package io.github.tieo.taghistory.apple.anisette

import io.github.tieo.taghistory.anisette.AnisetteProvider
import io.github.tieo.taghistory.anisette.MachineIdentity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Builds the anisette part of Apple requests from [provider]: the machine's
 * one-time password and id together with its identity (local user, device
 * id, serial, client info). Every request of a session has to carry the
 * same machine, because Apple binds the tokens it issues to it.
 *
 * Provider values are cached for [CACHE_TTL_SECONDS] seconds, which is the
 * one-time password's lifetime, so a burst of requests costs one emulated
 * CoreADI session.
 */
@OptIn(ExperimentalTime::class)
class AnisetteClient(
    private val provider: AnisetteProvider,
    /** For test determinism: fixed clock in unit tests, system time in prod. */
    private val clockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var cachedHeaders: Map<String, String>? = null
    private var cacheExpiresAtMs: Long = 0L
    // Serializes the expensive provider call. A map refresh fans out one
    // fetch per beacon in parallel; without this gate every concurrent
    // caller misses the empty cache at the same instant and each runs its
    // own emulated CoreADI session. The lock collapses a burst to one call.
    private val refreshLock = Mutex()

    /** The machine every request is sent as. */
    suspend fun machine(): MachineIdentity = provider.machine()

    /**
     * Anisette headers for a request. [withClientInfo] adds the client info
     * trio the 2FA endpoints want, which names Xcode on this machine.
     */
    suspend fun getHeaders(withClientInfo: Boolean = false): Map<String, String> {
        val headers = LinkedHashMap(fetchAnisetteDict())
        if (withClientInfo) {
            headers["X-Mme-Client-Info"] = machine().clientInfoFor(XCODE_BUNDLE)
            headers["X-Apple-App-Info"] = "com.apple.gs.xcode.auth"
            headers["X-Xcode-Version"] = "11.2 (11B41)"
        }
        return headers
    }

    /**
     * "Client provided data": the header set embedded inside the GSA SRP
     * request body, plus the constant `bootstrap`/`icscrec`/`pbe`/`prkgen`/
     * `svct` flags.
     */
    suspend fun getCpd(): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        out["bootstrap"] = true
        out["icscrec"] = true
        out["pbe"] = false
        out["prkgen"] = true
        out["svct"] = "iCloud"
        out.putAll(fetchAnisetteDict())
        return out
    }

    /**
     * Answers Apple's resync request ([sim] is the `X-Apple-I-MD-DATA` of a
     * `hsc 434` reply) and drops the cached headers, which Apple refused.
     */
    suspend fun resync(sim: ByteArray) {
        refreshLock.withLock {
            provider.resync(sim)
            cachedHeaders = null
            cacheExpiresAtMs = 0L
        }
    }

    private suspend fun fetchAnisetteDict(): Map<String, String> {
        cachedHeaders?.let { if (clockMillis() < cacheExpiresAtMs) return it }

        return refreshLock.withLock {
            // Re-check under the lock: a caller that won the race may have
            // already populated the cache while we were waiting, so the
            // rest of the burst reuses its result instead of re-running.
            cachedHeaders?.let { if (clockMillis() < cacheExpiresAtMs) return@withLock it }

            val fresh = provider.getHeaders()
            cachedHeaders = fresh
            cacheExpiresAtMs = clockMillis() + CACHE_TTL_SECONDS * 1000L
            fresh
        }
    }

    companion object {
        /** Cache the provider's one-time password for this many seconds. */
        const val CACHE_TTL_SECONDS: Int = 30

        /** The bundle the 2FA endpoints expect in the client info. */
        const val XCODE_BUNDLE = "com.apple.AOSKit/282 (com.apple.dt.Xcode/3594.4.19)"
    }
}
