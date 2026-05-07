package io.github.tieo.taghistory.apple.gsa

import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.anyToPlist

/**
 * Low-level client for Apple's Grand Slam Authentication endpoint.
 *
 * Handles the plist envelope wrapping (Header + Request.cpd + caller
 * parameters), content-type negotiation, and the `["Response"]` unwrap
 * on the way back. Caller provides the SRP-specific fields like `A2k`,
 * `M1`, and `c`.
 *
 * Mirrors `AsyncAppleAccount._gsa_request` from `findmy/reports/account.py`.
 */
class GsaClient(
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val endpoint: String = ENDPOINT_GSA,
) {

    /**
     * Perform one GSA request. [parameters] are merged into the `Request`
     * dictionary at the top level (alongside `cpd`). Returns the parsed
     * `Response` sub-dictionary from the plist body.
     */
    suspend fun request(
        userId: String,
        deviceId: String,
        parameters: Map<String, Any?>,
    ): PlistValue.Dict {
        val bodyDict = linkedMapOf<String, Any?>(
            "Header" to linkedMapOf<String, Any?>("Version" to "1.0.1"),
            "Request" to linkedMapOf<String, Any?>().apply {
                put("cpd", anisette.getCpd(userId, deviceId, "0"))
                putAll(parameters)
            },
        )
        val plistBody = XmlPlist.encode(anyToPlist(bodyDict))

        val req = HttpRequest(
            method = "POST",
            url = endpoint,
            headers = linkedMapOf(
                "Content-Type" to "text/x-xml-plist",
                "Accept" to "*/*",
                "User-Agent" to "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0",
                "X-MMe-Client-Info" to anisette.clientInfo(),
            ),
            body = plistBody,
        )

        val resp = http.execute(req)
        if (!resp.isOk()) {
            throw GsaRequestException("GSA request failed: HTTP ${resp.statusCode}")
        }

        val parsed = XmlPlist.parse(resp.body)
        require(parsed is PlistValue.Dict) { "GSA response is not a <dict>" }
        val response = parsed["Response"]
        require(response is PlistValue.Dict) { "GSA response missing 'Response' dictionary" }
        return response
    }

    companion object {
        const val ENDPOINT_GSA: String = "https://gsa.apple.com/grandslam/GsService2"
    }
}

/** Thrown for transport-level GSA failures (non-2xx response, unparseable body). */
class GsaRequestException(message: String) : RuntimeException(message)
