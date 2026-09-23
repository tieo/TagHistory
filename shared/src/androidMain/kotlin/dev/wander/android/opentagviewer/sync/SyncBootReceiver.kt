package io.github.tieo.taghistory.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the Doze-proof sync alarm after a reboot. WorkManager restores its own
 * periodic job on boot, but the AlarmManager alarm does not survive a reboot, so
 * without this the overnight backstop would silently stop after every restart.
 */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // BOOT_COMPLETED arrives after the user unlocks, when the credential
        // storage the prefs live in is readable. QUICKBOOT_POWERON is the same
        // event on devices with a fast-boot mode.
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            SyncAlarmScheduler.rescheduleFromPrefs(context)
        }
    }
}
