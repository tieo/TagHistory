package io.github.tieo.taghistory.data.model

import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions

/**
 * Aggregated view over the three beacon-shaped tables — what the devices
 * list screen actually cares about. `userBeaconOptions` is nullable
 * because the user doesn't have to customize every beacon.
 */
data class BeaconData(
    val beaconId: String,
    val ownedBeaconInfo: OwnedBeacons?,
    val beaconNamingRecord: BeaconNamingRecord?,
    val userBeaconOptions: UserBeaconOptions?,
)
