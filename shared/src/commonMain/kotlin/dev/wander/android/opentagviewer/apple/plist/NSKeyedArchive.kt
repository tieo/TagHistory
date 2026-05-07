package io.github.tieo.taghistory.apple.plist

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Resolver for `NSKeyedArchiver`-serialized property lists.
 *
 * Apple serializes object graphs by flattening them into a `$objects`
 * array, then referring to each node through [PlistValue.Uid] indices
 * stored under `$top`. This reader walks those pointers for the classes
 * we actually care about — `NSDictionary`, `NSArray`, `NSString`,
 * `NSDate`, `NSData` and their mutable variants — and returns a plain
 * [PlistValue] tree with the archive chrome stripped out.
 *
 * Unknown classes are passed through as plain [PlistValue.Dict]s so
 * callers can still walk whatever shape they have instead of failing
 * loudly. The failure mode we want to avoid is silently dropping a
 * field Apple added, not an unknown-class exception.
 *
 * Output semantics match the Java [NSKeyedArchiveReader] port: an
 * archived `CKRecord` decodes to a [PlistValue.Dict] where `RecordCtime`
 * and `RecordMtime` are [PlistValue.Date] and `ModifiedByDevice` is a
 * [PlistValue.Str].
 */
object NSKeyedArchive {

    private const val ARCHIVER_KEY = "\$archiver"
    private const val EXPECTED_ARCHIVER = "NSKeyedArchiver"
    private const val OBJECTS_KEY = "\$objects"
    private const val TOP_KEY = "\$top"
    private const val ROOT_KEY = "root"
    private const val CLASS_KEY = "\$class"
    private const val CLASSNAME_KEY = "\$classname"
    private const val CLASSES_KEY = "\$classes"

