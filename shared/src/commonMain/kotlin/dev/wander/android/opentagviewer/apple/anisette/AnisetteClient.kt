package io.github.tieo.taghistory.apple.anisette

import io.github.tieo.taghistory.anisette.AnisetteProvider
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Kotlin equivalent of the Java port's `AnisetteClient` +
 * `RemoteAnisetteProvider`. Builds the full GSA header dictionary by
 * combining:
 *
 *  - The OTP/MID pair fetched from the on-device native bridge via
 *    [AnisetteProvider] (which under the hood is Apple's ADI through
 *    omnisette via the Rust ottjni crate).
 *  - Per-request synthesized values — timestamp, timezone, locale,
 *    device-id, user-MD-LU — generated client-side to match the Python
 *    `findmy` reference implementation byte-for-byte.
 *
 * Provider values are cached for [CACHE_TTL_SECONDS] seconds. Apple's
 * server accepts a header pair for longer than that, so the cache TTL
 * just matches pysrp's default; pinning it here keeps login retries
 * cheap without risking a stale MD-M rejection.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class AnisetteClient(
    private val provider: AnisetteProvider,
    /** For test determinism — fixed clock in unit tests, system time in prod. */
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Short timezone name, e.g. `UTC` or `PDT`. Tests inject a fixed value. */
    private val timeZoneAbbreviation: () -> String = { "UTC" },
    /** BCP-47 underscore style, e.g. `en_US`. Tests inject a fixed value. */
    private val locale: () -> String = { "en_US" },
) {
    private var cachedHeaders: Map<String, String>? = null
    private var cacheExpiresAtMs: Long = 0L

    /** Bare header set suitable for the outer HTTP request. */
    suspend fun getHeaders(userId: String, deviceId: String, serial: String): Map<String, String> =
        getHeaders(userId, deviceId, serial, withClientInfo = false)

    /**
     * Header set including the `X-Mme-Client-Info` trio (User-Agent-ish).
     * Required on 2FA endpoints; omit on the core GSA SRP flow to match
     * the Python reference.
     */
    suspend fun getHeaders(
        userId: String,
        deviceId: String,
        serial: String?,
        withClientInfo: Boolean,
    ): Map<String, String> {
        val anisette = fetchAnisetteDict()

        return linkedMapOf<String, String>().apply {
            put("X-Apple-I-Client-Time", currentIsoTimestamp())
            put("X-Apple-I-TimeZone", timeZoneAbbreviation())
            put("loc", locale())
            put("X-Apple-Locale", locale())
            put("X-Apple-I-MD", anisette["X-Apple-I-MD"].orEmpty())
            put(
                "X-Apple-I-MD-LU",
                Base64.encode(userId.encodeToByteArray()),
            )
            put("X-Apple-I-MD-M", anisette["X-Apple-I-MD-M"].orEmpty())
            put("X-Apple-I-MD-RINFO", ROUTER)
            put("X-Mme-Device-Id", deviceId.uppercase())
            put("X-Apple-I-SRL-NO", serial ?: "0")

            if (withClientInfo) {
                put("X-Mme-Client-Info", CLIENT_INFO)
                put("X-Apple-App-Info", "com.apple.gs.xcode.auth")
                put("X-Xcode-Version", "11.2 (11B41)")
            }
        }
    }

    /**
     * "Client Pairing Data" — the header-superset dict embedded inside
     * the GSA SRP request body. Mirrors [getHeaders] plus the five
     * constant `bootstrap`/`icscrec`/`pbe`/`prkgen`/`svct` flags.
     */
    suspend fun getCpd(userId: String, deviceId: String, serial: String): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        out["bootstrap"] = true
        out["icscrec"] = true
        out["pbe"] = false
        out["prkgen"] = true
        out["svct"] = "iCloud"
        out.putAll(getHeaders(userId, deviceId, serial))
        return out
    }

    /** Used by [io.github.tieo.taghistory.apple.gsa.GsaClient] to populate `X-MMe-Client-Info`. */
    fun clientInfo(): String = CLIENT_INFO

    private suspend fun fetchAnisetteDict(): Map<String, String> {
        val now = clockMillis()
        val cached = cachedHeaders
        if (cached != null && now < cacheExpiresAtMs) return cached

        val fresh = provider.getHeaders()
        cachedHeaders = fresh
        cacheExpiresAtMs = now + CACHE_TTL_SECONDS * 1000L
        return fresh
    }

    private fun currentIsoTimestamp(): String {
        val instant = Instant.fromEpochMilliseconds(clockMillis())
        // Manual ISO-8601 (seconds precision, `Z` suffix) — avoids pulling
        // kotlinx-datetime in for what's effectively one format string.
        val s = instant.toString() // e.g. 2026-04-21T20:30:00.123Z
        val dotIdx = s.indexOf('.')
        return if (dotIdx >= 0) s.substring(0, dotIdx) + "Z" else s
    }

    companion object {
        /** Cache the upstream OTP/MID pair for this many seconds. */
        const val CACHE_TTL_SECONDS: Int = 30

        // Python pins these hard-coded values — match exactly. See
        // BaseAnisetteProvider.router / .client in findmy/reports/anisette.py.
        private const val ROUTER = "17106176"
        private const val CLIENT_INFO =
            "<MacBookPro18,3> <Mac OS X;13.4.1;22F8> " +
                "<com.apple.AOSKit/282 (com.apple.dt.Xcode/3594.4.19)>"
    }
}
