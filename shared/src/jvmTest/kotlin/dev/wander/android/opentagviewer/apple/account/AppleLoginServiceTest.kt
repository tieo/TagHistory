package io.github.tieo.taghistory.apple.account

import io.github.tieo.taghistory.anisette.AnisetteProvider
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpResponse
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.mobileme.MobileMeClient
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.plistDictOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Stub-transport tests for [AppleLoginService].
 *
 * These do NOT exercise the full SRP round-trip — Phase 6
 * (`Srp6aGsaTest` + `GsaCryptoTest`) already pinned the crypto layer
 * byte-for-byte against pysrp fixtures. What these tests pin down is the
 * *glue*: HTTP shape, plist field plumbing, state-machine transitions,
 * and every error path our UI branches on.
 *
 * Full-flow SRP integration is deferred to Phase 17 (live server or
 * FakeGsaServer driven by real crypto).
 */
class AppleLoginServiceTest {

    private fun fixedAnisette() = object : AnisetteProvider {
        override suspend fun version() = "test-version"
        override suspend fun getHeaders() = linkedMapOf(
            "X-Apple-I-MD" to "md-value",
            "X-Apple-I-MD-M" to "mdm-value",
        )
    }

    /**
     * Minimal programmable [HttpTransport] — take a list of `(predicate,
     * response)` handlers, return the first match. Unexpected calls fail
     * the test immediately so a drifting request shape surfaces fast.
     */
    class StubHttp(
        private val handlers: MutableList<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>,
    ) : HttpTransport {
        val received = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            received += request
            val handler = handlers.firstOrNull { it.first(request) }
                ?: fail("No stub handler for ${request.method} ${request.url}")
            return handler.second(request)
        }
    }

    private fun plistResponse(body: PlistValue, status: Int = 200): HttpResponse =
        HttpResponse(status, mapOf("Content-Type" to "text/x-xml-plist"), XmlPlist.encode(body))

    private fun buildService(
        account: AppleAccount = AppleAccount(uid = "u", devid = "d"),
        handlers: MutableList<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>,
    ): Pair<AppleLoginService, StubHttp> {
        val http = StubHttp(handlers)
        val anisette = AnisetteClient(
            provider = fixedAnisette(),
            clockMillis = { 1714000000000 }, // 2024-04-24T22:13:20Z
            timeZoneAbbreviation = { "UTC" },
            locale = { "en_US" },
        )
        val gsa = GsaClient(http, anisette)
        val mobileMe = MobileMeClient(http, anisette)
        return AppleLoginService(account, http, anisette, gsa, mobileMe) to http
    }

    // ---- State-machine enforcement ----

    @Test
    fun loginRejectedWhenAccountNotLoggedOut() = runTest {
        val acct = AppleAccount(uid = "u", devid = "d")
        // Seed an unrelated but non-LOGGED_OUT state so the guard fires.
        acct.setLoginState(LoginState.AUTHENTICATED, emptyMap())
        val (svc, _) = buildService(acct, mutableListOf())
        val ex = assertFailsWith<AppleLoginException> { svc.login("x@y", "p") }
        assertEquals(AppleLoginException.Kind.INVALID_STATE, ex.kind)
    }

    @Test
    fun submitSmsRejectedWhenAccountNotIn2faState() = runTest {
        val (svc, _) = buildService(AppleAccount(uid = "u", devid = "d"), mutableListOf())
        // Build a valid challenge out-of-band via reflection-friendly factory —
        // we can't call the internal ctor from tests, but the guard lives on
        // the service's overrides so poke it there directly.
        val ex = assertFailsWith<AppleLoginException> { svc.submitSms(1, "000000") }
        assertEquals(AppleLoginException.Kind.INVALID_STATE, ex.kind)
    }

    // ---- GSA init error paths ----

    @Test
    fun badEmailBubblesAsInvalidCredentials() = runTest {
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += gsaInitResponder {
            // Apple returns {ec:-22406, em:"unknown Apple ID"} when the email
            // is wrong. ec!=0 MUST translate to INVALID_CREDENTIALS.
            wrapInPlistResponse(
                plistDictOf(
                    "Status" to plistDictOf(
                        "ec" to PlistValue.Int64(-22406),
                        "em" to PlistValue.Str("unknown Apple ID"),
                    ),
                )
            )
        }
        val (svc, _) = buildService(handlers = handlers)

        val ex = assertFailsWith<AppleLoginException> { svc.login("nope@example.com", "p") }
        assertEquals(AppleLoginException.Kind.INVALID_CREDENTIALS, ex.kind)
        assertTrue("unknown Apple ID" in (ex.message ?: ""))
    }

    @Test
    fun unsupportedSrpProtocolBubblesAsUnhandledProtocol() = runTest {
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += gsaInitResponder {
            wrapInPlistResponse(
                plistDictOf(
                    "Status" to plistDictOf("ec" to PlistValue.Int64(0)),
                    "sp" to PlistValue.Str("s3k_experimental"),
                )
            )
        }
        val (svc, _) = buildService(handlers = handlers)
        val ex = assertFailsWith<AppleLoginException> { svc.login("x@y", "p") }
        assertEquals(AppleLoginException.Kind.UNHANDLED_PROTOCOL, ex.kind)
    }

    @Test
    fun gsaHttp500BubblesAsGsaRequestException() = runTest {
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += { r: HttpRequest -> r.url == GsaClient.ENDPOINT_GSA } to { _: HttpRequest ->
            HttpResponse(500, emptyMap(), "upstream boom".encodeToByteArray())
        }
        val (svc, _) = buildService(handlers = handlers)
        assertFailsWith<io.github.tieo.taghistory.apple.gsa.GsaRequestException> {
            svc.login("x@y", "p")
        }
    }

    // ---- GSA init request SHAPE ----

    @Test
    fun gsaInitRequestCarriesExpectedFieldsAndHeaders() = runTest {
        lateinit var captured: HttpRequest
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += { r: HttpRequest -> r.url == GsaClient.ENDPOINT_GSA } to { r: HttpRequest ->
            captured = r
            // Short-circuit with a "bad email" response so we don't have to
            // carry through to /complete.
            wrapInPlistResponse(
                plistDictOf(
                    "Status" to plistDictOf(
                        "ec" to PlistValue.Int64(-22406),
                        "em" to PlistValue.Str("short-circuit"),
                    ),
                )
            )
        }
        val (svc, _) = buildService(handlers = handlers)
        runCatching { svc.login("user@example.com", "pw") }

        assertEquals("POST", captured.method)
        assertEquals("text/x-xml-plist", captured.headers["Content-Type"])
        assertTrue(captured.headers["User-Agent"]!!.startsWith("akd/1.0"))
        assertTrue(captured.headers.containsKey("X-MMe-Client-Info"))

        val parsed = XmlPlist.parse(captured.body!!) as PlistValue.Dict
        val request = parsed.dict("Request")!!
        val cpd = request.dict("cpd")!!
        assertEquals("md-value", cpd.string("X-Apple-I-MD"), "CPD must carry anisette MD header")
        assertEquals("mdm-value", cpd.string("X-Apple-I-MD-M"))
        assertEquals(true, cpd.bool("bootstrap"))
        assertEquals(true, cpd.bool("icscrec"))
        assertEquals(false, cpd.bool("pbe"))

        val a2k = request.data("A2k")
        assertTrue(a2k != null && a2k.isNotEmpty(), "A2k must be present and non-empty")
        assertEquals("user@example.com", request.string("u"))
        assertEquals("init", request.string("o"))
        val ps = request.array("ps")!!
        assertEquals(2, ps.size, "ps must offer s2k + s2k_fo")
        assertEquals("s2k", (ps[0] as PlistValue.Str).value)
        assertEquals("s2k_fo", (ps[1] as PlistValue.Str).value)
    }

    // ---- 2FA endpoint shape ----

    @Test
    fun submitSmsHitsPhoneSecurityCodeEndpointWithJsonBody() = runTest {
        val acct = AppleAccount(uid = "u", devid = "d")
        acct.username = "x@y"
        acct.password = "p"
        acct.accountInfo = AccountInfo(accountName = "a", trustedDevice2fa = false)
        acct.setLoginState(
            LoginState.REQUIRE_2FA,
            mapOf(
                "adsid" to kotlinx.serialization.json.JsonPrimitive("ADSID-xyz"),
                "idms_token" to kotlinx.serialization.json.JsonPrimitive("IDMS-abc"),
            ),
        )

        lateinit var smsRequest: HttpRequest
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += { r: HttpRequest ->
            r.url == "https://gsa.apple.com/auth/verify/phone/securitycode"
        } to { r: HttpRequest ->
            smsRequest = r
            // Short-circuit: the service will then try to gsaAuthenticate.
            // Force it to fail there with a distinctive credential error so
            // we can stop the cascade at the endpoint we care about.
            HttpResponse(200, emptyMap(), ByteArray(0))
        }
        handlers += gsaInitResponder {
            wrapInPlistResponse(
                plistDictOf(
                    "Status" to plistDictOf(
                        "ec" to PlistValue.Int64(-22406),
                        "em" to PlistValue.Str("short-circuit post-2fa"),
                    ),
                )
            )
        }
        val (svc, _) = buildService(acct, handlers)

        // submit throws because gsaAuthenticate returns INVALID_CREDENTIALS
        // after the 2FA submit — that's fine, we only care about the SMS
        // request shape.
        runCatching { svc.submitSms(42, "123456") }

        assertEquals("POST", smsRequest.method)
        assertEquals("application/json", smsRequest.headers["Content-Type"])

        val identityToken = smsRequest.headers["X-Apple-Identity-Token"]!!
        val decoded = kotlin.io.encoding.Base64.decode(identityToken).decodeToString()
        assertEquals("ADSID-xyz:IDMS-abc", decoded, "identity token is b64(adsid:idms_token)")

        // Body is JSON — verify it carries phoneNumber.id, securityCode.code, mode
        val jsonBody = kotlinx.serialization.json.Json.parseToJsonElement(
            smsRequest.body!!.decodeToString()
        ) as kotlinx.serialization.json.JsonObject
        val phoneNumberId = (jsonBody["phoneNumber"] as kotlinx.serialization.json.JsonObject)["id"]
        assertEquals("42", phoneNumberId.toString())
        val code = (jsonBody["securityCode"] as kotlinx.serialization.json.JsonObject)["code"]
        assertEquals("\"123456\"", code.toString())
        assertEquals("\"sms\"", jsonBody["mode"].toString())
    }

    @Test
    fun requestTrustedDeviceUsesXmlPlistContentTypeAndNoBody() = runTest {
        val acct = AppleAccount(uid = "u", devid = "d")
        acct.setLoginState(
            LoginState.REQUIRE_2FA,
            mapOf(
                "adsid" to kotlinx.serialization.json.JsonPrimitive("a"),
                "idms_token" to kotlinx.serialization.json.JsonPrimitive("t"),
            ),
        )

        lateinit var tdRequest: HttpRequest
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += { r: HttpRequest ->
            r.url == "https://gsa.apple.com/auth/verify/trusteddevice"
        } to { r: HttpRequest ->
            tdRequest = r
            HttpResponse(200, emptyMap(), ByteArray(0))
        }
        val (svc, _) = buildService(acct, handlers)
        svc.requestTrustedDevice()

        assertEquals("GET", tdRequest.method)
        assertEquals("text/x-xml-plist", tdRequest.headers["Content-Type"])
        assertEquals("text/x-xml-plist", tdRequest.headers["Accept"])
        // GET requests in the Kotlin transport model may have a non-null
        // empty body — assert it's effectively empty, not that it's null.
        val body = tdRequest.body
        assertTrue(body == null || body.isEmpty(), "GET should not carry a body")
    }

    @Test
    fun twoFactorRequestFailureSurfacesAsUnhandledProtocol() = runTest {
        val acct = AppleAccount(uid = "u", devid = "d")
        acct.setLoginState(
            LoginState.REQUIRE_2FA,
            mapOf(
                "adsid" to kotlinx.serialization.json.JsonPrimitive("a"),
                "idms_token" to kotlinx.serialization.json.JsonPrimitive("t"),
            ),
        )
        val handlers = mutableListOf<Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse>>()
        handlers += { _: HttpRequest -> true } to { _: HttpRequest ->
            HttpResponse(500, emptyMap(), ByteArray(0))
        }
        val (svc, _) = buildService(acct, handlers)
        val ex = assertFailsWith<AppleLoginException> { svc.requestSms(1) }
        assertEquals(AppleLoginException.Kind.UNHANDLED_PROTOCOL, ex.kind)
    }

    // ---- LoginResult sealed type ----

    @Test
    fun loginResultRequireTwoFactorIsPatternMatchable() {
        // No I/O — just pin the sealed hierarchy shape so the exhaustive
        // `when` in calling UI stays valid across refactors.
        val trusted = TwoFactorChallenge.TrustedDevice(
            coordinator = object : TwoFactorCoordinator {
                override suspend fun requestSms(phoneNumberId: Int) {}
                override suspend fun submitSms(phoneNumberId: Int, code: String) =
                    LoginResult.LoggedIn
                override suspend fun requestTrustedDevice() {}
                override suspend fun submitTrustedDevice(code: String) = LoginResult.LoggedIn
            },
        )
        val result: LoginResult = LoginResult.RequireTwoFactor(listOf(trusted))
        val text = when (result) {
            LoginResult.LoggedIn -> "in"
            is LoginResult.RequireTwoFactor -> "methods=${result.methods.size}"
        }
        assertEquals("methods=1", text)
        assertEquals("Trusted device", trusted.displayName)
    }

    // ---- Helpers ----

    private fun gsaInitResponder(
        respond: () -> HttpResponse,
    ): Pair<(HttpRequest) -> Boolean, (HttpRequest) -> HttpResponse> =
        { r: HttpRequest -> r.url == GsaClient.ENDPOINT_GSA } to { _: HttpRequest -> respond() }

    private fun wrapInPlistResponse(innerDict: PlistValue.Dict): HttpResponse =
        plistResponse(plistDictOf("Response" to innerDict))
}
