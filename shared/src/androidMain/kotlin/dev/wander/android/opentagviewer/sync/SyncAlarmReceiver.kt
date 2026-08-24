package io.github.tieo.taghistory.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tieo.taghistory.data.repo.SyncTrigger

/**
 * Fires when the Doze-proof alarm goes off. Enqueues one expedited sync (tagged
 * [SyncTrigger.ALARM] so the run log can tell it apart from the periodic
 * WorkManager job) and immediately re-arms the next alarm, since a one-shot
 * alarm doesn't repeat.
 */
class SyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SyncAlarmScheduler.ACTION) return
        BeaconSyncWorker.enqueueOneShot(context, SyncTrigger.ALARM)
        SyncAlarmScheduler.rescheduleFromPrefs(context)
    }
}
