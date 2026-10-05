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
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.io.File

/** HTTP surface of [TagHistoryServer]; see [ServerApi] for the contract. */
fun Application.tagHistoryModule(server: TagHistoryServer, webDir: File?) {
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

        if (webDir != null) {
            staticFiles("/", webDir) { default("index.html") }
        }
    }
}
