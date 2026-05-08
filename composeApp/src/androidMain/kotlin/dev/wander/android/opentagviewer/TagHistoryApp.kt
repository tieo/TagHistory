package io.github.tieo.taghistory

import android.app.Application
import android.os.Build
import org.maplibre.android.MapLibre

class TagHistoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pre-init MapLibre native libs so the first MapView open doesn't pay
        // the cold native-load cost.
        MapLibre.getInstance(this)

        val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        io.github.tieo.taghistory.sync.SyncLog.setEnvironment(
            mapOf(
                "app_version" to (pkg?.versionName ?: "?"),
                "app_version_code" to (pkg?.longVersionCode?.toString() ?: "?"),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "?"),
            ),
        )
    }
}
