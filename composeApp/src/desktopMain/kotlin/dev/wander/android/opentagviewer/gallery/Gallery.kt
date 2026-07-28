package io.github.tieo.taghistory.gallery

import androidx.compose.runtime.Composable
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.russhwolf.settings.PropertiesSettings
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserDataRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.TagHistoryDatabase
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoScreen
import io.github.tieo.taghistory.ui.deviceinfo.DeviceInfoViewModel
import io.github.tieo.taghistory.ui.history.HistoryScreen
import io.github.tieo.taghistory.ui.history.HistoryViewModel
import io.github.tieo.taghistory.ui.information.InformationScreen
import io.github.tieo.taghistory.ui.login.AppleLoginViewModel
import io.github.tieo.taghistory.ui.login.LoginScreen
import io.github.tieo.taghistory.ui.manage.ManageTagsScreen
import io.github.tieo.taghistory.ui.map.MapScreen
import io.github.tieo.taghistory.ui.map.MapViewModel
import io.github.tieo.taghistory.ui.map.TagCardUi
import io.github.tieo.taghistory.ui.nearby.NearbyScreen
import io.github.tieo.taghistory.ui.nearby.NearbyViewModel
import io.github.tieo.taghistory.ui.nearby.OwnedTagInfo
import io.github.tieo.taghistory.ui.settings.SettingsScreen
import io.github.tieo.taghistory.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.io.File
import java.util.Properties

/*
 * A gallery of the app's screens, drawn off-screen with sample data on the JVM
 * desktop target. Each view is drawn in the states it can be in; the state name
 * becomes part of the file name so the model reads as a list, not a lookup.
 *
 * The screens are ViewModel-driven, so a scene builds the real ViewModel over an
 * in-memory database, seeds it, lets its state settle, then renders. The map
 * layer itself is Android-native (MapLibre); on the desktop renderer it is a
 * placeholder, so map renders show the real card + chrome over a stub map.
 */

// A real phone frame, not a tall strip: a Pixel-class screen is about 880 dp
// high, so anything much taller just renders a screen with a big empty bottom.
private const val PHONE_W = 390
private const val PHONE_H = 880
private const val WIDE_W = 1180
private const val WIDE_H = 900
private const val CARD_H = 480

private data class Scene(val view: String, val state: String, val content: @Composable () -> Unit)

// ── in-memory backing ──────────────────────────────────────────────────────

private fun newDb(): TagHistoryDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties())
    TagHistoryDatabase.Schema.create(driver)
    return TagHistoryDatabase(driver)
}

private fun TagHistoryDatabase.seedBeacon(id: String, name: String, emoji: String?) {
    beaconNamingRecordQueries.upsert(id = id, importId = null, version = "1", content = null, isRemoved = false)
    ownedBeaconQueries.upsert(id = id, importId = null, content = "plist", version = "1", isRemoved = false)
    userBeaconOptionsQueries.upsert(beaconId = id, lastUpdate = 0L, uiName = name, uiEmoji = emoji)
}

private fun TagHistoryDatabase.seedLocation(id: String, ts: Long, lat: Double, lon: Double) {
    locationReportQueries.upsert(
        hashId = "h-$id-$ts", beaconId = id, publishedAt = ts, description = "",
        timestamp = ts, confidence = 1, latitude = lat, longitude = lon,
        horizontalAccuracy = 10, status = 0, lastUpdate = ts,
    )
}

private fun authRepo(): UserAuthRepository =
    UserAuthRepository(PropertiesSettings(Properties()), SecureBlobStore(), "gallery").apply {
        storeUserAuth("""{"account":null}""".encodeToByteArray())
    }

private fun galleryScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

/** Build a MapViewModel over a freshly-seeded DB and let its boot+refresh settle. */
private fun mapVm(
    seed: TagHistoryDatabase.() -> Unit,
    fetchReports: suspend (Map<String, BeaconData>, Int) -> Map<String, List<BeaconLocationReport>> =
        { _, _ -> emptyMap() },
): MapViewModel {
    val db = newDb().apply(seed)
    val vm = MapViewModel(
        beaconRepo = BeaconRepository(db) { 1_000L },
        userDataRepo = UserDataRepository(PropertiesSettings(Properties())),
        authRepo = authRepo(),
        fetchReports = fetchReports,
        minRefreshIntervalMs = 0L,
        scope = galleryScope(),
        ioDispatcher = Dispatchers.Unconfined,
    )
    runBlocking { delay(120) }
    return vm
}

private val nowMs = System.currentTimeMillis()
private val hoursAgo = { h: Long -> nowMs - h * 3_600_000L }

private fun report(ts: Long, lat: Double, lon: Double) = BeaconLocationReport(
    publishedAt = ts, description = "", timestamp = ts, confidence = 1,
    latitude = lat, longitude = lon, horizontalAccuracy = 10, status = 0,
)

