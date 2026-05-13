package io.github.tieo.taghistory.nearby

import android.content.Context
import android.os.Build

/**
 * Cheap probe: does this device have UWB hardware Android can talk to?
 * Used to gate the "Precision finding" affordance in the Nearby UI so
 * we don't dangle a button that does nothing on phones without an
 * NXP/Qualcomm UWB chip (most non-flagship Android devices).
 *
 * Note: presence of the system feature only means Android can drive a
 * UWB radio — it does not mean an AirTag will accept a ranging session
 * from us. The full negotiation (CCC/FiRa session config, derived keys
 * over Apple's GATT characteristic) is task #28.
 */
object UwbCapability {
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return context.packageManager.hasSystemFeature("android.hardware.uwb")
    }
}
