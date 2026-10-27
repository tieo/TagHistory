package io.github.tieo.taghistory.apple.gsa

import io.github.tieo.taghistory.anisette.FakeAnisetteProvider
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpResponse
import io.github.tieo.taghistory.apple.http.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A refusal reaches the sign-in screen as its message, so it must read as one line. */
class GsaClientErrorTest {

    private class Refusing(private val status: Int, private val body: String) : HttpTransport {
        override suspend fun execute(request: HttpRequest) = HttpResponse(status, emptyMap(), body.encodeToByteArray())
    }

    private suspend fun messageFor(status: Int, body: String): String? {
        val gsa = GsaClient(Refusing(status, body), AnisetteClient(FakeAnisetteProvider()))
        return assertFailsWith<GsaRequestException> { gsa.request(mapOf("o" to "init")) }.message
    }

    @Test
    fun an_html_error_page_is_reduced_to_its_title() = runTest {
        val page = "<html>\n<head><title>429 Too Many Requests</title></head>\n<body>\n" +
            "<center><h1>429 Too Many Requests</h1></center>\n<hr><center>Apple</center>\n</body>\n</html>\n"

        assertEquals("GSA request failed: HTTP 429 Too Many Requests", messageFor(429, page))
    }

    @Test
    fun a_plain_body_is_kept_on_one_line() = runTest {
        assertEquals("GSA request failed: HTTP 503: try again later", messageFor(503, "try again\nlater"))
    }

    @Test
    fun an_empty_body_gives_the_status_alone() = runTest {
        assertEquals("GSA request failed: HTTP 500", messageFor(500, ""))
    }
}
