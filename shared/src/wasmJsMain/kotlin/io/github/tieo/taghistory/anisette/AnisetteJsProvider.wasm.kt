package io.github.tieo.taghistory.anisette

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device anisette in the browser via the lbr77/anisette-js
 * Unicorn-Engine WASM emulator. Same security posture as the
 * Android ottjni bridge: ARM64 Android binaries (libCoreADI.so +
 * libstoreservicescore.so) execute in this user's session, the
 * machine ID never leaves the user's browser.
 *
 * The bridge expects three files at `/anisette/` on the served
 * origin (drop them in via `scripts/build-web-anisette.sh`):
 *
 *   * anisette.js — bundled TS API
 *   * anisette_rs.wasm — Unicorn engine
 *   * libstoreservicescore.so + libCoreADI.so — extracted from
 *     the Apple Music APK the same way the Android app does it
 *
 * Provisioning state (the `adi.pb` blob + device JSON) is held in
 * IndexedDB across reloads so the user only goes through Apple's
 * one-time provisioning flow once per browser profile.
 */
class AnisetteJsProvider private constructor() : AnisetteProvider {

    override suspend fun version(): String = "anisette-js (in-browser)"

    override suspend fun getHeaders(): Map<String, String> {
        val json = suspendCancellableCoroutine<String> { cont ->
            jsGetHeaders(
                onSuccess = { cont.resume(it) },
                onError = { msg -> cont.resumeWithException(AnisetteException(msg)) },
            )
        }
        // Bridge returns JSON `{ "X-Apple-I-MD": "...", ... }`.
        return parseFlatJson(json)
    }

    companion object {
        /**
         * Try to initialise the in-browser anisette bridge. Returns
         * null when the dist bundle is not present on this origin —
         * caller surfaces "sign-in unavailable" instead of crashing.
         */
        suspend fun create(): AnisetteJsProvider? {
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                jsInit(
                    onReady = { cont.resume(true) },
                    onMissing = { cont.resume(false) },
                )
            }
            return if (ok) AnisetteJsProvider() else null
        }
    }
}

private fun parseFlatJson(json: String): Map<String, String> {
    // anisette-js returns a flat string->string map. Minimal parser
    // to avoid pulling kotlinx-serialization into this file.
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < json.length) {
        val keyStart = json.indexOf('"', i)
        if (keyStart < 0) break
        val keyEnd = json.indexOf('"', keyStart + 1)
        if (keyEnd < 0) break
        val key = json.substring(keyStart + 1, keyEnd)
        val colon = json.indexOf(':', keyEnd + 1)
        if (colon < 0) break
        val valStart = json.indexOf('"', colon + 1)
        if (valStart < 0) break
        val valEnd = json.indexOf('"', valStart + 1)
        if (valEnd < 0) break
        out[key] = json.substring(valStart + 1, valEnd)
        i = valEnd + 1
    }
    return out
}

private fun jsInit(onReady: () -> Unit, onMissing: (String) -> Unit) {
    js(
        "window.__tagAnisette__.init().then(function(){ onReady() }).catch(function(e){ onMissing(String(e && e.message || e)) })"
    )
}

private fun jsGetHeaders(onSuccess: (String) -> Unit, onError: (String) -> Unit) {
    js(
        "window.__tagAnisette__.getHeaders().then(function(json){ onSuccess(json) }).catch(function(e){ onError(String(e && e.message || e)) })"
    )
}
