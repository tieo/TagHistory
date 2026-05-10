package io.github.tieo.taghistory.apple.account

import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.crypto.GsaCrypto
import io.github.tieo.taghistory.apple.crypto.Srp6aGsa
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.mobileme.MobileMeClient
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Kotlin port of the Java `AppleLoginService` (which itself ported
 * `AsyncAppleAccount.login` + `_gsa_authenticate` + `_login_mobileme`
 * from `findmy/reports/account.py`).
 *
 * Drives the owning [AppleAccount] through the four-state machine:
 * `LOGGED_OUT -> REQUIRE_2FA -> AUTHENTICATED -> LOGGED_IN`.
 *
 * Public surface uses sealed [LoginResult] / [TwoFactorChallenge] types
 * so callers can pattern-match exhaustively instead of round-tripping
 * through an enum after each call. Exceptions all normalize to
 * [AppleLoginException].
 *
 * Every network call is routed through the injected [HttpTransport],
 * so the full flow can be exercised against recorded fixtures — see
 * `AppleLoginServiceTest`.
 */
@OptIn(ExperimentalEncodingApi::class)
class AppleLoginService(
    val account: AppleAccount,
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val gsa: GsaClient,
    private val mobileMe: MobileMeClient,
) : TwoFactorCoordinator {

    /**
     * Exactly matches Python `AppleAccount.login`.
     *
     * Password-only accounts land on [LoginResult.LoggedIn] directly.
     * 2FA-protected accounts return [LoginResult.RequireTwoFactor] with
     * the eagerly-fetched list of available methods — the caller picks
     * one and drives [TwoFactorChallenge.request] + [TwoFactorChallenge.submit]
     * to completion.
     */
    suspend fun login(username: String, password: String): LoginResult {
        requireState(LoginState.LOGGED_OUT)
        val afterGsa = gsaAuthenticate(username, password)
        return if (afterGsa == LoginState.REQUIRE_2FA) {
            LoginResult.RequireTwoFactor(methods = fetchTwoFactorMethods())
        } else {
            loginMobileMe()
            LoginResult.LoggedIn
        }
    }

    private suspend fun fetchTwoFactorMethods(): List<TwoFactorChallenge> {
        requireState(LoginState.REQUIRE_2FA)
        val out = mutableListOf<TwoFactorChallenge>()

        // Python offers trusted-device only when GSA told us so. The flag
        // was stashed on the account during _gsa_authenticate.
        if (account.accountInfo?.trustedDevice2fa == true) {
            out += TwoFactorChallenge.TrustedDevice(coordinator = this)
        }

        // Apple exposes available SMS numbers in an HTML page at
        // /auth — embedded as JSON inside a <script class="boot_args">.
        val html = twoFactorRequest("GET", ENDPOINT_2FA_METHODS, data = null, extraHeaders = null)
        for (number in extractPhoneNumbers(html)) {
            val id = (number["id"] as? JsonPrimitive)?.intOrNull ?: -1
            val label = (number["numberWithDialCode"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content ?: "-"
            out += TwoFactorChallenge.Sms(id, label, coordinator = this)
        }
        return out
    }

    override suspend fun requestSms(phoneNumberId: Int) {
        requireState(LoginState.REQUIRE_2FA)
        val data = mutableMapOf<String, Any?>(
            "phoneNumber" to mapOf("id" to phoneNumberId),
            "mode" to "sms",
        )
        twoFactorRequest("PUT", ENDPOINT_2FA_SMS_REQUEST, data, null)
    }

    override suspend fun submitSms(phoneNumberId: Int, code: String): LoginResult {
        requireState(LoginState.REQUIRE_2FA)
        val data = mutableMapOf<String, Any?>(
            "phoneNumber" to mapOf("id" to phoneNumberId),
            "securityCode" to mapOf("code" to code),
            "mode" to "sms",
        )
        twoFactorRequest("POST", ENDPOINT_2FA_SMS_SUBMIT, data, null)

        completeTwoFactor()
        return LoginResult.LoggedIn
    }

    override suspend fun requestTrustedDevice() {
        requireState(LoginState.REQUIRE_2FA)
        val headers = linkedMapOf(
            "Content-Type" to "text/x-xml-plist",
            "Accept" to "text/x-xml-plist",
        )
        twoFactorRequest("GET", ENDPOINT_2FA_TD_REQUEST, data = null, extraHeaders = headers)
    }

    override suspend fun submitTrustedDevice(code: String): LoginResult {
        requireState(LoginState.REQUIRE_2FA)
        val headers = linkedMapOf(
            "security-code" to code,
            "Content-Type" to "text/x-xml-plist",
            "Accept" to "text/x-xml-plist",
        )
        twoFactorRequest("GET", ENDPOINT_2FA_TD_SUBMIT, data = null, extraHeaders = headers)

        completeTwoFactor()
        return LoginResult.LoggedIn
    }

    /** Re-run GSA with the existing account credentials + hit MobileMe. */
    private suspend fun completeTwoFactor() {
        val after = gsaAuthenticate(null, null)
        if (after != LoginState.AUTHENTICATED) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Unexpected state after submitting 2FA: $after",
            )
        }
        loginMobileMe()
    }

    private suspend fun gsaAuthenticate(username: String?, password: String?): LoginState {
        username?.let { account.username = it }
        password?.let { account.password = it }
        val u = account.username ?: throw AppleLoginException(
            AppleLoginException.Kind.INVALID_STATE, "No username or password specified"
        )
        val p = account.password ?: throw AppleLoginException(
            AppleLoginException.Kind.INVALID_STATE, "No username or password specified"
        )

        val srp = Srp6aGsa(u)
        val a2k = srp.startAuthentication()

        val init = mutableMapOf<String, Any?>(
            "A2k" to a2k,
            "u" to u,
            "ps" to listOf("s2k", "s2k_fo"),
            "o" to "init",
        )
        val initResp = gsa.request(account.uid, account.devid, init)

        val initStatus = requireStatus(initResp)
        if (initStatus.int64("ec") != 0L) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_CREDENTIALS,
                "Email verification failed: ${initStatus.string("em")}",
            )
        }
        val sp = initResp.string("sp")
        if (sp != GsaCrypto.PROTOCOL_S2K && sp != GsaCrypto.PROTOCOL_S2K_FO) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Unsupported SRP protocol: $sp",
            )
        }

        val salt = initResp.data("s")
            ?: throw protocolError("GSA init response missing 's' (salt)")
        val serverB = initResp.data("B")
            ?: throw protocolError("GSA init response missing 'B' (server ephemeral)")
        val iterations = initResp.int64("i")?.toInt()
            ?: throw protocolError("GSA init response missing 'i' (iterations)")
        // Apple echoes this cookie back verbatim on /complete. Its plist
        // type is opaque — observed as NSString currently, historically
        // NSData — so we read through opaque() and send the bytes back.
        val cookie = initResp["c"]
            ?: throw protocolError("GSA init response missing 'c' (cookie)")

        val pbkdfPw = GsaCrypto.encryptPassword(p, salt, iterations, sp)
        val m1 = srp.processChallenge(salt, serverB, pbkdfPw)

        val complete = mutableMapOf<String, Any?>(
            "c" to cookie,
            "M1" to m1,
            "u" to u,
            "o" to "complete",
        )
        val completeResp = gsa.request(account.uid, account.devid, complete)

        val completeStatus = requireStatus(completeResp)
        if (completeStatus.int64("ec") != 0L) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_CREDENTIALS,
                "Password authentication failed: ${completeStatus.string("em")}",
            )
        }

        val m2 = completeResp.data("M2")
            ?: throw protocolError("GSA complete response missing 'M2'")
        if (!srp.verifySession(m2)) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Failed to verify SRP session (M2 mismatch)",
            )
        }

        val spdCiphertext = completeResp.data("spd")
            ?: throw protocolError("GSA complete response missing 'spd'")
        val sessionKey = srp.getSessionKey()
            ?: throw protocolError("SRP session key unavailable")
        val spdPlain = GsaCrypto.decryptSpdAesCbc(sessionKey, spdCiphertext)
        val spd = XmlPlist.parse(spdPlain) as? PlistValue.Dict
            ?: throw protocolError("SPD payload is not a <dict>")

        val info = AccountInfo(
            accountName = spd.string("acname"),
            firstName = spd.string("fn"),
            lastName = spd.string("ln"),
            trustedDevice2fa = false,
        )
        account.accountInfo = info

        val au = completeStatus.string("au")
        return when (au) {
            "secondaryAuth", "trustedDeviceSecondaryAuth" -> {
                info.trustedDevice2fa = au == "trustedDeviceSecondaryAuth"
                account.setLoginState(
                    LoginState.REQUIRE_2FA,
                    mapOf(
                        "adsid" to JsonPrimitive(spd.string("adsid").orEmpty()),
                        "idms_token" to JsonPrimitive(spd.string("GsIdmsToken").orEmpty()),
                    ),
                )
                LoginState.REQUIRE_2FA
            }
            null -> {
                val idmsPet = spd.dict("t")
                    ?.dict("com.apple.gs.idms.pet")
                    ?.string("token")
                    .orEmpty()
                account.setLoginState(
                    LoginState.AUTHENTICATED,
                    mapOf(
                        "idms_pet" to JsonPrimitive(idmsPet),
                        "adsid" to JsonPrimitive(spd.string("adsid").orEmpty()),
                    ),
                )
                LoginState.AUTHENTICATED
            }
            else -> throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Unknown auth value: $au",
            )
        }
    }

    private suspend fun loginMobileMe(): LoginState {
        requireState(LoginState.AUTHENTICATED)
        val idmsPet = account.loginStateString("idms_pet") ?: ""
        val adsid = account.loginStateString("adsid") ?: ""
        val uname = account.username
            ?: throw AppleLoginException(AppleLoginException.Kind.INVALID_STATE, "No username")

        val response = mobileMe.login(uname, idmsPet, adsid, account.uid, account.devid)

        val delegates = response.dict("delegates")
        val mobileMeData = delegates?.dict("com.apple.mobileme")

        val status = mobileMeData?.int64("status") ?: response.int64("status")
        if (status == null || status != 0L) {
            val msg = mobileMeData?.string("status-message") ?: response.string("status-message")
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "com.apple.mobileme login failed: $status / $msg",
            )
        }

        val dsidStr = when (val d = response["dsid"]) {
            is PlistValue.Str -> d.value
            is PlistValue.Int64 -> d.value.toString()
            else -> ""
        }
        val serviceData = mobileMeData?.get("service-data")?.let(::plistToJson)
            ?: JsonObject(emptyMap())
        account.setLoginState(
            LoginState.LOGGED_IN,
            mapOf(
                "dsid" to JsonPrimitive(dsidStr),
                "mobileme_data" to serviceData,
            ),
        )
        return LoginState.LOGGED_IN
    }

    private suspend fun twoFactorRequest(
        method: String,
        url: String,
        data: Map<String, Any?>?,
        extraHeaders: Map<String, String>?,
    ): String {
        val adsid = account.loginStateString("adsid") ?: ""
        val idmsToken = account.loginStateString("idms_token") ?: ""
        val identityToken = Base64.encode("$adsid:$idmsToken".encodeToByteArray())

        val headers = linkedMapOf<String, String>(
            "User-Agent" to "Xcode",
            "Accept-Language" to "en-us",
            "X-Apple-Identity-Token" to identityToken,
        )
        extraHeaders?.let { headers.putAll(it) }
        headers.putAll(
            anisette.getHeaders(account.uid, account.devid, "0", withClientInfo = true)
        )

        val body = data?.let {
            // Apple's 2FA endpoints return 415 if we send JSON without a
            // media type. Caller-supplied Content-Type (e.g. text/x-xml-plist
            // for trusted-device) wins — so only fill in the JSON default
            // when the caller hasn't set one.
            if ("Content-Type" !in headers) {
                headers["Content-Type"] = "application/json"
            }
            JSON.encodeToString(
                JsonObject.serializer(),
                anyToJson(it) as JsonObject,
            ).encodeToByteArray()
        }

        val resp = http.execute(
            HttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
            )
        )
        if (!resp.isOk()) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "2FA request failed: HTTP ${resp.statusCode}",
            )
        }
        return resp.body.decodeToString()
    }

    private fun extractPhoneNumbers(html: String): List<JsonObject> {
        // Python uses BeautifulSoup to extract <script class="boot_args">.
        // We don't need a full HTML parser — the element is known, the
        // body is a single JSON literal, and it's all ASCII.
        val pattern = Regex(
            """<script[^>]*class="boot_args"[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(html) ?: return emptyList()
        val json = match.groupValues[1].trim()
        return try {
            val root = JSON.parseToJsonElement(json).jsonObject
            val direct = root["direct"] as? JsonObject ?: return emptyList()
            val pnv = direct["phoneNumberVerification"] as? JsonObject ?: return emptyList()
            val numbers = pnv["trustedPhoneNumbers"]
            if (numbers is kotlinx.serialization.json.JsonArray) {
                numbers.mapNotNull { it as? JsonObject }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun requireState(expected: LoginState) {
        if (account.loginState != expected) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Required state $expected but account is ${account.loginState}",
            )
        }
    }

    private fun requireStatus(resp: PlistValue.Dict): PlistValue.Dict =
        resp.dict("Status")
            ?: throw protocolError("GSA response missing 'Status' dictionary")

    private fun protocolError(message: String): AppleLoginException =
        AppleLoginException(AppleLoginException.Kind.UNHANDLED_PROTOCOL, message)

    companion object {
        // 2FA auth endpoints (mirror account.py constants).
        private const val ENDPOINT_2FA_METHODS = "https://gsa.apple.com/auth"
        private const val ENDPOINT_2FA_SMS_REQUEST = "https://gsa.apple.com/auth/verify/phone"
        private const val ENDPOINT_2FA_SMS_SUBMIT =
            "https://gsa.apple.com/auth/verify/phone/securitycode"
        private const val ENDPOINT_2FA_TD_REQUEST =
            "https://gsa.apple.com/auth/verify/trusteddevice"
        private const val ENDPOINT_2FA_TD_SUBMIT =
            "https://gsa.apple.com/grandslam/GsService2/validate"

        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        /** Coerce a `Map<String, Any?>` tree into a JSON element tree. */
        private fun anyToJson(value: Any?): JsonElement = when (value) {
            null -> JsonPrimitive(null as String?)
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
                (k as String) to anyToJson(v)
            })
            is List<*> -> kotlinx.serialization.json.JsonArray(value.map { anyToJson(it) })
            else -> throw IllegalArgumentException("Cannot JSON-encode ${value::class.simpleName}")
        }

        /** Coerce a plist tree into a JSON element tree for persistence. */
        private fun plistToJson(value: PlistValue): JsonElement = when (value) {
            PlistValue.Null -> JsonPrimitive(null as String?)
            is PlistValue.Bool -> JsonPrimitive(value.value)
            is PlistValue.Int64 -> JsonPrimitive(value.value)
            is PlistValue.Real -> JsonPrimitive(value.value)
            is PlistValue.Str -> JsonPrimitive(value.value)
            is PlistValue.Data -> JsonPrimitive(Base64.encode(value.bytes))
            is PlistValue.Date -> JsonPrimitive(value.epochMillis)
            is PlistValue.Uid -> JsonPrimitive(value.value)
            is PlistValue.Dict -> JsonObject(value.entries.mapValues { plistToJson(it.value) })
            is PlistValue.Array -> kotlinx.serialization.json.JsonArray(
                value.items.map { plistToJson(it) }
            )
        }

    }
}
