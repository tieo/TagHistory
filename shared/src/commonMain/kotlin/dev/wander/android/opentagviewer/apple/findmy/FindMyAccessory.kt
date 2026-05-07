package io.github.tieo.taghistory.apple.findmy

import io.github.tieo.taghistory.apple.plist.BinaryPlist
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * KMP port of `findmy/accessory.py#FindMyAccessory`. Wraps a primary +
 * secondary [AccessoryKeyGenerator] and surfaces the set of public keys
 * that could be active for a given instant.
 *
 * Apple's rollover cadence is 15 minutes for primary keys and 24 hours
 * (= 96 primary slots) for the secondary key, with one quirk: the very
 * first secondary rotation happens at the next *local* 4 AM after
 * pairing, not 96 slots later. That quirk lives in
 * [computeSecondaryOffset]; the timezone is communicated as a raw UTC
 * offset (seconds) so the whole class stays free of platform date
 * libraries.
 */
@OptIn(ExperimentalTime::class)
class FindMyAccessory(
    masterKey: ByteArray,
    skn: ByteArray,
    sks: ByteArray,
    val pairedAt: Instant,
    /** UTC offset of the pairing timezone, in seconds. Defaults to UTC. */
    val pairedZoneOffsetSeconds: Int = 0,
    var name: String?,
    val model: String?,
    val identifier: String?,
) {
    private val primary = AccessoryKeyGenerator(masterKey, skn, KeyType.PRIMARY)
    private val secondary = AccessoryKeyGenerator(masterKey, sks, KeyType.SECONDARY)

    fun interval(): kotlin.time.Duration = KEY_INTERVAL

    /** Index-based lookup. Skips the secondary-offset quirk on purpose — matches Java. */
    fun keysAt(index: Int): Set<KeyPair> {
        if (index < 0) return emptySet()
        return keysAtInternal(index, secondaryOffset = 0)
    }

    fun keysAt(when_: Instant): Set<KeyPair> {
        if (when_ < pairedAt) return emptySet()
        val minutes = (when_ - pairedAt).inWholeMinutes
        val index = (minutes / 15).toInt() + 1
        return keysAtInternal(index, computeSecondaryOffset())
    }

    fun keysBetween(start: Instant, end: Instant): Set<KeyPair> {
        val out = linkedSetOf<KeyPair>()
        var cursor = start
        while (cursor < end) {
            out.addAll(keysAt(cursor))
            cursor += KEY_INTERVAL
        }
        return out
    }

    fun keysBetween(start: Int, end: Int): Set<KeyPair> {
        val out = linkedSetOf<KeyPair>()
        for (i in start until end) out.addAll(keysAt(i))
        return out
    }

    private fun keysAtInternal(index: Int, secondaryOffset: Int): Set<KeyPair> {
        val keys = linkedSetOf<KeyPair>()
        keys += primary.keyAt(index)
        keys += secondary.keyAt(index / 96 + 1)
        if (index > secondaryOffset) {
            keys += secondary.keyAt((index - secondaryOffset) / 96 + 2)
        }
        return keys
    }

    private fun computeSecondaryOffset(): Int {
        val pairedEpochSec = pairedAt.epochSeconds
        val pairedLocalSec = pairedEpochSec + pairedZoneOffsetSeconds
        // Floor-division so negative local seconds (pre-1970 in a westerly
        // zone) still map to the start-of-day boundary correctly.
        val startOfLocalDaySec = floorDivLong(pairedLocalSec, SECONDS_PER_DAY) * SECONDS_PER_DAY
        var fourAmLocalSec = startOfLocalDaySec + FOUR_AM_SECONDS
        if (fourAmLocalSec <= pairedLocalSec) fourAmLocalSec += SECONDS_PER_DAY
        val firstRolloverEpochSec = fourAmLocalSec - pairedZoneOffsetSeconds
        val minutes = (firstRolloverEpochSec - pairedEpochSec) / 60
        return (minutes / 15).toInt() + 1
    }

    // Test seams — mirror the package-private accessors on the Java port.
    internal fun primaryGenerator(): AccessoryKeyGenerator = primary
    internal fun secondaryGenerator(): AccessoryKeyGenerator = secondary

    // Java has Math.floorDiv; Kotlin common doesn't expose a Long overload.
    // Floor towards negative infinity, matching Java's behavior.
    private fun floorDivLong(a: Long, b: Long): Long {
        val q = a / b
        return if ((a xor b) < 0 && q * b != a) q - 1 else q
    }

    companion object {
        val KEY_INTERVAL: kotlin.time.Duration = 15.minutes

        private const val SECONDS_PER_DAY: Long = 86_400L
        private const val FOUR_AM_SECONDS: Long = 4L * 60 * 60

        /**
         * Read a `.plist` exported from Apple's FindMy app. Mirrors the
         * Python `from_plist` helper, including the quirk that
         * `privateKey.key.data` is a DER blob whose last 28 bytes are the
         * scalar we actually want.
         *
         * Accepts either the binary (`bplist00`) or XML form — both appear
         * in the wild depending on the exporter.
         */
        fun fromPlist(bytes: ByteArray): FindMyAccessory {
            val root = if (BinaryPlist.isBinaryPlist(bytes)) {
                BinaryPlist.parse(bytes)
            } else {
                XmlPlist.parse(bytes)
            }
            val dict = root as? PlistValue.Dict
                ?: throw IllegalArgumentException("Accessory plist root is not a <dict>")

            val privateRaw = readKeyData(dict, "privateKey")
            require(privateRaw.size >= MasterKeyLength) {
                "privateKey blob shorter than $MasterKeyLength bytes"
            }
            val master = privateRaw.copyOfRange(privateRaw.size - MasterKeyLength, privateRaw.size)

            val skn = readKeyData(dict, "sharedSecret")
            val sks = dict["secondarySharedSecret"]?.let { readKeyData(dict, "secondarySharedSecret") }
                ?: readKeyData(dict, "secureLocationsSharedSecret")

            val pairingMs = (dict["pairingDate"] as? PlistValue.Date)?.epochMillis
                ?: throw IllegalArgumentException("Accessory plist missing pairingDate")

            val model = (dict["model"] as? PlistValue.Str)?.value
            val identifier = (dict["identifier"] as? PlistValue.Str)?.value

            return FindMyAccessory(
                masterKey = master,
                skn = skn,
                sks = sks,
                pairedAt = Instant.fromEpochMilliseconds(pairingMs),
                pairedZoneOffsetSeconds = 0, // plist exports are normalized to UTC
                name = null,
                model = model,
                identifier = identifier,
            )
        }

        /** Round-trip restore from our persisted fields map. */
        fun fromRestoredFields(fields: Map<String, Any?>): FindMyAccessory {
            val master = fields["master_key"] as ByteArray
            val skn = fields["skn"] as ByteArray
            val sks = fields["sks"] as ByteArray
            val epochMs = (fields["paired_at_epoch_ms"] as Number).toLong()
            val offset = (fields["paired_zone_offset_seconds"] as? Number)?.toInt() ?: 0
            return FindMyAccessory(
                masterKey = master,
                skn = skn,
                sks = sks,
                pairedAt = Instant.fromEpochMilliseconds(epochMs),
                pairedZoneOffsetSeconds = offset,
                name = fields["name"] as? String,
                model = fields["model"] as? String,
                identifier = fields["identifier"] as? String,
            )
        }

        private const val MasterKeyLength = 28

        private fun readKeyData(root: PlistValue.Dict, topKey: String): ByteArray {
            val outer = root.dict(topKey)
                ?: throw IllegalArgumentException("Accessory plist missing $topKey")
            val inner = outer.dict("key")
                ?: throw IllegalArgumentException("Accessory plist missing $topKey.key")
            return inner.data("data")
                ?: throw IllegalArgumentException("Accessory plist missing $topKey.key.data")
        }
    }
}