/** A day of movement: two moves, then a stop cluster of three near-identical fixes. */
private fun sampleDay(): List<BeaconLocationReport> {
    val base = hoursAgo(9)
    val m = 60_000L
    return listOf(
        report(base, 48.3980, 9.9915),
        report(base + 40 * m, 48.3010, 9.8400),
        report(base + 95 * m, 48.2951, 9.7213),
        report(base + 110 * m, 48.2952, 9.7214),
        report(base + 130 * m, 48.2951, 9.7212),
    )
}

private fun historyVm(
    seed: TagHistoryDatabase.() -> Unit = {},
    fetchRange: suspend (String, Long, Long) -> List<BeaconLocationReport> = { _, _, _ -> emptyList() },
    fetch: Boolean = false,
): HistoryViewModel {
    val db = newDb().apply { seedBeacon("a", "Car Keys", "🔑"); seed() }
    val vm = HistoryViewModel(
        beaconRepo = BeaconRepository(db) { 1_000L },
        beaconId = "a",
        fetchRange = fetchRange,
        scope = galleryScope(),
        nowMs = { nowMs },
    )
    // load() reads what is already cached; fetchAndLoad() runs the network path
    // (used only to show the loading state via a fetch that never returns).
    if (fetch) vm.fetchAndLoad(hoursAgo(24), nowMs) else vm.load(hoursAgo(24), nowMs)
    runBlocking { delay(120) }
    return vm
}

private fun deviceInfoVm(seed: TagHistoryDatabase.() -> Unit): DeviceInfoViewModel {
    val db = newDb().apply(seed)
    val vm = DeviceInfoViewModel(
        beaconRepo = BeaconRepository(db) { 1_000L },
        beaconId = "a",
        nowMs = { nowMs },
        scope = galleryScope(),
    )
    runBlocking { delay(80) }
    return vm
}

private val sampleTags = listOf(
    OwnedTagInfo("a", "Car Keys", "🔑"),
    OwnedTagInfo("b", "Backpack", "🎒"),
    OwnedTagInfo("c", "Bike", "🚲"),
)

private fun nearbyVm(tags: List<OwnedTagInfo>): NearbyViewModel {
    val db = newDb().apply { tags.forEach { seedBeacon(it.beaconId, it.displayName, it.emoji) } }
    val vm = NearbyViewModel(
        beaconRepo = BeaconRepository(db) { 1_000L },
        loadOwnedTags = { tags },
        startBleScan = { _, _ -> Job() },
        uwbAvailable = false,
        scope = galleryScope(),
        now = { nowMs },
    )
    vm.onStart()
    runBlocking { delay(60) }
    return vm
}

private val sampleCards = listOf(
    TagCardUi("a", "Car Keys", "🔑", "AirTag", 48.2094, 9.7203, hoursAgo(1), "Tulpenweg 52, Ulm"),
    TagCardUi("b", "Backpack", "🎒", "AirTag", 48.2951, 9.7213, hoursAgo(3), "Albert-Einstein-Allee 11, Ulm"),
    TagCardUi("c", "Bike", "🚲", "AirTag", null, null, null, null),
)

