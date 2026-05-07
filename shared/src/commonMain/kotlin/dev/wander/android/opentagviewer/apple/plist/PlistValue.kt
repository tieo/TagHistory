package io.github.tieo.taghistory.apple.plist

/**
 * Typed Apple property-list value tree.
 *
 * The Java port this replaces modeled plists as `Map<String, Object>` with
 * unchecked casts at every read site. That cost us real bugs — most recently
 * `String cannot be cast to byte[]` on the GSA `c` cookie — so every leaf
 * shape is now its own sealed subtype and reads go through explicit
 * accessors that surface a mismatch at the point of use, not three call
 * frames away.
 *
 * Encoding correspondence (XML + binary plist wire formats):
 *   `<dict>`     `D0..DF / 0F..1F` → [Dict]
 *   `<array>`    `A0..AF / 0A..1F` → [Array]
 *   `<string>`   `5X / 6X`         → [Str]
 *   `<data>`     `4X`              → [Data]
 *   `<integer>`  `1X`              → [Int64]
 *   `<real>`     `2X`              → [Real]
 *   `<true/>` `<false/>` `0x08/0x09` → [Bool]
 *   `<date>`     `0x33`            → [Date]
 *   n/a          `0x80..8X`        → [Uid] (NSKeyedArchiver only)
 *   `<null/>`    `0x00`            → [Null]
 */
sealed interface PlistValue {

    data object Null : PlistValue

    data class Bool(val value: Boolean) : PlistValue

    /** Integer plist value. Apple uses signed 64-bit in the wire format. */
    data class Int64(val value: Long) : PlistValue

    data class Real(val value: Double) : PlistValue

    data class Str(val value: String) : PlistValue

    /** Raw bytes. Equality is by content so tests can assert against a literal. */
    class Data(val bytes: ByteArray) : PlistValue {
        override fun equals(other: Any?): Boolean =
            other is Data && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String {
            val head = bytes.take(8).joinToString("") { b ->
                val v = b.toInt() and 0xff
                val hi = v ushr 4
                val lo = v and 0xf
                "${hexDigit(hi)}${hexDigit(lo)}"
            }
            val ellipsis = if (bytes.size > 8) "…" else ""
            return "Data(${bytes.size} bytes, hex=$head$ellipsis)"
        }

        private fun hexDigit(n: Int): Char =
            if (n < 10) ('0' + n) else ('a' + (n - 10))
    }

    /**
     * Absolute instant encoded as milliseconds since the Unix epoch. The
     * wire format uses the NSDate reference (2001-01-01Z); converted on
     * parse so every consumer speaks a single time domain.
     */
    data class Date(val epochMillis: Long) : PlistValue

    /** NSKeyedArchiver object-graph pointer. Only appears inside archiver blobs. */
    data class Uid(val value: Long) : PlistValue

    /**
     * Ordered dictionary. Iteration order matches insertion order so the
     * encoded output stays diff-stable against the Python reference
     * implementation (findmy/pysrp).
     */
    data class Dict(val entries: Map<String, PlistValue> = emptyMap()) : PlistValue {
        operator fun get(key: String): PlistValue? = entries[key]

        fun string(key: String): String? = (entries[key] as? Str)?.value
        fun data(key: String): ByteArray? = (entries[key] as? Data)?.bytes
        fun int64(key: String): Long? = (entries[key] as? Int64)?.value
        fun bool(key: String): Boolean? = (entries[key] as? Bool)?.value
        fun dict(key: String): Dict? = entries[key] as? Dict
        fun array(key: String): Array? = entries[key] as? Array

        /**
         * Reads an opaque field that may appear as either a string or raw
         * bytes. Apple's GSA `c` (cookie) is typed this way: currently
         * NSString, historically NSData. See
         * reference_apple_gsa_quirks.md quirk #2.
         */
        fun opaque(key: String): ByteArray? = when (val v = entries[key]) {
            is Str -> v.value.encodeToByteArray()
            is Data -> v.bytes
            null -> null
            else -> throw IllegalArgumentException(
                "Expected opaque bytes/string for \"$key\", got ${v::class.simpleName}")
        }
    }

    data class Array(val items: List<PlistValue> = emptyList()) : PlistValue {
        val size: Int get() = items.size
        operator fun get(index: Int): PlistValue = items[index]
    }
}

/** Build a [PlistValue.Dict] preserving insertion order for diff-stable output. */
fun plistDictOf(vararg pairs: Pair<String, PlistValue>): PlistValue.Dict =
    PlistValue.Dict(linkedMapOf(*pairs))

fun plistArrayOf(vararg items: PlistValue): PlistValue.Array =
    PlistValue.Array(items.toList())

fun String.asPlist(): PlistValue = PlistValue.Str(this)
fun Long.asPlist(): PlistValue = PlistValue.Int64(this)
fun Int.asPlist(): PlistValue = PlistValue.Int64(toLong())
fun Boolean.asPlist(): PlistValue = PlistValue.Bool(this)
fun ByteArray.asPlist(): PlistValue = PlistValue.Data(this)
fun Double.asPlist(): PlistValue = PlistValue.Real(this)

/**
 * Best-effort coercion from a heterogeneously-typed tree (`Map<String, Any>`
 * + `List<Any>` + primitives) into a [PlistValue] tree. Used by the GSA /
 * MobileMe clients, which build request bodies as ergonomic untyped maps
 * and then serialize through [XmlPlist.encode].
 *
 * Leaves through `PlistValue` untouched — passing an already-converted
 * tree is idempotent.
 */
fun anyToPlist(value: Any?): PlistValue = when (value) {
    null -> PlistValue.Null
    is PlistValue -> value
    is String -> PlistValue.Str(value)
    is Boolean -> PlistValue.Bool(value)
    is Byte -> PlistValue.Int64(value.toLong())
    is Short -> PlistValue.Int64(value.toLong())
    is Int -> PlistValue.Int64(value.toLong())
    is Long -> PlistValue.Int64(value)
    is Float -> PlistValue.Real(value.toDouble())
    is Double -> PlistValue.Real(value)
    is ByteArray -> PlistValue.Data(value)
    is Map<*, *> -> {
        val out = linkedMapOf<String, PlistValue>()
        for ((k, v) in value) {
            val key = k as? String
                ?: throw IllegalArgumentException("Plist dict keys must be String, got ${k?.let { it::class.simpleName }}")
            out[key] = anyToPlist(v)
        }
        PlistValue.Dict(out)
    }
    is List<*> -> PlistValue.Array(value.map { anyToPlist(it) })
    else -> throw IllegalArgumentException(
        "Cannot encode ${value::class.simpleName} as a plist value"
    )
}
