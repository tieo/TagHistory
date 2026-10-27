package io.github.tieo.taghistory.apple.gsa

import io.github.tieo.taghistory.anisette.FakeAnisetteProvider
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpResponse
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.anyToPlist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Apple's `hsc 434` asks the machine to resync; the request then goes out again. */
class GsaClientResyncTest {

    private class Apple(private val replies: MutableList<Map<String, Any?>>) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(200, emptyMap(), XmlPlist.encode(anyToPlist(mapOf("Response" to replies.removeAt(0)))))
        }
    }

    private val accepted = mapOf("Status" to mapOf("hsc" to 200L, "ec" to 0L), "sp" to "s2k")

    @Test
    fun a_resync_request_is_answered_and_the_request_resent() = runTest {
        val sim = byteArrayOf(1, 2, 3)
        val apple = Apple(
            mutableListOf(
                mapOf("Status" to mapOf("hsc" to 434L, "ec" to -22421L, "X-Apple-I-MD-DATA" to sim)),
                accepted,
            ),
        )
        val provider = FakeAnisetteProvider()
        val gsa = GsaClient(apple, AnisetteClient(provider))

        val response = gsa.request(mapOf("o" to "init"))

        assertEquals(1, provider.resyncs.size)
        assertContentEquals(sim, provider.resyncs.single())
        assertEquals(2, apple.requests.size)
        assertEquals("s2k", response.string("sp"))
        assertEquals(2, provider.headerCalls, "the resent request must carry fresh anisette")
    }

    @Test
    fun an_accepted_request_does_not_resync() = runTest {
        val apple = Apple(mutableListOf(accepted))
        val provider = FakeAnisetteProvider()

        GsaClient(apple, AnisetteClient(provider)).request(mapOf("o" to "init"))

        assertTrue(provider.resyncs.isEmpty())
        assertEquals(1, apple.requests.size)
        val clientInfo = apple.requests.single().headers["X-MMe-Client-Info"]
        assertEquals(FakeAnisetteProvider.MACHINE.clientInfo, clientInfo)
    }
}

private fun PlistValue.Dict.string(key: String) = (this[key] as? PlistValue.Str)?.value
