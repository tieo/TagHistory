package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.anisette.DesktopAnisetteProvider
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
import java.nio.file.Files

/**
 * Runs the server. `anisette-check` instead generates one set of anisette
 * headers and prints their names, which verifies the native bridge and the
 * Apple libraries on a host without signing in to anything.
 */
fun main(args: Array<String>) {
    val config = ServerConfig.fromEnvironment()
    check(config.blobKeyFile.isFile) { "Blob key file ${config.blobKeyFile} is missing" }
    check(config.ottjniLibrary.isFile) { "ottjni library ${config.ottjniLibrary} is missing" }
    System.setProperty("taghistory.ottjni.path", config.ottjniLibrary.absolutePath)

    config.dataDir.mkdirs()
    val anisetteDir = File(config.dataDir, "anisette")
    linkAppleLibs(config.appleLibsDir, File(anisetteDir, "lib/x86_64"))

    if (args.firstOrNull() == "anisette-check") {
        val headers = runBlocking { DesktopAnisetteProvider(anisetteDir).getHeaders() }
        println("anisette OK: " + headers.keys.sorted().joinToString())
        return
    }

    val db = runBlocking {
        createDatabase(DatabaseDriverFactory("jdbc:sqlite:${File(config.dataDir, "taghistory.db").absolutePath}"))
    }
    val server = TagHistoryServer(
        db = db,
        settings = fileSettings(File(config.dataDir, "settings.properties")),
        blobStore = SecureBlobStore(config.blobKeyFile),
        anisetteProvider = DesktopAnisetteProvider(anisetteDir),
        http = KtorHttpTransport(createPlatformHttpClient()),
        syncIntervalMinutes = config.syncIntervalMinutes,
        geocoder = NominatimGeocoder(HttpClient(CIO)),
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    server.start(scope)
    embeddedServer(ServerCIO, host = config.bindHost, port = config.port) {
        tagHistoryModule(server, config.webDir)
    }.start(wait = true)
}

/**
 * Omnisette looks for Apple's libraries inside the anisette directory, which
 * must stay writable and persistent for the provisioning state. The libraries
 * themselves are deployed read-only elsewhere and linked in.
 */
private fun linkAppleLibs(source: File, target: File) {
    target.mkdirs()
    for (name in listOf("libCoreADI.so", "libstoreservicescore.so")) {
        val from = File(source, name)
        check(from.isFile) { "Apple library $from is missing" }
        val link = File(target, name).toPath()
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, from.toPath().toAbsolutePath())
    }
}
