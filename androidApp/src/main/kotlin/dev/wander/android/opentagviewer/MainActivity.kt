package io.github.tieo.taghistory

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.tieo.taghistory.host.AndroidAppHost
import kotlinx.coroutines.CompletableDeferred

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
            pendingZipPick?.complete(uri)
            pendingZipPick = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val host = AndroidAppHost.create(applicationContext)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
        val onImport = host.createImportCallback { launchZipPicker() }
        setContent {
            App(factories = host.buildAppFactories(versionName, onImport = onImport))
        }
    }

    private suspend fun launchZipPicker(): Uri? {
        val deferred = CompletableDeferred<Uri?>()
        pendingZipPick = deferred
        pickZipLauncher.launch("application/zip")
        return deferred.await()
    }
}
