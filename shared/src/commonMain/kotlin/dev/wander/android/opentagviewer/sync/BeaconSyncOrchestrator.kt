package io.github.tieo.taghistory.sync

import io.github.tieo.taghistory.apple.account.AppleAccount
import io.github.tieo.taghistory.apple.findmy.FindMyAccessory
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.data.repo.UserAuthRepository
import io.github.tieo.taghistory.data.repo.UserSettingsRepository
import io.github.tieo.taghistory.db.OwnedBeacons

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
    private val hoursBack: Int = DEFAULT_HOURS_BACK,
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

    suspend fun run(): Outcome {
        val settings = settingsRepo.getUserSettings()
        if (settings.backgroundSyncEnabled != true) {
            return Outcome.Success(persistedReports = 0, beaconCount = 0)
        }

        val userAuth = authRepo.getUserAuth()
            ?: return Outcome.Success(persistedReports = 0, beaconCount = 0)

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
            return Outcome.Success(persistedReports = 0, beaconCount = 0)
        }

        return try {
            val account = rehydrateAccount(userAuth.data)
            val reports = fetchReports.fetch(account, accessoriesById, hoursBack)
            beaconRepo.storeToLocationCache(reports)
            val total = reports.values.sumOf { it.size }
            Outcome.Success(persistedReports = total, beaconCount = reports.size)
        } catch (e: Exception) {
            Outcome.Retry(e)
        }
    }

    private fun rehydrateAccount(encryptedBlob: ByteArray): AppleAccount {
        val plain = authRepo.decrypt(encryptedBlob).decodeToString()
        return AppleAccount.restoreFromJson(plain)
    }

    companion object {
        const val DEFAULT_HOURS_BACK: Int = 24

        val DefaultAccessoryLoader: (OwnedBeacons) -> FindMyAccessory? = { owned ->
            owned.content?.let { FindMyAccessory.fromPlist(it.encodeToByteArray()) }
        }
    }
}
