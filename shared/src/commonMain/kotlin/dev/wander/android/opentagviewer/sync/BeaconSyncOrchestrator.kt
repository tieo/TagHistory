package io.github.tieo.taghistory.sync

import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.SyncOutcome
import io.github.tieo.taghistory.data.repo.SyncRun
import io.github.tieo.taghistory.data.repo.SyncRunRepository
import io.github.tieo.taghistory.data.repo.SyncTrigger
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.db.OwnedBeacons
// SyncEvent / SyncLog are in this same package; no import needed.

/**
 * Headless sync pass shared between the Android WorkManager worker and
 * the desktop/iOS equivalents. Replaces Java `BackgroundSyncWorker`
 * without the platform imports so the same logic runs under unit tests.
 */
class BeaconSyncOrchestrator(
    private val settingsRepo: UserSettingsRepository,
    private val authRepo: UserAuthRepository,
    private val beaconRepo: BeaconRepository,
    /**
     * Fetches the recent reports for a set of beacons. Production wires
     * this to `AppleReportsService.fetchLastReportsByBeacon` with a client
     * that closes over [account]; stub transports in tests replace it to
     * exercise the orchestration without HTTP.
     */
    private val fetchReports: ReportsFetcher,
    /**
     * Test seam — production wires to
     * `FindMyAccessory.fromPlist(content.encodeToByteArray())`. Returning
     * null causes the beacon to be skipped silently.
     */
    private val accessoryLoader: (OwnedBeacons) -> FindMyAccessory? = DefaultAccessoryLoader,
    /**
     * Upper bound on the adaptive fetch window. The actual window is derived
     * from how stale the cached data is (see [run]); this just caps a
     * post-downtime backfill. Tests can pin a small value.
     */
    private val maxHoursBack: Int = DEFAULT_MAX_HOURS_BACK,
    /** Lower bound on the adaptive window — the routine ~hourly cadence floor. */
    private val minHoursBack: Int = DEFAULT_MIN_HOURS_BACK,
    /**
     * Durable per-run log. Null in tests that don't assert on it. Every exit of
     * [run] writes one row here so the Sync-activity screen can show whether the
     * background actually ran and stored data over time.
     */
    private val syncRunRepo: SyncRunRepository? = null,
    /** Injectable clock for tests. */
    private val nowMs: () -> Long = { defaultNowMs() },
) {

    fun interface ReportsFetcher {
        suspend fun fetch(
            account: AppleAccount,
            accessoriesById: Map<String, FindMyAccessory>,
            hoursBack: Int,
        ): Map<String, List<BeaconLocationReport>>
    }

    sealed class Outcome {
        /** Sync completed (possibly a no-op). Never a retry signal. */
        data class Success(val persistedReports: Int, val beaconCount: Int) : Outcome()

        /** Transient failure (network, auth hiccup). Caller should retry. */
        data class Retry(val cause: Throwable) : Outcome()
    }

    suspend fun run(trigger: SyncTrigger = SyncTrigger.WORKER): Outcome {
        val startedAt = nowMs()

        // Record one row per exit and map the internal outcome to the
        // WorkManager Result the worker needs. SKIPPED (disabled / no auth /
        // nothing to do / throttled) is NOT a retry — it maps to Success so
        // WorkManager doesn't back off.
        fun finish(
            outcome: SyncOutcome,
            detail: String? = null,
            persisted: Int = 0,
            beacons: Int = 0,
            window: Int? = null,
            cause: Throwable? = null,
        ): Outcome {
            runCatching {
                syncRunRepo?.record(
                    SyncRun(
                        startedAtMs = startedAt,
                        trigger = trigger,
                        outcome = outcome,
                        detail = detail,
                        persistedReports = persisted,
                        beaconCount = beacons,
                        windowHours = window,
                        durationMs = nowMs() - startedAt,
                    ),
                )
            }
            return if (outcome == SyncOutcome.RETRY) {
                Outcome.Retry(cause ?: RuntimeException(detail ?: "retry"))
            } else {
                Outcome.Success(persistedReports = persisted, beaconCount = beacons)
            }
        }

        val settings = settingsRepo.getUserSettings()
        if (!settings.isBackgroundSyncEnabled()) {
            SyncLog.record(SyncEvent.Kind.INFO, "Background sync: disabled in settings, skipping")
            return finish(SyncOutcome.SKIPPED, "disabled in settings")
        }

        // Throttle overlapping triggers: the periodic WorkManager job and the
        // Doze-proof alarm both fire ~every interval and can land close
        // together. If an effective run happened within half the interval,
        // skip — the data is already fresh and a second Apple sweep just risks
        // throttling. A MANUAL press always runs.
        if (trigger != SyncTrigger.MANUAL) {
            val intervalMin = settings.backgroundSyncIntervalMinutes ?: DEFAULT_INTERVAL_MINUTES_FALLBACK
            val minGapMs = maxOf(MIN_THROTTLE_GAP_MINUTES, intervalMin / 2).toLong() * 60_000L
            val lastEffective = syncRunRepo?.lastEffectiveAtMs()
            if (lastEffective != null && startedAt - lastEffective < minGapMs) {
                val agoMin = (startedAt - lastEffective) / 60_000L
                SyncLog.record(SyncEvent.Kind.INFO, "Background sync: throttled, ${agoMin}m since last run")
                return finish(SyncOutcome.SKIPPED, "throttled: ${agoMin}m since last run")
            }
        }

        val userAuth = authRepo.getUserAuth()
        if (userAuth == null) {
            SyncLog.record(SyncEvent.Kind.INFO, "Background sync: no auth, skipping")
            return finish(SyncOutcome.SKIPPED, "no auth")
        }

        val beacons = beaconRepo.getAllBeacons()
        val accessoriesById = mutableMapOf<String, FindMyAccessory>()
        for (b in beacons) {
            val owned = b.ownedBeaconInfo ?: continue
            val accessory = try {
                accessoryLoader(owned)
            } catch (_: Exception) {
                null
            }
            if (accessory != null) accessoriesById[owned.id] = accessory
        }
        if (accessoriesById.isEmpty()) {
            SyncLog.record(SyncEvent.Kind.INFO, "Background sync: no loadable accessories, skipping")
            return finish(SyncOutcome.SKIPPED, "no loadable accessories")
        }

        // Adaptive window: fetch only as far back as the data is stale, instead
        // of a fixed window. Anchor on the newest cached fix across all
        // beacons:
        //  - empty DB (fresh install)            -> full maxHoursBack backfill
        //  - everything fresh (hourly operation) -> minHoursBack, cheap + fast
        //  - phone was off / app idle for a gap  -> window grows to cover it,
        //    capped at maxHoursBack (so "sometimes 7 days" happens on its own)
        // A perpetually-unseen beacon does NOT inflate the window: if any other
        // beacon is fresh the window stays small, and Apple has nothing newer
        // for the unseen one anyway.
        val newestFixMs = beaconRepo.getLastLocationsForAll().values.maxOfOrNull { it.timestamp }
        val effectiveHours = if (newestFixMs == null) {
            maxHoursBack
        } else {
            val gapHours = ((nowMs() - newestFixMs) / 3_600_000L).toInt() + WINDOW_MARGIN_HOURS
            gapHours.coerceIn(minHoursBack, maxHoursBack)
        }

        SyncLog.record(
            SyncEvent.Kind.START,
            "Background sync started (${accessoriesById.size} accessories, ${effectiveHours}h adaptive)",
            mapOf(
                "accessories" to accessoriesById.size.toString(),
                "hours_back" to effectiveHours.toString(),
                "newest_fix_age_h" to (newestFixMs?.let { ((nowMs() - it) / 3_600_000L).toString() } ?: "none"),
                "min_h" to minHoursBack.toString(),
                "max_h" to maxHoursBack.toString(),
            ),
        )
        return try {
            val account = rehydrateAccount(userAuth.data)
            val reports = fetchReports.fetch(account, accessoriesById, effectiveHours)
            beaconRepo.storeToLocationCache(reports)
            val total = reports.values.sumOf { it.size }
            SyncLog.record(
                SyncEvent.Kind.REFRESH_DONE,
                "Background sync persisted $total reports across ${reports.size} beacons",
                mapOf("persisted" to total.toString(), "beacons" to reports.size.toString()),
            )
            finish(
                SyncOutcome.SUCCESS,
                persisted = total,
                beacons = reports.size,
                window = effectiveHours,
            )
        } catch (e: Throwable) {
            // Throwable, not Exception: an anisette decrypt can OutOfMemoryError
            // (an Error). If that escaped, the worker died uninstrumented and
            // WorkManager just bumped run_attempt_count with no log of why.
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncLog.record(
                SyncEvent.Kind.RUNG_FAIL,
                "Background sync failed: ${e::class.simpleName}: ${e.message}",
                mapOf(
                    "error_class" to (e::class.simpleName ?: "?"),
                    "error_msg" to (e.message ?: "?"),
                ),
            )
            finish(
                SyncOutcome.RETRY,
                detail = "${e::class.simpleName}: ${e.message}",
                window = effectiveHours,
                cause = e,
            )
        }
    }

    private fun rehydrateAccount(encryptedBlob: ByteArray): AppleAccount {
        val plain = authRepo.decrypt(encryptedBlob).decodeToString()
        return AppleAccount.restoreFromJson(plain)
    }

    companion object {
        /** Cap on the adaptive window: a post-downtime backfill won't exceed a week. */
        const val DEFAULT_MAX_HOURS_BACK: Int = 24 * 7
        /** Floor on the adaptive window during normal ~hourly operation. */
        const val DEFAULT_MIN_HOURS_BACK: Int = 2
        /** Slack added to the measured staleness gap so we never just-miss a fix. */
        const val WINDOW_MARGIN_HOURS: Int = 1

        /** Used for the throttle only when no interval is set in settings. */
        const val DEFAULT_INTERVAL_MINUTES_FALLBACK: Int = 60

        /** Floor on the overlap-throttle gap regardless of interval. */
        const val MIN_THROTTLE_GAP_MINUTES: Int = 10

        @OptIn(kotlin.time.ExperimentalTime::class)
        private fun defaultNowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

        val DefaultAccessoryLoader: (OwnedBeacons) -> FindMyAccessory? = { owned ->
            owned.content?.let { FindMyAccessory.fromPlist(it.encodeToByteArray()) }
        }
    }
}
