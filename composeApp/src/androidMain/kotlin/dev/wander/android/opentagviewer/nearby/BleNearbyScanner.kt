package io.github.tieo.taghistory.nearby

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android BLE wrapper around [NearbyMatcher]. Subscribes to the platform's
 * BluetoothLeScanner with an Apple-manufacturer-data filter, hands each
 * matching advertisement to the matcher and emits `NearbyEvent` flow items
 * for the UI.
 *
 * Doesn't do its own permission checking beyond a `hasScanPermission`
 * guard — Compose-side code is expected to request `BLUETOOTH_SCAN`
 * (Android 12+) or `ACCESS_FINE_LOCATION` (Android < 12) before calling
 * [observe].
 */
class BleNearbyScanner(
    private val context: Context,
    private val matcher: NearbyMatcher,
) {

    sealed class Event {
        data object Stopped : Event()
        data object MissingPermission : Event()
        data object BluetoothOff : Event()
        data class Hit(
            val beaconId: String,
            val keyType: String,
            val rssi: Int,
            val elapsedRealtimeMs: Long,
        ) : Event()
    }

    fun observe(): Flow<Event> = callbackFlow {
        if (!hasScanPermission()) {
            trySend(Event.MissingPermission); awaitClose { }; return@callbackFlow
        }
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = mgr?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            trySend(Event.BluetoothOff); awaitClose { }; return@callbackFlow
        }
        matcher.primeAt()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                process(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) process(r)?.let { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
            }
        }

        val filter = ScanFilter.Builder()
            // Apple's manufacturer-id 0x004C; further-filter by the
            // FindMy offline-finding subtype byte 0x12 + length 0x19.
            .setManufacturerData(
                APPLE_COMPANY_ID,
                APPLE_FINDMY_FILTER,
                APPLE_FINDMY_FILTER_MASK,
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            trySend(Event.MissingPermission); awaitClose { }; return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
            trySend(Event.Stopped)
        }
    }

    /** Pulls trailing-22 from the manufacturer-data payload and asks the matcher. */
    private fun process(result: ScanResult): Event.Hit? {
        val mfg = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return null
        // Layout: [type=0x12][length=0x19][status][22 bytes pubkey tail][hint][battery]
        if (mfg.size < 1 + 1 + 1 + 22) return null
        if (mfg[0] != 0x12.toByte()) return null
        val tail = mfg.copyOfRange(3, 3 + 22)
        val hit = matcher.match(tail) ?: return null
        return Event.Hit(
            beaconId = hit.beaconId,
            keyType = hit.keyType,
            rssi = result.rssi,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        private const val TAG = "OTV/BleNearby"
        // Apple Bluetooth SIG manufacturer ID.
        const val APPLE_COMPANY_ID: Int = 0x004C
        // Match byte[0] = 0x12 (FindMy offline finding subtype) and
        // byte[1] = 0x19 (length 25). The mask runs across the first 2
        // bytes only; everything after is the rotating key tail.
        private val APPLE_FINDMY_FILTER: ByteArray = byteArrayOf(0x12, 0x19)
        private val APPLE_FINDMY_FILTER_MASK: ByteArray = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
    }
}
