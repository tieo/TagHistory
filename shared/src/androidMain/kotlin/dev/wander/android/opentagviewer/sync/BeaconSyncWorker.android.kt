package io.github.tieo.taghistory.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.tieo.taghistory.data.model.UserSettings
import java.util.concurrent.TimeUnit

/**
 * Thin WorkManager shim over [BeaconSyncOrchestrator]. Replaces the Java
 * `BackgroundSyncWorker` + `BackgroundSyncScheduler`.
 *
 * Android-specific because WorkManager is. The orchestrator itself is
 * platform-agnostic and gets wired up by [BeaconSyncWorkerFactory] (which
 * the host composes with its DI container / Application class).
 */
class BeaconSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val orchestrator = runCatching { orchestratorProvider?.invoke(applicationContext) }
            .getOrNull()
        if (orchestrator == null) {
            Log.w(TAG, "BeaconSyncWorker fired without orchestratorProvider wired; skipping")
            return Result.success()
        }
        return when (val outcome = orchestrator.run()) {
            is BeaconSyncOrchestrator.Outcome.Success -> {
                Log.i(
                    TAG,
                    "Background sync complete: persisted ${outcome.persistedReports} reports across ${outcome.beaconCount} beacons",
                )
                Result.success()
            }
            is BeaconSyncOrchestrator.Outcome.Retry -> {
                Log.w(TAG, "Background sync transient failure; retrying", outcome.cause)
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "BeaconSyncWorker"
        const val UNIQUE_WORK_NAME = "background_location_sync"
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val MIN_INTERVAL_MINUTES = 15

        /**
         * The host sets this once at app startup — it's the only way the
         * stateless WorkManager-provided worker can reach back into the
         * DI graph without subclassing WorkerFactory. Left null in unit
         * tests.
         */
        @Volatile
        var orchestratorProvider: ((Context) -> BeaconSyncOrchestrator)? = null

        fun resolveIntervalMinutes(settings: UserSettings): Int {
            val configured = settings.backgroundSyncIntervalMinutes ?: DEFAULT_INTERVAL_MINUTES
            return maxOf(MIN_INTERVAL_MINUTES, configured)
        }

        fun apply(context: Context, settings: UserSettings) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!settings.isBackgroundSyncEnabled()) {
                wm.cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val intervalMinutes = resolveIntervalMinutes(settings)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BeaconSyncWorker>(
                intervalMinutes.toLong(), TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
