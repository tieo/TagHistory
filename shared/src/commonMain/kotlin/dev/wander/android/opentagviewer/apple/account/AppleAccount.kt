package io.github.tieo.taghistory.apple.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmStatic
import kotlin.random.Random

/**
 * Persistent state for a single Apple account — identity, credentials,
 * current login-machine position, and whatever opaque blobs Apple handed
 * back on the last successful step (PET token, ADSID, delegate tokens).
 *
 * JSON export shape matches the Java port bit-for-bit:
 * ```
 * {
 *   "ids":         { "uid": "...", "devid": "..." },
 *   "account":     { "username": "...", "password": "...", "info": {...} },
 *   "login_state": { "state": 0..3, "data": {...} }
 * }
 * ```
 * so an account persisted by the old Java+Chaquopy path restores cleanly.
 * Schema drift would mean locked-out users on update — we keep keys and
 * numeric state values exactly as before.
 *
 * This class is state-only. Login orchestration lives in
 * [AppleLoginService]; request encoding lives in the transport clients.
 * Keeping it network-free makes it trivial to test.
 */
class AppleAccount(
    uid: String? = null,
    devid: String? = null,
) {
    var uid: String = uid ?: randomUuid()
        private set
    var devid: String = devid ?: randomUuid()
        private set

    var username: String? = null
    var password: String? = null

    var accountInfo: AccountInfo? = null
        internal set

    var loginState: LoginState = LoginState.LOGGED_OUT
        private set

    private var _loginStateData: Map<String, JsonElement> = emptyMap()
    val loginStateData: Map<String, JsonElement> get() = _loginStateData

    /** Convenience: read a string-valued [loginStateData] entry. Returns null when absent. */
    fun loginStateString(key: String): String? =
        (_loginStateData[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Downgrading to a lower [LoginState] clears the cached [accountInfo]
     * — matches `AsyncAppleAccount._set_login_state` in the Python source.
     */
    fun setLoginState(state: LoginState, data: Map<String, JsonElement>?) {
        if (state.value < loginState.value) {
            accountInfo = null
        }
        loginState = state
        _loginStateData = data.orEmpty().toMap()
    }

    fun toExportMap(): JsonObject = buildJsonObject {
        put("ids", buildJsonObject {
            put("uid", JsonPrimitive(uid))
            put("devid", JsonPrimitive(devid))
        })
        put("account", buildJsonObject {
            put("username", username?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            put("password", password?.let(::JsonPrimitive) ?: JsonPrimitive(null as String?))
            put("info", accountInfo?.let { JSON.encodeToJsonElement(it) } ?: JsonPrimitive(null as String?))
        })
        put("login_state", buildJsonObject {
            put("state", JsonPrimitive(loginState.value))
            put("data", JsonObject(_loginStateData))
        })
    }

    fun exportToJson(): String = JSON.encodeToString(JsonObject.serializer(), toExportMap())

    companion object {
        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        @JvmStatic
        fun restoreFromJson(json: String): AppleAccount =
            restoreFromMap(JSON.parseToJsonElement(json).jsonObject)

        @JvmStatic
        fun restoreFromMap(data: JsonObject): AppleAccount = try {
            val ids = data.getValue("ids").jsonObject
            val account = data.getValue("account").jsonObject
            val login = data.getValue("login_state").jsonObject

            val acc = AppleAccount(
                uid = ids["uid"]?.nullableStringContent(),
                devid = ids["devid"]?.nullableStringContent(),
            )
            acc.username = account["username"]?.nullableStringContent()
            acc.password = account["password"]?.nullableStringContent()
            val info = account["info"]
            if (info is JsonObject) {
                acc.accountInfo = JSON.decodeFromJsonElement<AccountInfo>(info)
            }

            val stateNum = login.getValue("state").jsonPrimitive.content.toInt()
            acc.loginState = LoginState.fromValue(stateNum)
            val d = login["data"]
            acc._loginStateData = if (d is JsonObject) d.toMap() else emptyMap()
            acc
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to restore account data: ${e.message}", e)
        }

        private fun JsonElement.nullableStringContent(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * UUID generator matching Java's `UUID.randomUUID()` format
         * (lowercase-hex 8-4-4-4-12) so exports stay diff-stable across
         * platforms. Uses [Random.Default] — good enough for device IDs
         * that Apple only treats as opaque strings.
         */
        private fun randomUuid(): String {
            val bytes = ByteArray(16).also { Random.Default.nextBytes(it) }
            // RFC 4122 v4 variant bits — not strictly required by Apple but
            // keeps the generated values indistinguishable from java.util.UUID.
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = bytes.joinToString("") { b ->
                val v = b.toInt() and 0xff
                val hi = v ushr 4
                val lo = v and 0xf
                "${hexChar(hi)}${hexChar(lo)}"
            }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }

        private fun hexChar(n: Int): Char =
            if (n < 10) ('0' + n) else ('a' + (n - 10))
    }
}
