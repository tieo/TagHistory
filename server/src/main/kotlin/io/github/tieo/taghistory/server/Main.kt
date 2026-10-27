package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.anisette.AnisetteProvider
import io.github.tieo.taghistory.anisette.desktopAnisetteProvider
import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.account.AppleLoginException
import io.github.tieo.taghistory.apple.account.AppleLoginService
import io.github.tieo.taghistory.apple.anisette.AnisetteClient
import io.github.tieo.taghistory.apple.gsa.GsaClient
import io.github.tieo.taghistory.apple.http.HttpTransport
import io.github.tieo.taghistory.apple.mobileme.MobileMeClient
import io.github.tieo.taghistory.apple.http.KtorHttpTransport
import io.github.tieo.taghistory.apple.http.createPlatformHttpClient
import io.github.tieo.taghistory.data.storage.SecureBlobStore
import io.github.tieo.taghistory.db.DatabaseDriverFactory
import io.github.tieo.taghistory.db.createDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random

/**
 * Runs the server. `anisette-check` instead generates one set of anisette
 * headers, then starts a sign-in for a made-up `.invalid` Apple ID and
 * prints Apple's verdict. That verifies the native bridge, CoreADI and that
 * Apple accepts the machine, without signing in to anything: Apple answers
 * the first step before it looks at any password.
 */
fun main(args: Array<String>) {
    val config = ServerConfig.fromEnvironment()
    check(config.blobKeyFile.isFile) { "Blob key file ${config.blobKeyFile} is missing" }
    check(config.ottjniLibrary.isFile) { "ottjni library ${config.ottjniLibrary} is missing" }
    System.setProperty("taghistory.ottjni.path", config.ottjniLibrary.absolutePath)

    config.dataDir.mkdirs()
    val http = KtorHttpTransport(createPlatformHttpClient())
    val anisette = desktopAnisetteProvider(
        image = File(config.appleLibsDir, "CoreADI.x86_64"),
        configDir = File(config.dataDir, "anisette"),
        http = http,
    )

    if (args.firstOrNull() == "anisette-check") {
        runBlocking {
            println("anisette OK: " + anisette.getHeaders().keys.sorted().joinToString())
            println("machine: " + anisette.machine().clientInfo)
            println("Grand Slam: " + probeGrandSlam(anisette, http))
        }
        return
    }

    val db = runBlocking {
        createDatabase(DatabaseDriverFactory("jdbc:sqlite:${File(config.dataDir, "taghistory.db").absolutePath}"))
    }
    val server = TagHistoryServer(
        db = db,
        settings = fileSettings(File(config.dataDir, "settings.properties")),
        blobStore = SecureBlobStore(config.blobKeyFile),
        anisetteProvider = anisette,
        http = http,
        syncIntervalMinutes = config.syncIntervalMinutes,
        geocoder = NominatimGeocoder(HttpClient(CIO)),
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    server.start(scope)
    embeddedServer(ServerCIO, host = config.bindHost, port = config.port) {
        tagHistoryModule(server, config.webDir, EmojiFonts(HttpClient(CIO)))
    }.start(wait = true)
}

/**
 * Apple's answer to a sign-in for an address that cannot exist. A machine
 * Apple refuses gets `-80045` ("This action cannot be completed at this
 * time"); an accepted one gets Apple's verdict on the address.
 */
private suspend fun probeGrandSlam(anisette: AnisetteProvider, http: HttpTransport): String {
    val client = AnisetteClient(anisette)
    val service = AppleLoginService(
        AppleAccount(),
        http,
        client,
        GsaClient(http, client),
        MobileMeClient(http, client),
    )
    val address = "probe-" + Random.nextLong().toULong().toString(16) + "@example.invalid"
    return try {
        "unexpected: " + service.login(address, "not-a-password")
    } catch (e: AppleLoginException) {
        e.message.orEmpty()
    }
}
