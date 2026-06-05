package io.github.tieo.taghistory.anisette

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Browser AnisetteProvider that proxies to a remote anisette-v3-server.
 * Apple's ADI provisioning needs native libCoreADI / libstoreservicescore
 * binaries which cannot run in a sandboxed wasm bundle; a remote service
 * is the only practical path on web.
 *
 * Default URL points at the public SideStore anisette service; users
 * who own a self-hosted server can pass their own. Note: trusting any
 * third-party anisette server gives that server visibility into your
 * machine ID and request times.
 */
class RemoteAnisetteProvider(
    private val http: HttpClient,
    private val baseUrl: String = "https://ani.sidestore.io",
) : AnisetteProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun version(): String = "remote@$baseUrl"

    override suspend fun getHeaders(): Map<String, String> {
        val response = http.get("$baseUrl/v3/get_headers")
        if (!response.status.isSuccess()) {
            throw AnisetteException(
                "Remote anisette server $baseUrl returned ${response.status.value}",
            )
        }
        val body = response.bodyAsText()
        val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw AnisetteException("Remote anisette response was not a JSON object")
        return obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
    }
}
