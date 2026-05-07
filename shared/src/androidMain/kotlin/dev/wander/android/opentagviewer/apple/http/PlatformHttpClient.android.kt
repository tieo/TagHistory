package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android engine = OkHttp. Apple Root CA trust-anchor pinning is layered
 * in via [AppleTlsConfig.applyTo] so tests can swap in a MockEngine
 * without dragging in the full TLS config.
 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(false)
            AppleTlsConfig.applyTo(this)
        }
    }
}
