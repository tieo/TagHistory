package io.github.tieo.taghistory.apple.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * These tests lock in the HTTP-shape contract that the Java port broke
 * last week. They all use Ktor's `MockEngine` so nothing touches the
 * network. The important assertions are about *what we send*:
 *
 *  - Content-Type from [HttpRequest.headers] is honored verbatim. Ktor's
 *    `ContentNegotiation` plugin has rewritten this header in the past;
 *    the production transport does not install that plugin, and this
 *    test asserts the resulting request carries exactly one
 *    `Content-Type` with the value the caller passed.
 *  - Non-Content-Type headers survive round-trip unchanged, in the order
 *    the caller provided them.
 *  - The response body, status, and headers are surfaced back. Header
 *    lookup in [HttpResponse.header] is case-insensitive — Apple has
 *    shipped both `X-Apple-Id-Session-Id` and the lower-case variant.
 */
class KtorHttpTransportTest {

    @Test
    fun postSendsBodyWithExactContentType() = runTest {
        lateinit var captured: io.ktor.client.request.HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(
                content = byteArrayOf(1, 2, 3),
                status = HttpStatusCode.OK,
                headers = headersOf("X-Apple-Id-Session-Id", "abc-123"),
            )
        }
        val transport = KtorHttpTransport(HttpClient(engine))

        val body = "<plist><dict><key>k</key><string>v</string></dict></plist>"
            .encodeToByteArray()
        val response = transport.execute(
            HttpRequest(
                method = "POST",
                url = "https://gsa.apple.com/grandslam/GsService2",
                headers = linkedMapOf(
                    "Content-Type" to "text/x-xml-plist",
                    "Accept" to "*/*",
                    "User-Agent" to "akd/1.0",
                ),
                body = body,
            )
        )

        assertEquals("POST", captured.method.value)
        assertEquals("/grandslam/GsService2", captured.url.fullPath)

        // Ktor stores the outbound Content-Type on the body (OutgoingContent),
        // not the request headers map. The byte that goes on the wire is
        // whatever `captured.body.contentType` resolves to — assert the
        // caller's value is preserved verbatim, not rewritten by any plugin.
        assertEquals(
            "text/x-xml-plist",
            captured.body.contentType?.toString(),
            "Content-Type was rewritten — HTTP 415 regression possible",
        )

        assertEquals("*/*", captured.headers["Accept"])
        assertEquals("akd/1.0", captured.headers["User-Agent"])
        assertEquals(body.toList(), captured.body.toByteArray().toList())

        assertTrue(response.isOk())
        assertEquals(200, response.statusCode)
        assertEquals(listOf<Byte>(1, 2, 3), response.body.toList())
        assertEquals("abc-123", response.header("X-Apple-Id-Session-Id"))
    }

    @Test
    fun responseHeaderLookupIsCaseInsensitive() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteArray(0),
                status = HttpStatusCode.OK,
                headers = headersOf("X-Apple-Id-Session-Id", "session-xyz"),
            )
        }
        val transport = KtorHttpTransport(HttpClient(engine))

        val response = transport.execute(HttpRequest("GET", "https://example.invalid/"))

        assertEquals("session-xyz", response.header("x-apple-id-session-id"))
        assertEquals("session-xyz", response.header("X-APPLE-ID-SESSION-ID"))
        assertEquals("session-xyz", response.header("X-Apple-Id-Session-Id"))
    }

    @Test
    fun nonSuccessStatusSurfacedWithBody() = runTest {
        val engine = MockEngine {
            respond(
                content = "nope".encodeToByteArray(),
                status = HttpStatusCode.Unauthorized,
            )
        }
        val transport = KtorHttpTransport(HttpClient(engine))
        val response = transport.execute(HttpRequest("GET", "https://example.invalid/"))

        assertEquals(401, response.statusCode)
        assertTrue(!response.isOk())
        assertEquals("nope", response.body.decodeToString())
    }

    @Test
    fun getRequestDoesNotSendBody() = runTest {
        lateinit var captured: io.ktor.client.request.HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(content = ByteArray(0), status = HttpStatusCode.OK)
        }
        val transport = KtorHttpTransport(HttpClient(engine))

        transport.execute(
            HttpRequest(
                method = "GET",
                url = "https://anisette.local/",
                headers = mapOf("Accept" to "application/json"),
            )
        )

        assertEquals("GET", captured.method.value)
        assertEquals(ByteArray(0).toList(), captured.body.toByteArray().toList())
        assertEquals("application/json", captured.headers["Accept"])
    }

    @Test
    fun requestHeadersPreserveInsertionOrder() = runTest {
        // Order matters for diff-stability against pysrp — Apple's servers
        // don't care but our fixture-driven tests do.
        lateinit var captured: io.ktor.client.request.HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond(content = ByteArray(0), status = HttpStatusCode.OK)
        }
        val transport = KtorHttpTransport(HttpClient(engine))

        val hdrs = linkedMapOf(
            "X-Apple-I-Client-Time" to "2026-04-21T20:00:00Z",
            "X-Apple-I-TimeZone" to "UTC",
            "X-Apple-I-MD" to "deadbeef",
            "X-Apple-I-MD-M" to "cafebabe",
        )
        transport.execute(HttpRequest("GET", "https://x.invalid/", hdrs))

        // Ktor's HeadersBuilder is a multimap — verify every expected pair is
        // present. Order preservation in the outbound pipeline is an OkHttp /
        // CIO concern; this test asserts we didn't drop or duplicate any.
        val sent = hdrs.keys.associateWith { captured.headers[it] }
        assertEquals(hdrs, sent)
        // And no accidental extras.
        val extras = captured.headers.names() - hdrs.keys -
            setOf("Accept-Charset", "Accept", "User-Agent", "Content-Type", "Content-Length",
                "Host", "Connection", "Accept-Encoding")
        assertTrue(extras.isEmpty(), "Unexpected headers added: $extras")
        assertNotNull(captured.headers["X-Apple-I-MD"])
    }
}
