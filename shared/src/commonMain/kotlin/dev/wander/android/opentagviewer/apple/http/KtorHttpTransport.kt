package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse as KtorHttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * Ktor-backed [HttpTransport]. Platform code constructs the [HttpClient]
 * with an engine of its choice (OkHttp on Android, CIO on desktop,
 * MockEngine in tests).
 *
 * The Ktor client here is intentionally "raw" — no content negotiation,
 * no retry plugin, no logging plugin. Apple's GSA endpoint is picky about
 * exactly the bytes on the wire, and Ktor plugins have a habit of
 * rewriting [io.ktor.http.HttpHeaders.ContentType] out from under the
 * caller. Last quarter we shipped an HTTP 415 on `/GsService2` because
 * `ContentNegotiation` rewrote our `text/x-xml-plist` body to
 * `application/xml`. This class passes headers through verbatim —
 * [HttpTransportHeadersVerbatimTest] locks that contract in.
 */
class KtorHttpTransport(private val client: HttpClient) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val ktorResponse: KtorHttpResponse = client.request(request.url) {
            method = HttpMethod.parse(request.method)

            // Set explicit Content-Type from the caller's headers if present.
            // We do this *before* the headers{} block so any Content-Type in
            // request.headers still wins over the engine default.
            val contentTypeHeader = request.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            if (contentTypeHeader != null) {
                contentType(ContentType.parse(contentTypeHeader.value))
            }

            headers {
                for ((name, value) in request.headers) {
                    // Skip Content-Type — already set above. Appending it
                    // again would give Ktor's HeadersBuilder two entries and
                    // produce a malformed header on the wire.
                    if (name.equals("Content-Type", ignoreCase = true)) continue
                    append(name, value)
                }
            }

            if (request.body != null) {
                setBody(request.body)
            }
        }

        val responseHeaders = LinkedHashMap<String, String>()
        ktorResponse.headers.forEach { name, values ->
            val first = values.firstOrNull() ?: return@forEach
            responseHeaders[name] = first
        }

        return HttpResponse(
            statusCode = ktorResponse.status.value,
            headers = responseHeaders,
            body = ktorResponse.bodyAsBytes(),
        )
    }
}
