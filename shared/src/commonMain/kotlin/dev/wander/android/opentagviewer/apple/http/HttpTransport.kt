package io.github.tieo.taghistory.apple.http

/**
 * Minimal suspend HTTP abstraction used by the GSA / MobileMe / anisette
 * bridge clients.
 *
 * Kept small on purpose: a single POST/GET/PUT with a byte body in, a
 * byte body + status + headers out. Everything we talk to Apple about
 * here is one-shot — no streaming, no chunked uploads, no redirect knob.
 * Plist / JSON parsing happens a layer above.
 *
 * Implementations are pluggable:
 *  - [KtorHttpTransport] wraps a [io.ktor.client.HttpClient] for
 *    production (OkHttp on Android, CIO on desktop).
 *  - Tests use Ktor's `MockEngine` + [KtorHttpTransport] together so the
 *    login flow can be exercised offline and asserted at the HTTP-shape
 *    level.
 *
 * Every header lookup in [HttpResponse] is case-insensitive — Apple's
 * servers have historically flipped casing on e.g. `X-Apple-Id-Session-Id`
 * vs `x-apple-id-session-id`, which burned the Java port more than once.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * Single HTTP request. [headers] preserves insertion order so request
 * shape stays diff-stable against Python's `requests` library, which
 * several Apple endpoints are sensitive to.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        if (method != other.method) return false
        if (url != other.url) return false
        if (headers != other.headers) return false
        val a = body
        val b = other.body
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.contentEquals(b)
    }
    override fun hashCode(): Int {
        var h = method.hashCode()
        h = 31 * h + url.hashCode()
        h = 31 * h + headers.hashCode()
        h = 31 * h + (body?.contentHashCode() ?: 0)
        return h
    }
}

class HttpResponse(
    val statusCode: Int,
    headers: Map<String, String>,
    val body: ByteArray,
) {
    private val headersCaseInsensitive: Map<String, String> =
        headers.mapKeys { it.key.lowercase() }

    val headers: Map<String, String> = headers

    fun header(name: String): String? = headersCaseInsensitive[name.lowercase()]

    fun isOk(): Boolean = statusCode in 200..299
}