    /** Parse raw bytes (bplist00 or XML) and resolve the object graph. */
    fun parse(data: ByteArray): PlistValue {
        val root = if (BinaryPlist.isBinaryPlist(data)) {
            BinaryPlist.parse(data)
        } else {
            XmlPlist.parse(data)
        }
        return resolveRoot(root)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun parseBase64(base64: String): PlistValue =
        parse(Base64.decode(base64))

    private fun resolveRoot(root: PlistValue): PlistValue {
        val top = root as? PlistValue.Dict
            ?: throw PlistParseException("NSKeyedArchiver root must be a dict, got ${root::class.simpleName}")

        val archiver = top.string(ARCHIVER_KEY)
        if (archiver != EXPECTED_ARCHIVER) {
            throw PlistParseException(
                "Not an NSKeyedArchiver archive: \$archiver=$archiver"
            )
        }
        val objects = top.array(OBJECTS_KEY)
            ?: throw PlistParseException("Missing \$objects array")
        val topDict = top.dict(TOP_KEY)
            ?: throw PlistParseException("Missing \$top dictionary")

        val ctx = Context(objects)
        val rootRef = topDict[ROOT_KEY]
        if (rootRef != null) {
            return ctx.resolve(rootRef)
        }
        // No "root" — treat every $top entry as a named root.
        val out = LinkedHashMap<String, PlistValue>()
        for ((key, ref) in topDict.entries) {
            out[key] = ctx.resolve(ref)
        }
        return PlistValue.Dict(out)
    }

    private class Context(val objects: PlistValue.Array) {
        /**
         * Identity-keyed cache on the resolved [PlistValue]. Archive graphs
         * can be cyclic (e.g., an NSString pointing at its class which
         * points back), and we want to detect revisits before we stack-
         * overflow. Kotlin's `===` on equal-by-content strings is
         * unreliable, so we key on the backing `PlistValue` identity via
         * [IdentityMap].
         */
        private val inFlight = HashSet<Int>()
        private val resolvedByIndex = HashMap<Int, PlistValue>()

        fun resolve(value: PlistValue): PlistValue {
            if (value is PlistValue.Uid) {
                val idx = value.value.toInt()
                if (idx < 0 || idx >= objects.size) {
                    throw PlistParseException("UID $idx out of range (size=${objects.size})")
                }
                resolvedByIndex[idx]?.let { return it }
                if (!inFlight.add(idx)) {
                    // Cycle — return a placeholder Null. Real archives don't
                    // hit this path for the fields we read, but Apple has
                    // shipped cyclic $classes chains before so we'd rather
                    // degrade than loop forever.
                    return PlistValue.Null
                }
                val target = objects[idx]
                // "$null" sentinel → typed Null.
                if (target is PlistValue.Str && target.value == "\$null") {
                    inFlight.remove(idx)
                    resolvedByIndex[idx] = PlistValue.Null
                    return PlistValue.Null
                }
                val built = resolveObject(target)
                inFlight.remove(idx)
                resolvedByIndex[idx] = built
                return built
            }
            return resolveObject(value)
        }

        private fun resolveObject(value: PlistValue): PlistValue = when (value) {
            is PlistValue.Dict -> buildFromDict(value, classNameOf(value))
            is PlistValue.Array -> PlistValue.Array(value.items.map(::resolve))
            else -> value
        }

        private fun buildFromDict(d: PlistValue.Dict, className: String?): PlistValue {
            when (className) {
                null, "NSDictionary", "NSMutableDictionary" -> {
                    val keysArr = d.array("NS.keys")
                    val valsArr = d.array("NS.objects")
                    if (keysArr != null && valsArr != null) {
                        val n = minOf(keysArr.size, valsArr.size)
                        val out = LinkedHashMap<String, PlistValue>(n)
                        for (i in 0 until n) {
                            val key = resolve(keysArr[i])
                            val keyStr = (key as? PlistValue.Str)?.value
                                ?: key.toString()
                            out[keyStr] = resolve(valsArr[i])
                        }
                        return PlistValue.Dict(out)
                    }
                    return plainMap(d)
                }
                "NSArray", "NSMutableArray", "NSSet", "NSMutableSet" -> {
                    val valsArr = d.array("NS.objects")
                        ?: return PlistValue.Array(emptyList())
                    return PlistValue.Array(valsArr.items.map(::resolve))
                }
                "NSDate" -> {
                    val t = d["NS.time"]
                    val secs = when (t) {
                        is PlistValue.Real -> t.value
                        is PlistValue.Int64 -> t.value.toDouble()
                        else -> return PlistValue.Null
                    }
                    return PlistValue.Date((secs * 1000.0).toLong() + NSDATE_EPOCH_MILLIS)
                }
                "NSData", "NSMutableData" -> {
                    val bytes = d.data("NS.data") ?: d.data("NS.bytes") ?: ByteArray(0)
                    return PlistValue.Data(bytes)
                }
                "NSString", "NSMutableString" -> {
                    val s = d.string("NS.string") ?: return PlistValue.Null
                    return PlistValue.Str(s)
                }
                else -> return plainMap(d)
            }
        }

        private fun plainMap(d: PlistValue.Dict): PlistValue.Dict {
            val out = LinkedHashMap<String, PlistValue>()
            for ((k, v) in d.entries) {
                if (k == CLASS_KEY) continue
                out[k] = resolve(v)
            }
            return PlistValue.Dict(out)
        }

        private fun classNameOf(d: PlistValue.Dict): String? {
            val classRef = d[CLASS_KEY] as? PlistValue.Uid ?: return null
            val idx = classRef.value.toInt()
            if (idx < 0 || idx >= objects.size) return null
            val target = objects[idx] as? PlistValue.Dict ?: return null
            target.string(CLASSNAME_KEY)?.let { return it }
            // Some archives only ship $classes (inheritance chain); the leaf
            // class is the first entry.
            val chain = target.array(CLASSES_KEY) ?: return null
            return (chain.items.firstOrNull() as? PlistValue.Str)?.value
        }
    }

    private const val NSDATE_EPOCH_MILLIS = 978_307_200_000L
}
