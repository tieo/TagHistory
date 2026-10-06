package io.github.tieo.taghistory.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** A server call that came back with a non-2xx status. */
class ServerException(val status: Int, message: String) : RuntimeException(message)

/** Access tokens for a server behind an OIDC-guarded proxy. */
fun interface BearerSource {
    /** A token to send; [renew] asks for a fresh one after the proxy refused the last. */
    suspend fun token(renew: Boolean): String
}

/**
 * Typed client for [ServerApi]. [baseUrl] is the server origin without a
 * trailing slash; the web app passes its own page origin, so requests carry
 * the reverse proxy's session cookie. The Android app passes [bearer]
 * instead: the proxy answers a refused token with a login redirect (or 401),
 * so the request is retried once with a renewed token.
 */
class ServerClient(
    private val baseUrl: String,
    engineClient: HttpClient = HttpClient(),
    bearer: BearerSource? = null,
) {
    private val http = engineClient.config {
        install(ContentNegotiation) { json(WireJson) }
        expectSuccess = false
        // A login redirect is an auth failure to report, not a page to load.
        followRedirects = false
    }.apply {
        if (bearer != null) {
            plugin(HttpSend).intercept { request ->
                request.headers[HttpHeaders.Authorization] = "Bearer ${bearer.token(renew = false)}"
                val first = execute(request)
                if (!first.response.status.isAuthRefusal()) return@intercept first
                request.headers[HttpHeaders.Authorization] = "Bearer ${bearer.token(renew = true)}"
                execute(request)
            }
        }
    }

    suspend fun status(): ServerStatus = http.get(url(ServerApi.STATUS)).decode()

    suspend fun login(email: String, password: String): LoginResponse =
        http.post(url(ServerApi.LOGIN)) { jsonBody(LoginRequest(email, password)) }.decode()

    suspend fun requestTwoFactor(method: TwoFactorMethodDto) {
        http.post(url(ServerApi.LOGIN_2FA_REQUEST)) { jsonBody(TwoFactorRequest(method)) }.decode<Unit>()
    }

    suspend fun submitTwoFactor(method: TwoFactorMethodDto, code: String): LoginResponse =
        http.post(url(ServerApi.LOGIN_2FA_SUBMIT)) { jsonBody(TwoFactorRequest(method, code)) }.decode()

    suspend fun logout() {
        http.post(url(ServerApi.LOGOUT)).decode<Unit>()
    }

    /** Uploads an OpenTagViewer export zip. */
    suspend fun import(zip: ByteArray): ImportResult =
        http.post(url(ServerApi.IMPORT)) {
            contentType(ContentType.Application.Zip)
            setBody(zip)
        }.decode()

    suspend fun beacons(): BeaconsSnapshot = http.get(url(ServerApi.BEACONS)).decode()

    suspend fun setBeaconOptions(options: BeaconOptionsDto) {
        http.put(url(ServerApi.beacon(options.beaconId))) { jsonBody(options) }.decode<Unit>()
    }

    suspend fun removeBeacon(beaconId: String) {
        http.delete(url(ServerApi.beacon(beaconId))).decode<Unit>()
    }

    suspend fun reports(after: Long, limit: Int = ServerApi.MAX_REPORTS_PAGE): ReportsPage =
        http.get(url(ServerApi.REPORTS)) {
            parameter("after", after)
            parameter("limit", limit)
        }.decode()

    suspend fun uploadReports(reports: List<ReportUpload>): UploadResult =
        http.post(url(ServerApi.REPORTS)) { jsonBody(reports) }.decode()

    suspend fun syncRuns(after: Long): List<SyncRunDto> =
        http.get(url(ServerApi.SYNC_RUNS)) { parameter("after", after) }.decode()

    /** Runs a sync on the server now and returns its run record. */
    suspend fun syncNow(): SyncRunDto = http.post(url(ServerApi.SYNC)).decode()

    suspend fun geocode(latitude: Double, longitude: Double): String? =
        http.get(url(ServerApi.GEOCODE)) {
            parameter("lat", latitude)
            parameter("lon", longitude)
        }.decode<GeocodeResult>().address

    /** Emoji font for exactly [text]'s characters, or null when unavailable. */
    suspend fun emojiFont(text: String): ByteArray? {
        val response = http.get(url(ServerApi.EMOJI_FONT)) { parameter("text", text) }
        return if (response.status.isSuccess()) response.body<ByteArray>() else null
    }

    private fun url(path: String) = baseUrl + path

    private fun HttpStatusCode.isAuthRefusal() =
        this == HttpStatusCode.Unauthorized || this == HttpStatusCode.Found

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        if (!status.isSuccess()) {
            val text = bodyAsText()
            val message = runCatching { WireJson.decodeFromString(ApiError.serializer(), text).message }
                .getOrNull() ?: text.ifBlank { status.description }
            throw ServerException(status.value, message)
        }
        @Suppress("UNCHECKED_CAST")
        return if (T::class == Unit::class) Unit as T else body()
    }

    companion object {
        val WireJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
