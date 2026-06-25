package io.github.tieo.taghistory.apple.reports

import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.LoginState
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.http.HttpRequest
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.sync.SyncEvent
import io.github.tieo.taghistory.sync.SyncLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Low-level fetcher for Apple's FindMy report endpoint
 * (`acsnservice/fetch`). Mirrors `AsyncAppleAccount.fetch_raw_reports`.
 *
 * Request body:
 * ```
 * {"search": [{"startDate": ms, "endDate": ms, "ids": [b64HashedAdvKey,...]}]}
 * ```
 * Auth is HTTP Basic with `dsid:searchPartyToken` pulled from the
 * MobileMe response. Anisette headers ride along without the extended
 * `X-Mme-Client-Info` block. Response:
 * ```
 * {"statusCode": "200", "results": [{"payload", "id", "datePublished", "description"}, ...]}
 * ```
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LocationReportsClient(
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val endpoint: String = ENDPOINT_REPORTS_FETCH,
) {

    suspend fun fetchRaw(
        account: AppleAccount,
        startEpochMs: Long,
        endEpochMs: Long,
        hashedAdvKeysB64: List<String>,
    ): JsonObject {
        if (account.loginState != LoginState.LOGGED_IN) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Reports fetch requires LOGGED_IN state, got ${account.loginState}",
            )
        }

        val dsid = account.loginStateString("dsid")
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Missing dsid in account state",
            )
        val mobileme = account.loginStateData["mobileme_data"] as? JsonObject
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Missing mobileme_data in account state",
            )
        val tokens = mobileme["tokens"] as? JsonObject
        val searchPartyToken = (tokens?.get("searchPartyToken") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Missing searchPartyToken",
            )

        val body = buildJsonObject {
            put("search", buildJsonArray {
                add(buildJsonObject {
                    put("startDate", JsonPrimitive(startEpochMs))
                    put("endDate", JsonPrimitive(endEpochMs))
                    put("ids", buildJsonArray {
                        hashedAdvKeysB64.forEach { add(JsonPrimitive(it)) }
                    })
                })
            })
        }
        val payload = JSON.encodeToString(JsonObject.serializer(), body).encodeToByteArray()
        val basic = Base64.encode("$dsid:$searchPartyToken".encodeToByteArray())

        val headers = linkedMapOf<String, String>(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Basic $basic",
        )
        headers.putAll(
            anisette.getHeaders(account.uid, account.devid, "0", withClientInfo = false)
        )

        val resp = http.execute(
            HttpRequest(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = payload,
            )
        )
        // Log EVERY fetch's HTTP status so 5xx/4xx from acsnservice are
        // visible in logcat, not just swallowed into an exception message.
        SyncLog.record(
            if (resp.isOk()) SyncEvent.Kind.INFO else SyncEvent.Kind.RUNG_FAIL,
            "acsnservice/fetch HTTP ${resp.statusCode}",
            mapOf(
                "status" to resp.statusCode.toString(),
                "keys" to hashedAdvKeysB64.size.toString(),
                "window_ms" to (endEpochMs - startEpochMs).toString(),
                "body_bytes" to resp.body.size.toString(),
            ),
        )
        if (resp.statusCode == 401) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNAUTHORIZED,
                "Not authorized to fetch reports (HTTP 401). Re-authenticate.",
            )
        }
        if (!resp.isOk()) {
            throw ReportsFetchException("Reports fetch failed: HTTP ${resp.statusCode}")
        }

        val parsed = JSON.parseToJsonElement(resp.body.decodeToString()) as? JsonObject
            ?: throw ReportsFetchException("Reports response is not a JSON object")
        val status = (parsed["statusCode"] as? JsonPrimitive)?.content
        if (status != null && status != "200") {
            throw ReportsFetchException("Reports fetch returned statusCode=$status")
        }
        return parsed
    }

    companion object {
        const val ENDPOINT_REPORTS_FETCH: String =
            "https://gateway.icloud.com/acsnservice/fetch"

        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        /**
         * Parse the `results` array of a raw fetch response into
         * [LocationReport] instances (not yet decrypted). Reports are kept
         * in server order — the caller sorts / filters as needed.
         */
        fun parseReports(rawResponse: JsonObject): List<LocationReport> {
            val results = (rawResponse["results"] as? JsonArray) ?: return emptyList()
            val out = mutableListOf<LocationReport>()
            for (row in results) {
                val obj = row as? JsonObject ?: continue
                val payloadStr = (obj["payload"] as? JsonPrimitive)?.content ?: continue
                val idStr = (obj["id"] as? JsonPrimitive)?.content ?: continue
                val payload = Base64.decode(payloadStr)
                val hashed = Base64.decode(idStr)
                val publishedMs =
                    (obj["datePublished"] as? JsonPrimitive)?.longOrNull ?: 0L
                val description = (obj["description"] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content ?: ""
                out += LocationReport(
                    payload,
                    hashed,
                    Instant.fromEpochMilliseconds(publishedMs),
                    description,
                )
            }
            return out
        }
    }
}

/** Transport-level failures on the FindMy reports endpoint. */
class ReportsFetchException(message: String) : RuntimeException(message)
