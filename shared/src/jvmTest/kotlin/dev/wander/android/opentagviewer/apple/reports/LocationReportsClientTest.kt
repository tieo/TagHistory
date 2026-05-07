package io.github.tieo.taghistory.apple.reports

import io.github.tieo.taghistory.anisette.AnisetteProvider
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.LoginState
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpResponse
import io.github.tieo.taghistory.apple.http.HttpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LocationReportsClientTest {

    private lateinit var transport: FakeTransport
    private lateinit var anisette: AnisetteClient
    private lateinit var account: AppleAccount

    @BeforeTest
    fun setUp() {
        transport = FakeTransport()
        anisette = AnisetteClient(
            provider = object : AnisetteProvider {
                override suspend fun version() = "test-version"
                override suspend fun getHeaders() = linkedMapOf(
                    "X-Apple-I-MD" to "OTP",
                    "X-Apple-I-MD-M" to "MACHINE",
                )
            },
            clockMillis = { 1714000000000 },
            timeZoneAbbreviation = { "UTC" },
            locale = { "en_US" },
        )
        account = AppleAccount(uid = "uid-1", devid = "dev-1")
        account.username = "me@example.com"

        val state = mapOf(
            "dsid" to JsonPrimitive("123456"),
            "mobileme_data" to buildJsonObject {
                put("tokens", buildJsonObject {
                    put("searchPartyToken", JsonPrimitive("SPT-TOKEN"))
                })
            },
        )
        account.setLoginState(LoginState.LOGGED_IN, state)
    }

    @Test
    fun postsExpectedJsonAndAuth() = runTest {
        transport.enqueueFetchOk("""{"statusCode":"200","results":[]}""")

        val client = LocationReportsClient(transport, anisette)
        val raw = client.fetchRaw(
            account,
            1_000L,
            2_000L,
            listOf("hashA==", "hashB=="),
        )
        assertNotNull(raw)

        val req = transport.lastRequestTo(LocationReportsClient.ENDPOINT_REPORTS_FETCH)
        assertEquals("POST", req.method)

        val expectedBasic = "Basic " +
            Base64.encode("123456:SPT-TOKEN".encodeToByteArray())
        assertEquals(expectedBasic, req.headers["Authorization"])
        assertEquals("application/json", req.headers["Content-Type"])

        // Anisette headers must ride along (no X-Mme-Client-Info — this is a
        // non-GSA endpoint, see withClientInfo=false in the client).
        assertTrue(req.headers.containsKey("X-Apple-I-MD"))
        assertTrue(req.headers.containsKey("X-Apple-I-MD-M"))

        val body = Json.parseToJsonElement(req.body!!.decodeToString()).jsonObject
        val search = body["search"]!!.jsonArray
        assertEquals(1, search.size)
        val entry = search[0].jsonObject
        assertEquals(1000L, entry["startDate"]!!.jsonPrimitive.longOrNull)
        assertEquals(2000L, entry["endDate"]!!.jsonPrimitive.longOrNull)
        val ids = entry["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("hashA==", "hashB=="), ids)
    }

    @Test
    fun rejectsNotLoggedIn() = runTest {
        account.setLoginState(LoginState.LOGGED_OUT, null)
        val client = LocationReportsClient(transport, anisette)
        val ex = assertFailsWith<AppleLoginException> {
            client.fetchRaw(account, 0, 0, emptyList())
        }
        assertEquals(AppleLoginException.Kind.INVALID_STATE, ex.kind)
    }

    @Test
    fun wraps401AsUnauthorized() = runTest {
        transport.enqueueFetchStatus(401, "{}")
        val client = LocationReportsClient(transport, anisette)
        val ex = assertFailsWith<AppleLoginException> {
            client.fetchRaw(account, 0, 0, listOf("x"))
        }
        assertEquals(AppleLoginException.Kind.UNAUTHORIZED, ex.kind)
    }

    @Test
    fun parseReports_unpacksListEntries() {
        val payloadB64 = Base64.encode(ByteArray(89))
        val idB64 = Base64.encode(ByteArray(32))
        val json = """{"statusCode":"200","results":[""" +
            """{"payload":"$payloadB64","id":"$idB64",""" +
            """"datePublished":1710000000000,"description":"reg"}""" +
            """]}"""
        val raw = Json.parseToJsonElement(json) as JsonObject
        val parsed = LocationReportsClient.parseReports(raw)
        assertEquals(1, parsed.size)
        assertEquals(1710000000000L, parsed[0].publishedAt.toEpochMilliseconds())
        assertEquals("reg", parsed[0].description)
    }

    /** Minimal replay-style transport — mirrors the Java test's FakeTransport. */
    private class FakeTransport : HttpTransport {
        private val queue = ArrayDeque<HttpResponse>()
        private val recorded = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            recorded += request
            if (queue.isEmpty()) {
                throw AssertionError("Unexpected HTTP call to ${request.url}")
            }
            return queue.removeFirst()
        }

        fun enqueueFetchOk(jsonBody: String) {
            queue.addLast(
                HttpResponse(
                    200,
                    mapOf("Content-Type" to "application/json"),
                    jsonBody.encodeToByteArray(),
                )
            )
        }

        fun enqueueFetchStatus(status: Int, body: String) {
            queue.addLast(HttpResponse(status, emptyMap(), body.encodeToByteArray()))
        }

        fun lastRequestTo(url: String): HttpRequest {
            for (i in recorded.indices.reversed()) {
                if (recorded[i].url == url) return recorded[i]
            }
            throw AssertionError("No request recorded to $url")
        }
    }
}
