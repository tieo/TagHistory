package io.github.tieo.taghistory

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred

private const val TAG = "OTV/MainAct"

class MainActivity : ComponentActivity() {

    /**
     * One-shot holder set when [launchZipPicker] fires and completed by
     * the registered launcher below. Guarded against concurrent
     * imports — a second invocation while one is pending just waits on
     * the same deferred.
     */
    private var pendingZipPick: CompletableDeferred<Uri?>? = null

    private val pickZipLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            Log.i(TAG, "pickZipLauncher result uri=$uri")
            pendingZipPick?.complete(uri)
            pendingZipPick = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val host = (application as TagHistoryApp).host
        // Schedule (or cancel, per settings) the WorkManager background
        // sync, and keep the schedule following settings changes. Without
        // this the periodic fetch never runs and the app reopens onto
        // data as old as its last foreground session.
        host.startBackgroundSync(lifecycleScope)
        // On a fresh launch (not a rotation/recreation), prompt for the
        // battery-optimization exemption if we still need it — otherwise Doze
        // parks the periodic sync worker for hours. Guarded by
        // savedInstanceState == null so it doesn't re-pop on config changes.
        if (savedInstanceState == null) host.promptBatteryExemptionIfNeeded()
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
        val onImport = host.createImportCallback { launchZipPicker() }
        val onImportPreview = host.createImportPreviewCallback { launchZipPicker() }
        setContent {
            App(
                factories = host.buildAppFactories(
                    versionName,
                    onImport = onImport,
                    onImportPreview = onImportPreview,
                ),
            )
        }
    }

    private suspend fun launchZipPicker(): Uri? {
        Log.i(TAG, "launchZipPicker: launching GetContent('application/zip')")
        val deferred = CompletableDeferred<Uri?>()
        pendingZipPick = deferred
        pickZipLauncher.launch("application/zip")
        return deferred.await()
    }
}
