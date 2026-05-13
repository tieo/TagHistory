package io.github.tieo.taghistory.nearby

import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Matches an observed BLE advertisement payload against the public keys
 * an owned AirTag could currently be broadcasting.
 *
 * AirTag's offline-finding advertisement carries the **trailing 22 bytes**
 * of its 28-byte P-224 public X coordinate inside the Apple manufacturer
 * data, plus the leading 6 bytes encoded in the BLE MAC address (top 2
 * bits of byte 0 forced to `10` to flag a static random address). The
 * trailing 22 bytes alone are 176 bits — vanishingly unlikely to collide
 * across the world's AirTags — so we index expected keys by their last
 * 22 bytes and reverse-lookup hits in O(1).
 *
 * Apple rotates the broadcast key every 15 minutes (primary) and every
 * 24 h (secondary). [primeAt] precomputes the set of valid keys for the
 * current minute ± a configurable window so a clock skew between phone
 * and tag doesn't drop matches.
 */
@OptIn(ExperimentalTime::class)
class NearbyMatcher(
    private val accessories: Map<String, FindMyAccessory>,
    private val now: () -> Instant = { Clock.System.now() },
) {

    /** beaconId, primary/secondary indicator and the scan timestamp. */
    data class Hit(val beaconId: String, val keyType: String)

    /** key tail (22 bytes) -> (beaconId, keyType). Rebuilt by primeAt. */
    private var table: Map<TailKey, Hit> = emptyMap()
    private var primedFor: Instant? = null
    private var primedWindow: Duration = Duration.ZERO

    /**
     * Pre-compute every expected adv-key tail for every accessory across
     * [now - window, now + window]. Idempotent: re-priming inside the
     * same window is a no-op.
     */
    fun primeAt(window: Duration = DEFAULT_WINDOW) {
        val nowInstant = now()
        if (primedFor != null &&
            (nowInstant - primedFor!!).absoluteValue < REFRESH_THRESHOLD &&
            window == primedWindow
        ) return
        val start = nowInstant - window
        val end = nowInstant + window
        val out = HashMap<TailKey, Hit>()
        for ((id, accessory) in accessories) {
            for (kp in accessory.keysBetween(start, end)) {
                val adv = kp.advKeyBytes()
                if (adv.size != 28) continue
                val tail = TailKey(adv.copyOfRange(6, 28))
                out[tail] = Hit(id, kp.keyType.name)
            }
        }
        table = out
        primedFor = nowInstant
        primedWindow = window
    }

    /**
     * @param trailing22 the manufacturer-data slice containing bytes
     *   `[6..28)` of the advertised P-224 public key.
     */
    fun match(trailing22: ByteArray): Hit? {
        if (trailing22.size != 22) return null
        return table[TailKey(trailing22)]
    }

    /** Test seam — exposes the indexed key count. */
    val primedKeyCount: Int get() = table.size

    private class TailKey(val bytes: ByteArray) {
        private val hash = bytes.contentHashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is TailKey && bytes.contentEquals(other.bytes)
    }

    private val Duration.absoluteValue: Duration
        get() = if (isNegative()) -this else this

    companion object {
        /** Cover ±2 slots (15-min each) to absorb clock skew. */
        val DEFAULT_WINDOW: Duration = 30.minutes

        /** Re-prime when the cached set is older than this. */
        val REFRESH_THRESHOLD: Duration = 5.minutes
    }
}
