package io.github.tieo.taghistory.server

import kotlinx.serialization.Serializable

/**
 * Wire contract between the TagHistory sync server and its clients (the web
 * app and the Android app). The server is the one place that talks to Apple
 * and keeps the full history; clients replicate its tables into their own
 * local database and render from there with the same screens and view models
 * the standalone app uses.
 *
 * Paths are relative to the server origin. Every endpoint sits behind the
 * reverse proxy's login, so none of them carries its own auth.
 */
object ServerApi {
    const val STATUS = "/api/status"
    const val LOGIN = "/api/login"
    const val LOGIN_2FA_REQUEST = "/api/login/2fa/request"
    const val LOGIN_2FA_SUBMIT = "/api/login/2fa/submit"
    const val LOGOUT = "/api/logout"
    const val IMPORT = "/api/import"
    const val BEACONS = "/api/beacons"
    const val REPORTS = "/api/reports"
    const val SYNC_RUNS = "/api/sync-runs"
    const val SYNC = "/api/sync"
    const val GEOCODE = "/api/geocode"
    /**
     * OIDC redirect target for the app. Authelia answers with a form POST,
     * which an app scheme cannot receive, so the server passes the code on
     * to [APP_REDIRECT].
     */
    const val OAUTH2_CALLBACK = "/oauth2/callback"
    const val APP_REDIRECT = "io.github.tieo.taghistory:/oauth2redirect"
    /** `?text=` returns an emoji font covering exactly those characters. */
    const val EMOJI_FONT = "/api/emoji-font"

    /** `PUT` here with [BeaconOptionsDto] renames a beacon; `DELETE` removes it. */
    fun beacon(beaconId: String) = "$BEACONS/$beaconId"

    /** Largest page the server returns from [REPORTS]. */
    const val MAX_REPORTS_PAGE = 5_000
}

@Serializable
data class ServerStatus(
    /**
     * Identity of the server's database, fixed when it was created. Report
     * cursors are only meaningful against the database that issued them, so
     * a client that sees a new id starts its replication over.
     */
    val instanceId: String,
    /** True while the server holds a usable Apple session. */
    val signedIn: Boolean,
    val beaconCount: Int,
    val reportCount: Long,
    /** Newest sync run, or null before the first one. */
    val lastRun: SyncRunDto?,
    val syncIntervalMinutes: Int,
    val serverTimeMs: Long,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class TwoFactorMethodDto(
    val kind: Kind,
    val phoneNumberId: Int? = null,
    val phoneNumber: String? = null,
) {
    @Serializable
    enum class Kind { SMS, TRUSTED_DEVICE }
}

@Serializable
data class LoginResponse(
    val loggedIn: Boolean,
    /** Methods to pick from when Apple asks for a second factor. */
    val methods: List<TwoFactorMethodDto> = emptyList(),
)

@Serializable
data class TwoFactorRequest(val method: TwoFactorMethodDto, val code: String? = null)

@Serializable
data class ImportDto(
    val id: Long,
    val version: String?,
    val importedAt: Long,
    val exportedAt: Long,
    val sourceUser: String?,
    val via: String?,
)

/**
 * An owned beacon as clients see it. [content] is the accessory plist with
 * the key material stripped: clients read its descriptive fields (model,
 * pairing date, product id) and never decrypt reports themselves.
 */
@Serializable
data class OwnedBeaconDto(
    val id: String,
    val importId: Long?,
    val content: String?,
    val version: String?,
)

@Serializable
data class NamingRecordDto(
    val id: String,
    val importId: Long?,
    val version: String?,
    val content: String?,
)

@Serializable
data class BeaconOptionsDto(
    val beaconId: String,
    val lastUpdate: Long,
    val uiName: String?,
    val uiEmoji: String?,
)

/** Every live beacon with its naming record and the user's overrides. */
@Serializable
data class BeaconsSnapshot(
    val imports: List<ImportDto>,
    val owned: List<OwnedBeaconDto>,
    val naming: List<NamingRecordDto>,
    val options: List<BeaconOptionsDto>,
)

@Serializable
data class ReportDto(
    /** Server write sequence; the client's cursor for the next page. */
    val seq: Long,
    val hashId: String,
    val beaconId: String,
    val publishedAt: Long,
    val description: String?,
    val timestamp: Long,
    val confidence: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Long,
    val status: Long,
    val lastUpdate: Long,
)

@Serializable
data class ReportsPage(
    val reports: List<ReportDto>,
    /** Cursor to pass as `after` for the next page. */
    val next: Long,
    val hasMore: Boolean,
)

/** A report a client fetched itself and hands to the server (`POST` [ServerApi.REPORTS]). */
@Serializable
data class ReportUpload(
    val beaconId: String,
    val publishedAt: Long,
    val description: String?,
    val timestamp: Long,
    val confidence: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracy: Long,
    val status: Long,
)

@Serializable
data class UploadResult(
    /** Reports that were new to the server; known ones and unknown beacons are skipped. */
    val stored: Int,
)

@Serializable
data class SyncRunDto(
    val id: Long,
    val startedAt: Long,
    val trigger: String,
    val outcome: String,
    val detail: String?,
    val persistedReports: Long,
    val beaconCount: Long,
    val windowHours: Long?,
    val durationMs: Long?,
)

@Serializable
data class ImportResult(val imported: Int)

@Serializable
data class GeocodeResult(val address: String?)

@Serializable
data class ApiError(val message: String)
