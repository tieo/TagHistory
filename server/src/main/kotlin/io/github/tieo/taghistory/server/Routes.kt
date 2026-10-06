package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.data.repo.SyncTrigger
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File

/** HTTP surface of [TagHistoryServer]; see [ServerApi] for the contract. */
fun Application.tagHistoryModule(server: TagHistoryServer, webDir: File?, emojiFonts: EmojiFonts? = null) {
    val log = LoggerFactory.getLogger("TagHistoryRoutes")

    install(ContentNegotiation) { json(io.github.tieo.taghistory.server.ServerClient.WireJson) }
    install(Compression)
    install(StatusPages) {
        exception<AppleLoginException> { call, e ->
            val status = when (e.kind) {
                AppleLoginException.Kind.INVALID_CREDENTIALS,
                AppleLoginException.Kind.UNAUTHORIZED -> HttpStatusCode.Unauthorized
                AppleLoginException.Kind.INVALID_STATE -> HttpStatusCode.Conflict
                AppleLoginException.Kind.UNHANDLED_PROTOCOL -> HttpStatusCode.BadGateway
            }
            call.respond(status, ApiError(e.message ?: "Apple sign-in failed"))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request"))
        }
        exception<Throwable> { call, e ->
            log.error("Request failed: ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError(e.message ?: e::class.simpleName ?: "Error"))
        }
    }

    routing {
        get(ServerApi.STATUS) { call.respond(server.status()) }

        post(ServerApi.LOGIN) {
            val body = call.receive<LoginRequest>()
            call.respond(server.login.login(body.email, body.password))
        }
        post(ServerApi.LOGIN_2FA_REQUEST) {
            server.login.requestTwoFactor(call.receive<TwoFactorRequest>().method)
            call.respond(HttpStatusCode.NoContent)
        }
        post(ServerApi.LOGIN_2FA_SUBMIT) {
            val body = call.receive<TwoFactorRequest>()
            val code = requireNotNull(body.code) { "code is missing" }
            call.respond(server.login.submitTwoFactor(body.method, code))
        }
        post(ServerApi.LOGOUT) {
            server.logout()
            call.respond(HttpStatusCode.NoContent)
        }

        post(ServerApi.IMPORT) {
            call.respond(ImportResult(server.import(call.receive<ByteArray>())))
        }

        get(ServerApi.BEACONS) { call.respond(server.beacons()) }
        put("${ServerApi.BEACONS}/{id}") {
            val id = call.parameters["id"]!!
            val body = call.receive<BeaconOptionsDto>()
            require(body.beaconId == id) { "beaconId does not match the path" }
            server.setBeaconOptions(body)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("${ServerApi.BEACONS}/{id}") {
            server.removeBeacon(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }

        get(ServerApi.REPORTS) {
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: ServerApi.MAX_REPORTS_PAGE
            call.respond(server.reports(after, limit))
        }
        post(ServerApi.REPORTS) {
            call.respond(UploadResult(server.uploadReports(call.receive<List<ReportUpload>>())))
        }
        get(ServerApi.SYNC_RUNS) {
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            call.respond(server.syncRuns(after))
        }
        post(ServerApi.SYNC) { call.respond(server.runSync(SyncTrigger.MANUAL)) }

        get(ServerApi.GEOCODE) {
            val lat = requireNotNull(call.request.queryParameters["lat"]?.toDoubleOrNull()) { "lat is missing" }
            val lon = requireNotNull(call.request.queryParameters["lon"]?.toDoubleOrNull()) { "lon is missing" }
            call.respond(GeocodeResult(server.geocode(lat, lon)))
        }

        post(ServerApi.OAUTH2_CALLBACK) {
            val form = call.receiveParameters()
            call.respondText(oauthHandoffPage(form), ContentType.Text.Html)
        }

        get(ServerApi.EMOJI_FONT) {
            val text = requireNotNull(call.request.queryParameters["text"]) { "text is missing" }
            require(text.length <= 512) { "text is too long" }
            val font = emojiFonts?.subset(text)
            if (font == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("No emoji font"))
            } else {
                call.response.header("Cache-Control", "public, max-age=31536000")
                call.respondBytes(font, ContentType("font", "woff2"))
            }
        }

        if (webDir != null) {
            staticFiles("/", webDir) { default("index.html") }
        }
    }
}

/**
 * Hands an OIDC authorization response to the app. The page sends the
 * browser to the app's redirect URI at once; the link is there for browsers
 * that only open an app from a tap. The code is useless without the PKCE
 * verifier the app kept, so passing it through the browser exposes nothing.
 */
internal fun oauthHandoffPage(form: io.ktor.http.Parameters): String {
    val query = listOf("code", "state", "iss", "error", "error_description")
        .mapNotNull { key -> form[key]?.let { "$key=${it.encodeURLParameter()}" } }
        .joinToString("&")
    val target = "${ServerApi.APP_REDIRECT}?$query"
    val attr = target.replace("&", "&amp;").replace("\"", "&quot;")
    return """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>TagHistory</title>
<style>body{font-family:system-ui,sans-serif;background:#131318;color:#e7e0e8;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
a{background:#d0bcff;color:#381e72;padding:14px 28px;border-radius:24px;text-decoration:none;font-weight:600}</style>
</head><body><a href="$attr">Open TagHistory</a>
<script>location.replace(document.querySelector("a").href)</script></body></html>"""
}
