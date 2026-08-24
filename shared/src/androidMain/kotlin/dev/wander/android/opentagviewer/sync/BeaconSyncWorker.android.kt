package io.github.tieo.taghistory.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.tieo.taghistory.data.model.UserSettings
import io.github.tieo.taghistory.data.repo.SyncTrigger
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
        val trigger = runCatching {
            SyncTrigger.valueOf(inputData.getString(KEY_TRIGGER) ?: SyncTrigger.WORKER.name)
        }.getOrDefault(SyncTrigger.WORKER)
        return when (val outcome = orchestrator.run(trigger)) {
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
        const val ONESHOT_WORK_NAME = "background_location_sync_oneshot"
        const val KEY_TRIGGER = "trigger"
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
                SyncAlarmScheduler.disable(context)
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
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.WORKER.name))
                // LINEAR, not the default EXPONENTIAL: a run that fails because
                // the network/DNS was down at fire time should retry in a fixed
                // ~15 min, not double toward WorkManager's 5 h cap and leave the
                // map stale for half a day.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            // Doze backstop: the periodic job above is deferred for hours in
            // deep Doze, so pair it with an allow-while-idle alarm that fires
            // overnight. The orchestrator throttles the overlap so the two
            // triggers don't double-fetch.
            SyncAlarmScheduler.schedule(context, intervalMinutes)
        }

        /**
         * Enqueue a single expedited sync now, tagged with [trigger]. Used by
         * [SyncAlarmReceiver] when the Doze-proof alarm fires. KEEP so a burst
         * of alarms can't stack duplicate work.
         */
        fun enqueueOneShot(context: Context, trigger: SyncTrigger) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<BeaconSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(ONESHOT_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            SyncAlarmScheduler.disable(context)
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
