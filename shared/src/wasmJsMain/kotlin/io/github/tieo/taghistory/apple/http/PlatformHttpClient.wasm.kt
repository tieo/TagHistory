package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient

actual fun createPlatformHttpClient(): HttpClient = HttpClient()
