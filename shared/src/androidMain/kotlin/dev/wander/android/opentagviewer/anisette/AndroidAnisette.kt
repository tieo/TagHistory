package io.github.tieo.taghistory.anisette

import android.content.Context
import io.github.tieo.taghistory.apple.http.HttpTransport
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The phone's Mac: Apple's macOS CoreADI from the APK's assets, and the
 * machine file in app-private storage, which Android excludes from backups
 * (allowBackup=false) so the identity never leaves the phone.
 */
fun androidAnisetteProvider(context: Context, http: HttpTransport): AnisetteProvider {
    val app = context.applicationContext
    return MacAnisetteProvider(
        openAdi = { hostUuid, store ->
            val image = app.assets.open(COREADI_ASSET).use { it.readBytes() }
            JniCoreAdi(image, hostUuid, store)
        },
        storage = FileMachineStorage(File(app.filesDir, "anisette/machine.json")),
        http = http,
        nativeVersion = JniCoreAdi::version,
        blockingContext = Dispatchers.IO,
    )
}

/** Apple's CoreADI 4.19, the x86_64 slice from iTunes 12.8.3 for Mac. */
private const val COREADI_ASSET = "coreadi/CoreADI.x86_64"
