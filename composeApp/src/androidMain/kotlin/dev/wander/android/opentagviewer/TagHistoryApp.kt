package io.github.tieo.taghistory

import android.app.Application
import android.os.Build
import android.util.Log
import io.github.tieo.taghistory.host.AndroidAppHost
import io.github.tieo.taghistory.sync.BeaconSyncWorker
import io.github.tieo.taghistory.sync.SyncLog
import org.maplibre.android.MapLibre

class TagHistoryApp : Application() {
    /**
     * Process-wide host, shared between the UI (MainActivity) and the
     * headless WorkManager process. Lazy so a normal launch doesn't pay
     * the DB-open cost until something asks for it.
     */
    val host by lazy { AndroidAppHost.create(this) }

    override fun onCreate() {
        super.onCreate()
        // Route ALL SyncLog output to logcat. println() -> System.out is
        // silently dropped on some Android builds (was on this device), so
        // network errors like HTTP 503 never showed up in `adb logcat`.
        // android.util.Log always lands. Set this FIRST so nothing logs to
        // the void before the sink is installed.
        SyncLog.sink = { line -> Log.i("TagHistory", line) }

        // Pre-init MapLibre native libs so the first MapView open doesn't pay
        // the cold native-load cost.
        MapLibre.getInstance(this)

        // Wire the worker's orchestrator here, not in MainActivity. When the
        // app is unopened for days WorkManager cold-starts only the process
        // (Application.onCreate), never the Activity. Wiring it there left the
        // provider null on every headless fire, so the worker no-opped and
        // data went stale until the next manual open.
        BeaconSyncWorker.orchestratorProvider = { host.createSyncOrchestrator() }

        val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        Log.i(
            "TagHistory",
            "[SyncLog] env:" +
                " app_version=${pkg?.versionName ?: "?"}" +
                " app_version_code=${pkg?.longVersionCode?.toString() ?: "?"}" +
                " device=${Build.MANUFACTURER} ${Build.MODEL}" +
                " android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
                " abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
    }
}
