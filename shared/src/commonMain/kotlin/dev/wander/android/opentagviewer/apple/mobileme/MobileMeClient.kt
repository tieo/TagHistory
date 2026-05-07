package io.github.tieo.taghistory.apple.mobileme

import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.anyToPlist
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Second half of the Apple sign-in: exchanges the GSA `idms_pet` token
 * for iCloud delegates including the `searchPartyToken` required to
 * fetch location reports.
 *
 * Single HTTPS POST, plist body, HTTP Basic auth with the Apple ID as
 * username and PET as password. Mirrors
 * `AsyncAppleAccount._login_mobileme`.
 */
@OptIn(ExperimentalEncodingApi::class)
class MobileMeClient(
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val endpoint: String = ENDPOINT,
) {

    /**
     * Returns the raw MobileMe response plist as a [PlistValue.Dict] —
     * caller pulls `dsid` and `delegates["com.apple.mobileme"]` out.
     */
    suspend fun login(
        username: String,
        idmsPet: String,
        adsid: String,
        userId: String,
        deviceId: String,
    ): PlistValue.Dict {
        val body = linkedMapOf<String, Any?>(
            "apple-id" to username,
            "delegates" to linkedMapOf<String, Any?>("com.apple.mobileme" to emptyMap<String, Any?>()),
            "password" to idmsPet,
            "client-id" to userId,
        )
        val plistBody = XmlPlist.encode(anyToPlist(body))

        val headers = linkedMapOf<String, String>().apply {
            put("X-Apple-ADSID", adsid)
            put("User-Agent", "com.apple.iCloudHelper/282 CFNetwork/1408.0.4 Darwin/22.5.0")
            put(
                "X-Mme-Client-Info",
                "<MacBookPro18,3> <Mac OS X;13.4.1;22F8> " +
                    "<com.apple.AOSKit/282 (com.apple.accountsd/113)>",
            )
            putAll(anisette.getHeaders(userId, deviceId, "0"))
            put("Authorization", basicAuth(username, idmsPet))
            put("Content-Type", "text/x-xml-plist")
            put("Accept", "*/*")
        }

        val resp = http.execute(
            HttpRequest(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = plistBody,
            )
        )
        if (!resp.isOk()) {
            throw MobileMeRequestException("MobileMe login failed: HTTP ${resp.statusCode}")
        }
        val parsed = XmlPlist.parse(resp.body)
        require(parsed is PlistValue.Dict) { "MobileMe response is not a <dict>" }
        return parsed
    }

    private fun basicAuth(user: String, pass: String): String {
        val token = "$user:$pass"
        return "Basic " + Base64.encode(token.encodeToByteArray())
    }

    companion object {
        const val ENDPOINT: String = "https://setup.icloud.com/setup/iosbuddy/loginDelegates"
    }
}

/** Thrown for transport-level MobileMe failures. */
class MobileMeRequestException(message: String) : RuntimeException(message)
