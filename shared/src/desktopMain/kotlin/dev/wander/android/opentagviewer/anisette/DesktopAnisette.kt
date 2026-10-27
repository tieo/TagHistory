package io.github.tieo.taghistory.anisette

import io.github.tieo.taghistory.apple.http.HttpTransport
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * A plain JVM host's Mac: Apple's macOS CoreADI [image] (the x86_64 slice
 * from iTunes 12.8.3 for Mac), and the machine file in [configDir], which
 * must persist: it is the machine's identity, and a new one makes Apple ask
 * for the second factor again.
 */
fun desktopAnisetteProvider(image: File, configDir: File, http: HttpTransport): AnisetteProvider =
    MacAnisetteProvider(
        openAdi = { hostUuid, store ->
            if (!image.isFile) throw AnisetteException("CoreADI not found at $image")
            JniCoreAdi(image.readBytes(), hostUuid, store)
        },
        storage = FileMachineStorage(File(configDir, "machine.json")),
        http = http,
        nativeVersion = JniCoreAdi::version,
        blockingContext = Dispatchers.IO,
    )
