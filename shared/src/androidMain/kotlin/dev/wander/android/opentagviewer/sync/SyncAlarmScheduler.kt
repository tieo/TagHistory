package io.github.tieo.taghistory.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Doze-proof backstop for the periodic [BeaconSyncWorker]. A plain
 * PeriodicWorkRequest is deferred to Doze maintenance windows that stretch to
 * many hours overnight, so a stationary phone can miss 5-8 hourly runs in a row
 * and only backfill when the app is next opened.
 *
 * This schedules a single [AlarmManager.setAndAllowWhileIdle] alarm one interval
 * out. That flavour fires even in Doze (the OS throttles it to at most ~once per
 * 9-15 min in deep Doze, comfortably under an hourly cadence) and needs NO
 * special permission — unlike the exact-alarm variants, which require the
 * Play-restricted USE_EXACT_ALARM or the user-revocable SCHEDULE_EXACT_ALARM.
 * We don't need second precision, so inexact-allow-while-idle is the right tool.
 *
 * A one-shot alarm doesn't repeat and doesn't survive reboot, so the interval
 * and enabled flag are mirrored into SharedPreferences: [SyncAlarmReceiver]
 * re-arms the next one after each fire, and [SyncBootReceiver] re-arms after a
 * reboot — both able to run in a cold process without touching the DB.
 */
object SyncAlarmScheduler {
    const val ACTION = "io.github.tieo.taghistory.action.SYNC_ALARM"
    private const val PREFS = "sync_alarm"
    private const val KEY_INTERVAL = "interval_min"
    private const val KEY_ENABLED = "enabled"
    private const val REQUEST_CODE = 0x7A61 // "za"

    /** Arm the next alarm one interval out and persist the schedule. */
    fun schedule(context: Context, intervalMinutes: Int) {
        val ctx = context.applicationContext
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INTERVAL, intervalMinutes)
            .putBoolean(KEY_ENABLED, true)
            .apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + intervalMinutes.toLong() * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(ctx))
    }

    /**
     * Re-arm from persisted state in a cold process (after a fire or a reboot).
     * No-op when background sync is disabled, so a stale prefs entry can't keep
     * waking the phone forever.
     */
    fun rescheduleFromPrefs(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        schedule(ctx, prefs.getInt(KEY_INTERVAL, 60))
    }

    /** Cancel any pending alarm and mark the schedule disabled. */
    fun disable(context: Context) {
        val ctx = context.applicationContext
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(ctx))
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, SyncAlarmReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
