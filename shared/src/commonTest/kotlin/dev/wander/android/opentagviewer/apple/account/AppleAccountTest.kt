package io.github.tieo.taghistory.apple.account

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks in the on-disk export shape — users already persisted by the
 * Java+Chaquopy code path restore through this class and must see their
 * `login_state.state` numeric values interpreted the same way.
 */
class AppleAccountTest {

    @Test
    fun defaultIdsAreUuidShaped() {
        val a = AppleAccount()
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(uuidRegex.matches(a.uid), "uid not UUID-shaped: ${a.uid}")
        assertTrue(uuidRegex.matches(a.devid), "devid not UUID-shaped: ${a.devid}")
        assertNotEquals(a.uid, a.devid)
    }

    @Test
    fun explicitIdsAreRetained() {
        val a = AppleAccount(uid = "fixed-uid", devid = "fixed-devid")
        assertEquals("fixed-uid", a.uid)
        assertEquals("fixed-devid", a.devid)
    }

    @Test
    fun loginStateDefaultsToLoggedOut() {
        assertEquals(LoginState.LOGGED_OUT, AppleAccount().loginState)
    }

    @Test
    fun downgradingStateClearsAccountInfo() {
        val a = AppleAccount(uid = "u", devid = "d")
        a.accountInfo = AccountInfo(accountName = "x@y", trustedDevice2fa = true)
        a.setLoginState(LoginState.AUTHENTICATED, mapOf("k" to JsonPrimitive("v")))
        assertNotNull(a.accountInfo)

        a.setLoginState(LoginState.LOGGED_OUT, null)
        assertNull(a.accountInfo, "info should clear on downgrade")
    }

    @Test
    fun exportIncludesStateAsInt() {
        val a = AppleAccount(uid = "u", devid = "d")
        a.username = "user@example.com"
        a.password = "hunter2"
        a.setLoginState(LoginState.AUTHENTICATED, mapOf("idms_pet" to JsonPrimitive("pet")))

        val exported = a.toExportMap()
        val state = exported["login_state"]!!.jsonObject["state"]!!.jsonPrimitive.content.toInt()
        assertEquals(2, state, "AUTHENTICATED must persist as numeric 2")
    }

    @Test
    fun roundTripPreservesEverything() {
        val original = AppleAccount(uid = "u-1", devid = "d-1")
        original.username = "user@example.com"
        original.password = "pw"
        original.accountInfo = AccountInfo(
            accountName = "User Name",
            firstName = "User",
            lastName = "Name",
            trustedDevice2fa = true,
        )
        original.setLoginState(
            LoginState.LOGGED_IN,
            mapOf(
                "dsid" to JsonPrimitive("42"),
                "mobileme_data" to buildJsonObject {
                    put("token", JsonPrimitive("abc"))
                },
            ),
        )

        val restored = AppleAccount.restoreFromJson(original.exportToJson())

        assertEquals(original.uid, restored.uid)
        assertEquals(original.devid, restored.devid)
        assertEquals(original.username, restored.username)
        assertEquals(original.password, restored.password)
        assertEquals(original.loginState, restored.loginState)
        assertEquals(original.loginStateData, restored.loginStateData)
        assertEquals(original.accountInfo?.accountName, restored.accountInfo?.accountName)
        assertEquals(original.accountInfo?.trustedDevice2fa, restored.accountInfo?.trustedDevice2fa)
    }

    @Test
    fun restoreFromJavaShapedPayload() {
        // Byte-for-byte compatible with what the old Java port produced —
        // users upgrading mid-session must not get logged out.
        val legacy = """
          {
            "ids":{"uid":"U","devid":"D"},
            "account":{
              "username":"x@y",
              "password":"p",
              "info":{"account_name":"A","first_name":"F","last_name":"L","trusted_device_2fa":true}
            },
            "login_state":{"state":1,"data":{"adsid":"a","idms_token":"t"}}
          }
        """.trimIndent()
        val a = AppleAccount.restoreFromJson(legacy)
        assertEquals("U", a.uid)
        assertEquals("D", a.devid)
        assertEquals("x@y", a.username)
        assertEquals("p", a.password)
        assertEquals(LoginState.REQUIRE_2FA, a.loginState)
        assertEquals("a", a.loginStateString("adsid"))
        assertEquals("t", a.loginStateString("idms_token"))
        assertEquals(true, a.accountInfo?.trustedDevice2fa)
    }
}
