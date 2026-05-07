package io.github.tieo.taghistory.apple.plist

/**
 * Reader for Apple's `bplist00` binary property-list format.
 *
 * Layout (summarized from CoreFoundation's CFBinaryPlist.c):
 *   magic           8 bytes ("bplist00")
 *   object table    offset-variable, heterogeneous
 *   offset table    numObjects × offsetIntSize bytes (big-endian)
 *   trailer         32 bytes, fixed fields at end of file
 *
 * Object marker byte is split into a high nibble (type) and a low nibble
 * (either element count or a type-specific size code). A low nibble of
 * `0xF` means "next object is an integer giving the true count".
 *
 * NSKeyedArchiver blobs are bplist00 files whose `$objects` array uses
 * [PlistValue.Uid] references to encode cyclic / shared object graphs —
 * resolving those pointers is [NSKeyedArchive]'s job, not ours.
 */
object BinaryPlist {

    private val MAGIC = byteArrayOf(
        'b'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'i'.code.toByte(),
        's'.code.toByte(), 't'.code.toByte(), '0'.code.toByte(), '0'.code.toByte()
    )

    fun isBinaryPlist(data: ByteArray): Boolean {
        if (data.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return true
    }

    fun parse(data: ByteArray): PlistValue {
        if (!isBinaryPlist(data)) {
            throw PlistParseException("Not a bplist00 document")
        }
        if (data.size < MAGIC.size + TRAILER_SIZE) {
            throw PlistParseException("Truncated bplist — size=${data.size}")
        }

        val trailerStart = data.size - TRAILER_SIZE
        val offsetIntSize = (data[trailerStart + 6].toInt() and 0xFF)
        val objectRefSize = (data[trailerStart + 7].toInt() and 0xFF)
        val numObjects = readLongBE(data, trailerStart + 8, 8)
        val topObject = readLongBE(data, trailerStart + 16, 8)
        val offsetTableOffset = readLongBE(data, trailerStart + 24, 8)

        if (numObjects < 0 || numObjects > Int.MAX_VALUE.toLong()) {
            throw PlistParseException("Absurd numObjects=$numObjects")
        }
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
            throw PlistParseException(
                "Unsupported size fields: offsetIntSize=$offsetIntSize objectRefSize=$objectRefSize"
            )
        }

        val ctx = Context(
            data = data,
            offsetIntSize = offsetIntSize,
            objectRefSize = objectRefSize,
            numObjects = numObjects.toInt(),
            offsetTableOffset = offsetTableOffset,
        )
        return ctx.readObjectAt(topObject.toInt())
    }

    private const val TRAILER_SIZE = 32

