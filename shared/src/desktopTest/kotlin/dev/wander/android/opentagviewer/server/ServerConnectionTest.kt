package io.github.tieo.taghistory.server

import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.apple.crypto.sha256
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Properties
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The app signs in to a sync server behind Authelia the way Authelia
 * demands of a bearer-token client (PAR, PKCE S256, form_post through the
 * server's callback), keeps the session across process restarts, and
 * renews a refused or rotated token without the user noticing.
 */
class ServerConnectionTest {

    /** Authelia in front of the TagHistory server, as far as the app sees them. */
    private class FakeAuthelia {
        var challenge: String? = null
        var state: String? = null
        var issued = 0
        val validAccess = mutableSetOf<String>()
        val validRefresh = mutableSetOf<String>()
        val serverCalls = mutableListOf<String?>()

        @OptIn(ExperimentalEncodingApi::class)
        suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
            val url = request.url
            return when {
                url.host == SERVER_HOST -> {
                    val bearer = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                    serverCalls += bearer
                    if (bearer in validAccess) {
                        json(STATUS_JSON)
                    } else {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://$AUTH_HOST/?rd=$url"))
                    }
                }
                url.encodedPath == "/.well-known/openid-configuration" -> json(
                    """{"issuer":"https://$AUTH_HOST","authorization_endpoint":"https://$AUTH_HOST/api/oidc/authorization",
                    "token_endpoint":"https://$AUTH_HOST/api/oidc/token",
                    "pushed_authorization_request_endpoint":"https://$AUTH_HOST/api/oidc/pushed-authorization-request"}""",
                )
                url.encodedPath == "/api/oidc/pushed-authorization-request" -> {
                    val form = form(request)
                    assertEquals(OidcClient.CLIENT_ID, form["client_id"])
                    assertEquals("form_post", form["response_mode"])
                    assertEquals("S256", form["code_challenge_method"])
                    assertEquals("https://$SERVER_HOST/oauth2/callback", form["redirect_uri"])
                    assertEquals(setOf("offline_access", "authelia.bearer.authz"), form["scope"]!!.split(" ").toSet())
                    challenge = form["code_challenge"]
                    state = form["state"]
                    json("""{"request_uri":"urn:ietf:params:oauth:request_uri:abc","expires_in":90}""")
                }
                url.encodedPath == "/api/oidc/token" -> {
                    val form = form(request)
                    when (form["grant_type"]) {
                        "authorization_code" -> {
                            val verifier = form["code_verifier"]!!
                            val expected = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                                .encode(sha256(verifier.encodeToByteArray()))
                            if (form["code"] != CODE || expected != challenge) return invalidGrant()
                            tokens()
                        }
                        "refresh_token" -> {
                            // Rotation: a refresh token works exactly once.
                            if (!validRefresh.remove(form["refresh_token"])) return invalidGrant()
                            tokens()
                        }
                        else -> error("unexpected grant")
                    }
                }
                else -> error("unexpected request $url")
            }
        }

        private fun MockRequestHandleScope.tokens(): HttpResponseData {
            issued++
            validAccess += "at-$issued"
            validRefresh += "rt-$issued"
            return json("""{"access_token":"at-$issued","refresh_token":"rt-$issued","expires_in":3600,"token_type":"bearer"}""")
        }

        private fun MockRequestHandleScope.invalidGrant() = respond(
            """{"error":"invalid_grant","error_description":"The refresh token is no longer valid"}""",
            HttpStatusCode.BadRequest,
            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

        private fun MockRequestHandleScope.json(body: String) =
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

        private fun form(request: HttpRequestData): Parameters {
            assertEquals(HttpMethod.Post, request.method)
            val body = request.body as OutgoingContent.ByteArrayContent
            // Form bodies encode a space as '+', which a URL query parser keeps.
            return Parameters.build {
                for (pair in body.bytes().decodeToString().split('&')) {
                    val (key, value) = pair.split('=', limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) }
                    append(key, value)
                }
            }
        }
    }

    private val dir: File = Files.createTempDirectory("server-connection").toFile()
    private val blobs = SecureBlobStore(File(dir, "key").apply { writeBytes(ByteArray(32) { 7 }) })
    private val properties = Properties()
    private val settings = PropertiesSettings(properties)
    private val authelia = FakeAuthelia()
    private val engine = HttpClient(MockEngine { request -> with(authelia) { handle(request) } })
    private var now = 1_000_000L

    private fun connection() = ServerConnection(settings, blobs, OidcClient(engine, { now }), engine, { now })

    private fun redirect(state: String?, code: String = CODE) =
        "${ServerApi.APP_REDIRECT}?" + Parameters.build {
            append("code", code)
            state?.let { append("state", it) }
            append("iss", "https://$AUTH_HOST")
        }.formUrlEncode()

    private suspend fun signIn(c: ServerConnection) {
        val browserUrl = c.connect(SERVER_HOST)!!
        assertTrue(browserUrl.startsWith("https://$AUTH_HOST/api/oidc/authorization?"), browserUrl)
        assertTrue("request_uri=" in browserUrl)
        c.complete(redirect(authelia.state))
    }

    @Test
    fun sign_in_then_server_calls_carry_the_access_token() = runBlocking<Unit> {
        val c = connection()
        signIn(c)

        assertEquals(ServerConnection.State.Connected("https://$SERVER_HOST"), c.state.value)
        c.client()!!.status()
        assertEquals("at-1", authelia.serverCalls.last())
        // The refresh token is stored encrypted, never in clear.
        assertFalse(properties.values.any { "rt-1" in it.toString() })
    }

    @Test
    fun a_refused_token_is_renewed_and_the_rotated_refresh_token_survives_a_restart() = runBlocking<Unit> {
        signIn(connection())
        authelia.validAccess.clear()

        // A new process: no access token in memory, only the stored refresh token.
        val restarted = connection()
        restarted.client()!!.status()
        assertEquals("at-2", authelia.serverCalls.last())

        // The proxy drops at-2: the client renews with rt-2 (rt-1 is spent) and retries.
        authelia.validAccess.clear()
        restarted.client()!!.status()
        assertEquals<List<String?>>(listOf("at-2", "at-3"), authelia.serverCalls.takeLast(2))
        assertEquals(ServerConnection.State.Connected("https://$SERVER_HOST"), restarted.state.value)
    }

    @Test
    fun an_expired_access_token_is_renewed_before_it_is_sent() = runBlocking<Unit> {
        val c = connection()
        signIn(c)
        authelia.serverCalls.clear()
        now += 3_600_000L

        c.client()!!.status()

        assertEquals<List<String?>>(listOf("at-2"), authelia.serverCalls, "the expired token must not be tried first")
    }

    @Test
    fun a_revoked_sign_in_asks_for_a_new_one() = runBlocking<Unit> {
        val c = connection()
        signIn(c)
        authelia.validAccess.clear()
        authelia.validRefresh.clear()

        assertFailsWith<SignInExpiredException> { c.client()!!.status() }

        assertEquals(ServerConnection.State.SignInExpired("https://$SERVER_HOST"), c.state.value)
        assertEquals(null, c.client())
        // Still expired after a restart, so the Settings screen offers the sign-in.
        assertIs<ServerConnection.State.SignInExpired>(connection().state.value)
    }

    @Test
    fun an_answer_to_another_attempt_is_rejected() = runBlocking<Unit> {
        val c = connection()
        c.connect("https://$SERVER_HOST/some/page")

        assertFailsWith<OidcException> { c.complete(redirect(state = "forged")) }

        assertEquals(0, authelia.issued)
        assertEquals(ServerConnection.State.Disconnected, c.state.value)
    }

    @Test
    fun the_sign_in_survives_the_app_dying_while_the_browser_is_open() = runBlocking<Unit> {
        connection().connect(SERVER_HOST)

        val restarted = connection()
        assertEquals(ServerConnection.State.AwaitingBrowser("https://$SERVER_HOST"), restarted.state.value)
        restarted.complete(redirect(authelia.state))

        assertEquals(ServerConnection.State.Connected("https://$SERVER_HOST"), restarted.state.value)
    }

    private companion object {
        const val SERVER_HOST = "tags.example.org"
        const val AUTH_HOST = "auth.example.org"
        const val CODE = "code-1"
        const val STATUS_JSON =
            """{"instanceId":"i","signedIn":true,"beaconCount":0,"reportCount":0,"lastRun":null,"syncIntervalMinutes":30,"serverTimeMs":0}"""
    }
}
