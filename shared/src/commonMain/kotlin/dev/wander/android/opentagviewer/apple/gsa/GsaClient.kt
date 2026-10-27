package io.github.tieo.taghistory.apple.gsa

import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.apple.plist.anyToPlist
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
     *
     * When Apple asks the machine to resync (`hsc 434` with the SIM in
     * `X-Apple-I-MD-DATA`), the machine answers and the request goes out once
     * more with fresh anisette; Apple accepts the machine again after that.
     */
    suspend fun request(parameters: Map<String, Any?>): PlistValue.Dict {
        val first = send(parameters)
        val sim = resyncSim(first) ?: return first
        anisette.resync(sim)
        return send(parameters)
    }

    private suspend fun send(parameters: Map<String, Any?>): PlistValue.Dict {
        val bodyDict = linkedMapOf<String, Any?>(
            "Header" to linkedMapOf<String, Any?>("Version" to "1.0.1"),
            "Request" to linkedMapOf<String, Any?>().apply {
                put("cpd", anisette.getCpd())
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
                "X-MMe-Client-Info" to anisette.machine().clientInfo,
                GSA_CONNECTION,
            ),
            body = plistBody,
        )

        val resp = http.execute(req)
        if (!resp.isOk()) {
            // Apple explains a refusal in the body. The message ends up on the
            // sign-in screen, so an HTML error page shrinks to its title.
            val body = resp.body.decodeToString()
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()
            val detail = body.take(300).replace(Regex("\\s+"), " ").trim()
            throw GsaRequestException(
                "GSA request failed: HTTP " + when {
                    !title.isNullOrEmpty() -> title.removePrefix("${resp.statusCode}").trim().let { "${resp.statusCode} $it" }
                    detail.isEmpty() -> "${resp.statusCode}"
                    else -> "${resp.statusCode}: $detail"
                },
            )
        }

        val parsed = XmlPlist.parse(resp.body)
        require(parsed is PlistValue.Dict) { "GSA response is not a <dict>" }
        val response = parsed["Response"]
        require(response is PlistValue.Dict) { "GSA response missing 'Response' dictionary" }
        return response
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun resyncSim(response: PlistValue.Dict): ByteArray? {
        val status = response.dict("Status") ?: return null
        if (status.int64("hsc") != HSC_RESYNC) return null
        return when (val sim = status["X-Apple-I-MD-DATA"]) {
            is PlistValue.Data -> sim.bytes
            is PlistValue.Str -> Base64.decode(sim.value)
            else -> null
        }
    }

    companion object {
        const val ENDPOINT_GSA: String = "https://gsa.apple.com/grandslam/GsService2"

        /** Apple's "resync this machine" status. */
        private const val HSC_RESYNC = 434L
    }
}

/** Thrown for transport-level GSA failures (non-2xx response, unparseable body). */
class GsaRequestException(message: String) : RuntimeException(message)

/**
 * Sent on every request to gsa.apple.com. Apple's edge answers a request that
 * arrives on a connection an earlier gsa.apple.com request kept alive with
 * `429 Too Many Requests` (an HTML page, before Grand Slam sees it), so each
 * request closes its connection and the next one opens a fresh one.
 */
val GSA_CONNECTION: Pair<String, String> = "Connection" to "close"