    private class Context(
        val data: ByteArray,
        val offsetIntSize: Int,
        val objectRefSize: Int,
        val numObjects: Int,
        val offsetTableOffset: Long,
    ) {
        fun readObjectAt(index: Int): PlistValue {
            if (index < 0 || index >= numObjects) {
                throw PlistParseException("Object index $index out of range [0,$numObjects)")
            }
            val offsetPos = (offsetTableOffset + index.toLong() * offsetIntSize).toInt()
            val objectOffset = readLongBE(data, offsetPos, offsetIntSize).toInt()
            return readObject(objectOffset)
        }

        private fun readObject(offsetIn: Int): PlistValue {
            var offset = offsetIn
            val marker = data[offset].toInt() and 0xFF
            offset += 1
            val high = marker ushr 4
            val low = marker and 0x0F

            return when (high) {
                0x0 -> when (marker) {
                    0x00 -> PlistValue.Null
                    0x08 -> PlistValue.Bool(false)
                    0x09 -> PlistValue.Bool(true)
                    0x0F -> throw PlistParseException("Fill byte at object root")
                    else -> throw PlistParseException("Unknown primitive marker 0x%02x".hexFmt(marker))
                }

                0x1 -> {
                    // Integer: 2^low bytes big-endian. low=4 (16 bytes) is rare;
                    // treat as unsigned and clamp into Long — NSKeyedArchiver
                    // never emits 16-byte ints for UID indices.
                    val byteCount = 1 shl low
                    if (byteCount > 8) {
                        throw PlistParseException("Integer > 64 bits is unsupported (byteCount=$byteCount)")
                    }
                    val value = if (byteCount == 8) {
                        // 8-byte ints are signed in bplist00.
                        readLongBE(data, offset, 8)
                    } else {
                        // Smaller ints are unsigned.
                        readUnsignedBE(data, offset, byteCount)
                    }
                    PlistValue.Int64(value)
                }

                0x2 -> {
                    // Real: 4 bytes = float32, 8 bytes = double64. Apple only
                    // ever writes 4 or 8.
                    val byteCount = 1 shl low
                    val value = when (byteCount) {
                        4 -> Float.fromBits(readUnsignedBE(data, offset, 4).toInt()).toDouble()
                        8 -> Double.fromBits(readLongBE(data, offset, 8))
                        else -> throw PlistParseException("Unsupported real size $byteCount")
                    }
                    PlistValue.Real(value)
                }

                0x3 -> {
                    // 0x33: NSDate, 8-byte big-endian double, seconds since
                    // 2001-01-01T00:00:00Z.
                    if (marker != 0x33) {
                        throw PlistParseException("Unknown 0x3X marker 0x%02x".hexFmt(marker))
                    }
                    val secondsSince2001 = Double.fromBits(readLongBE(data, offset, 8))
                    val millisSince2001 = (secondsSince2001 * 1000.0).toLong()
                    PlistValue.Date(millisSince2001 + NSDATE_EPOCH_MILLIS)
                }

                0x4 -> {
                    // Data: low = byteCount, or 0xF → length-prefixed.
                    val (count, payloadOffset) = readCount(low, offset)
                    val bytes = data.copyOfRange(payloadOffset, payloadOffset + count)
                    PlistValue.Data(bytes)
                }

                0x5 -> {
                    // ASCII string, each code unit is 1 byte.
                    val (count, payloadOffset) = readCount(low, offset)
                    val bytes = data.copyOfRange(payloadOffset, payloadOffset + count)
                    PlistValue.Str(bytes.decodeToString())
                }

                0x6 -> {
                    // UTF-16BE, count is in code units (2 bytes each).
                    val (count, payloadOffset) = readCount(low, offset)
                    val byteCount = count * 2
                    PlistValue.Str(decodeUtf16Be(data, payloadOffset, byteCount))
                }

                0x7 -> {
                    // UTF-8 string — rare (bplist00 doesn't formally use this,
                    // but some tooling emits it). Treat low as byte count.
                    val (count, payloadOffset) = readCount(low, offset)
                    val bytes = data.copyOfRange(payloadOffset, payloadOffset + count)
                    PlistValue.Str(bytes.decodeToString())
                }

                0x8 -> {
                    // UID: low+1 bytes, big-endian, unsigned.
                    val byteCount = low + 1
                    if (byteCount > 8) {
                        throw PlistParseException("UID larger than 8 bytes (byteCount=$byteCount)")
                    }
                    val value = readUnsignedBE(data, offset, byteCount)
                    PlistValue.Uid(value)
                }

                0xA, 0xC -> {
                    // Array (0xA) or ordered set (0xC) — treated the same, since
                    // set ordering is significant in Apple archives.
                    val (count, payloadOffset) = readCount(low, offset)
                    val items = ArrayList<PlistValue>(count)
                    for (i in 0 until count) {
                        val refIndex = readUnsignedBE(
                            data, payloadOffset + i * objectRefSize, objectRefSize
                        ).toInt()
                        items.add(readObjectAt(refIndex))
                    }
                    PlistValue.Array(items)
                }

                0xD -> {
                    val (count, payloadOffset) = readCount(low, offset)
                    // count keys, then count values.
                    val keysBase = payloadOffset
                    val valuesBase = payloadOffset + count * objectRefSize
                    val entries = LinkedHashMap<String, PlistValue>(count)
                    for (i in 0 until count) {
                        val keyIndex = readUnsignedBE(
                            data, keysBase + i * objectRefSize, objectRefSize
                        ).toInt()
                        val valueIndex = readUnsignedBE(
                            data, valuesBase + i * objectRefSize, objectRefSize
                        ).toInt()
                        val keyValue = readObjectAt(keyIndex)
                        val keyString = (keyValue as? PlistValue.Str)?.value
                            ?: throw PlistParseException(
                                "Dict key #$i resolved to ${keyValue::class.simpleName}, expected string"
                            )
                        entries[keyString] = readObjectAt(valueIndex)
                    }
                    PlistValue.Dict(entries)
                }

                else -> throw PlistParseException("Unknown marker 0x%02x".hexFmt(marker))
            }
        }

        /**
         * Resolve an element count from the marker's low nibble. If the low
         * nibble is `0xF`, the next object is itself an integer giving the
         * real count; returns `(count, offsetPastTheCount)`.
         */
        private fun readCount(low: Int, offset: Int): Pair<Int, Int> {
            if (low != 0x0F) return low to offset
            val sizeMarker = data[offset].toInt() and 0xFF
            if (sizeMarker ushr 4 != 0x1) {
                throw PlistParseException("Extended count must be an int, got 0x%02x".hexFmt(sizeMarker))
            }
            val countByteCount = 1 shl (sizeMarker and 0x0F)
            val count = readUnsignedBE(data, offset + 1, countByteCount)
            if (count < 0 || count > Int.MAX_VALUE.toLong()) {
                throw PlistParseException("Absurd extended count $count")
            }
            return count.toInt() to (offset + 1 + countByteCount)
        }
    }