private val SCENES: List<Scene> = buildList {
    // ── Map ─────────────────────────────────────────────────────────────────
    add(Scene("map", "as-it-is") {
        MapScreen(
            viewModel = mapVm(seed = {
                seedBeacon("a", "Car Keys", "🔑"); seedLocation("a", hoursAgo(1), 48.2094, 9.7203)
                seedBeacon("b", "Backpack", "🎒"); seedLocation("b", hoursAgo(3), 48.2951, 9.7213)
                seedBeacon("c", "Bike", "🚲")
            }),
        )
    })
    add(Scene("map", "empty") { MapScreen(viewModel = mapVm(seed = {})) })
    add(Scene("map", "loading") {
        // A never-returning fetch leaves the initial fetch incomplete, which is
        // the shimmer/loading state.
        MapScreen(viewModel = mapVm(seed = { seedBeacon("a", "Car Keys", "🔑") }) { _, _ ->
            kotlinx.coroutines.awaitCancellation()
        })
    })
    add(Scene("map", "failed") {
        MapScreen(viewModel = mapVm(seed = { seedBeacon("a", "Car Keys", "🔑") }) { _, _ ->
            throw RuntimeException("No internet connection")
        })
    })

    // ── Manage tags ───────────────────────────────────────────────────────────
    add(Scene("manage-tags", "as-it-is") {
        ManageTagsScreen(
            cards = sampleCards, onBack = {}, onRename = { _, _, _ -> }, onRemove = {},
            onImport = null, onExportSelected = null,
        )
    })
    add(Scene("manage-tags", "empty") {
        ManageTagsScreen(
            cards = emptyList(), onBack = {}, onRename = { _, _, _ -> }, onRemove = {},
            onImport = null, onExportSelected = null,
        )
    })

    // ── Settings ──────────────────────────────────────────────────────────────
    add(Scene("settings", "as-it-is") {
        SettingsScreen(
            viewModel = SettingsViewModel(
                settingsRepo = UserSettingsRepository(PropertiesSettings(Properties())),
                authRepo = authRepo(),
                scope = galleryScope(),
            ).also { runBlocking { delay(60) } },
            onOpenInformation = {},
            onRefreshNow = { "Refreshed 3 tags" },
            isIgnoringBatteryOptimizations = { false },
            requestIgnoreBatteryOptimizations = {},
        )
    })

    // ── Information ────────────────────────────────────────────────────────────
    add(Scene("information", "as-it-is") {
        InformationScreen(versionName = "1.0.4", onBack = {}, onOpenUrl = {})
    })

    // ── History ───────────────────────────────────────────────────────────────
    // Drawn at its phases per the viewbook note: nothing yet, a day of movement,
    // and no movement in range.
    add(Scene("history", "as-it-is") {
        HistoryScreen(
            viewModel = historyVm(seed = { sampleDay().forEach { seedLocation("a", it.timestamp, it.latitude, it.longitude) } }),
            title = "Car Keys 🔑", onBack = {},
        )
    })
    add(Scene("history", "loading") {
        HistoryScreen(viewModel = historyVm(fetchRange = { _, _, _ -> awaitCancellation() }, fetch = true), title = "Car Keys 🔑", onBack = {})
    })
    add(Scene("history", "empty") {
        HistoryScreen(viewModel = historyVm(), title = "Car Keys 🔑", onBack = {})
    })

    // ── Device info ────────────────────────────────────────────────────────────
    add(Scene("device-info", "as-it-is") {
        DeviceInfoScreen(
            viewModel = deviceInfoVm { seedBeacon("a", "Car Keys", "🔑"); seedLocation("a", hoursAgo(2), 48.2094, 9.7203) },
            onBack = {}, onOpenHistory = {},
        )
    })
    add(Scene("device-info", "empty") {
        DeviceInfoScreen(viewModel = deviceInfoVm { seedBeacon("a", "Car Keys", "🔑") }, onBack = {}, onOpenHistory = {})
    })

    // ── Login ─────────────────────────────────────────────────────────────────
    add(Scene("login", "as-it-is") {
        LoginScreen(
            viewModel = AppleLoginViewModel(
                startLogin = { _, _ -> throw IllegalStateException("gallery") },
                scope = galleryScope(),
            ),
        )
    })

    // ── Nearby ────────────────────────────────────────────────────────────────
    add(Scene("nearby", "as-it-is") { NearbyScreen(viewModel = nearbyVm(sampleTags)) })
    add(Scene("nearby", "empty") { NearbyScreen(viewModel = nearbyVm(emptyList())) })
}

private data class RenderJob(val suffix: String, val width: Int, val height: Int, val dark: Boolean, val scale: Float)

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    val only = System.getProperty("gallery.only")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    val sizes = (System.getProperty("gallery.sizes") ?: "phone,wide,card").split(",").map { it.trim() }.toSet()
    val themes = (System.getProperty("gallery.themes") ?: "light,dark").split(",").map { it.trim() }.toSet()
    val started = System.currentTimeMillis()

    val wanted = SCENES.filter { only == null || it.view in only || "${it.view}-${it.state}" in only }
    if (wanted.isEmpty()) {
        println("no scene matches ${only?.joinToString(",")}; views: ${SCENES.map { it.view }.distinct().joinToString(",")}")
        return
    }

    var drawn = 0
    for (scene in wanted) {
        val stem = if (scene.state == "as-it-is") scene.view else "${scene.view}-${scene.state}"
        val jobs = buildList {
            for (theme in listOf("light", "dark")) {
                if (theme !in themes) continue
                val dark = theme == "dark"
                if ("phone" in sizes) add(RenderJob("phone-$theme", PHONE_W, PHONE_H, dark, 2f))
                if ("wide" in sizes) add(RenderJob("wide-$theme", WIDE_W, WIDE_H, dark, 1.5f))
                if ("card" in sizes && scene.state == "as-it-is") add(RenderJob("card-$theme", PHONE_W, CARD_H, dark, 2f))
            }
        }
        for (job in jobs) {
            runCatching {
                renderToPng("$stem-${job.suffix}", job.width, job.height, dark = job.dark, outDir = outDir, scale = job.scale, content = scene.content)
                drawn++
            }.onFailure { println("FAILED $stem-${job.suffix}: ${it.message}") }
        }
    }
    println("$drawn renders of ${wanted.size} scenes in ${(System.currentTimeMillis() - started) / 1000.0}s")
    println("gallery written to ${outDir.absolutePath}")
}
