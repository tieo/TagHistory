package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient

/**
 * Platform-specific factory for the Ktor [HttpClient] that backs
 * [KtorHttpTransport]. Kept separate so tests can construct a Ktor
 * client with MockEngine directly, without going through any platform
 * TLS / DNS / proxy config.
 *
 * Android's actual installs Apple's legacy Root CA as an additional trust
 * anchor so the GSA cert chain validates on devices that have dropped it
 * from their default store (see reference_apple_gsa_quirks.md quirk #1).
 * Desktop / iOS use their platform default trust stores — fine for dev
 * builds today, to be revisited if/when we ship on either.
 */
expect fun createPlatformHttpClient(): HttpClient

/**
 * Convenience factory for consumers (composeApp host) that don't need to
 * hold a reference to the underlying Ktor [HttpClient] — avoids dragging
 * `io.ktor.client.HttpClient` onto the consumer's compile classpath just
 * to construct [KtorHttpTransport].
 */
fun defaultPlatformHttpTransport(): HttpTransport =
    KtorHttpTransport(createPlatformHttpClient())