    /** NSDate reference point (2001-01-01T00:00:00Z) in Unix epoch millis. */
    private const val NSDATE_EPOCH_MILLIS = 978_307_200_000L

    private fun readLongBE(data: ByteArray, offset: Int, byteCount: Int): Long {
        var value = 0L
        for (i in 0 until byteCount) {
            value = (value shl 8) or ((data[offset + i].toInt() and 0xFF).toLong())
        }
        // Sign-extend when the caller said "signed" (8-byte ints). That's
        // the only call site that passes byteCount=8 for a signed quantity;
        // everything else passes <=4 which fits positive-only in Long.
        return value
    }

    private fun readUnsignedBE(data: ByteArray, offset: Int, byteCount: Int): Long {
        var value = 0L
        for (i in 0 until byteCount) {
            value = (value shl 8) or ((data[offset + i].toInt() and 0xFF).toLong())
        }
        return value
    }

    private fun decodeUtf16Be(data: ByteArray, offset: Int, byteCount: Int): String {
        // Hand-rolled UTF-16BE decoder — Kotlin/Native's Charsets lookup is
        // absent in commonMain. Handles surrogate pairs; treats an unpaired
        // surrogate as the replacement character U+FFFD so malformed Apple
        // strings don't crash the login flow.
        require(byteCount % 2 == 0) { "UTF-16BE payload must be even, got $byteCount" }
        val sb = StringBuilder(byteCount / 2)
        var i = 0
        while (i < byteCount) {
            val hi = ((data[offset + i].toInt() and 0xFF) shl 8) or
                    (data[offset + i + 1].toInt() and 0xFF)
            i += 2
            if (hi in 0xD800..0xDBFF && i < byteCount) {
                val lo = ((data[offset + i].toInt() and 0xFF) shl 8) or
                        (data[offset + i + 1].toInt() and 0xFF)
                if (lo in 0xDC00..0xDFFF) {
                    i += 2
                    sb.append(hi.toChar())
                    sb.append(lo.toChar())
                    continue
                }
                sb.append('�')
            } else if (hi in 0xDC00..0xDFFF) {
                sb.append('�')
            } else {
                sb.append(hi.toChar())
            }
        }
        return sb.toString()
    }

    private fun String.hexFmt(value: Int): String {
        val hi = (value ushr 4) and 0xF
        val lo = value and 0xF
        val c1 = if (hi < 10) ('0' + hi) else ('a' + (hi - 10))
        val c2 = if (lo < 10) ('0' + lo) else ('a' + (lo - 10))
        return this.replace("%02x", "$c1$c2")
    }
}
