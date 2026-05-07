package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient

/**
 * iOS is not a ship target today and the Darwin engine isn't in our
 * offline artifact set. Callers on iOS must instantiate [KtorHttpTransport]
 * directly with a platform-supplied [HttpClient]; this factory throws so
 * a silent misconfiguration is impossible.
 */
actual fun createPlatformHttpClient(): HttpClient =
    throw NotImplementedError("iOS platform HTTP client is not configured yet")
