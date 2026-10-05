package io.github.tieo.taghistory.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess

/**
 * Noto Color Emoji cut down to the given characters, from Google Fonts.
 *
 * The web app draws text on a canvas with its own fonts and has no emoji
 * font; the full one is 25 MB, a subset of the few emoji the tags use is a
 * few KB. Google picks the format from the User-Agent and only hands a modern
 * browser the small vector subset, so the server asks as one. Subsets are
 * cached, since the set of tag emoji rarely changes.
 */
class EmojiFonts(private val http: HttpClient) {
    private val cache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > MAX_CACHED
    }

    suspend fun subset(text: String): ByteArray? {
        val key = text.codePoints().distinct().sorted().toArray().joinToString("") { String(Character.toChars(it)) }
        synchronized(cache) { cache[key] }?.let { return it }
        val css = http.get(CSS_URL) {
            parameter("family", "Noto Color Emoji")
            parameter("text", key)
            header("User-Agent", MODERN_BROWSER)
        }
        if (!css.status.isSuccess()) return null
        val url = FONT_URL.find(css.bodyAsText())?.groupValues?.get(1) ?: return null
        val font = http.get(url) { header("User-Agent", MODERN_BROWSER) }
        if (!font.status.isSuccess()) return null
        val bytes = font.readRawBytes()
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    private companion object {
        const val CSS_URL = "https://fonts.googleapis.com/css2"
        const val MODERN_BROWSER =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        val FONT_URL = Regex("""url\((https://[^)]+)\)""")
        const val MAX_CACHED = 32
    }
}
