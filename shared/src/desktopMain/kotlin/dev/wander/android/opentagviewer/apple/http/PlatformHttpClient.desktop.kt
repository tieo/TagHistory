package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Desktop = CIO engine. The Compose MP desktop target is primarily a
 * dev harness today, so default JDK trust is fine.
 */
actual fun createPlatformHttpClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
}
