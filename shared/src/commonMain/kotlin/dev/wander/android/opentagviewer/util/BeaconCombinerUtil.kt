package io.github.tieo.taghistory.util

import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconLocationReport
import io.github.tieo.taghistory.data.model.ImportData
import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.OwnedBeacons
import io.github.tieo.taghistory.db.UserBeaconOptions

/**
 * Joins the three beacon-related tables into flat [BeaconData] rows the
 * UI consumes, and dedups overlapping report lists by content hash.
 *
 * The FindMy export uses a two-id scheme:
 *  - [OwnedBeacons.id] is the beacon's stable UUID (what Apple returns in
 *    location reports, and what [UserBeaconOptions.beacon_id] points to).
 *  - [BeaconNamingRecord.id] is a separate record UUID — the naming row
 *    references the beacon via an `associatedBeacon` field inside its
 *    plist content. Joining on `naming.id == owned.id` silently drops the
 *    hardware side of every row.
 *
 * We iterate over owned beacons (the authoritative set) and match each
 * one to its naming record via the plist-embedded `associatedBeacon`.
 */
object BeaconCombinerUtil {

    fun combine(
        ownedBeacons: List<OwnedBeacons>,
        beaconNamingRecords: List<BeaconNamingRecord>,
        userBeaconOptions: List<UserBeaconOptions>,
    ): List<BeaconData> {
        val namingByBeacon = beaconNamingRecords.associateBy { associatedBeaconId(it) }
        val optionsById = userBeaconOptions.associateBy { it.beacon_id }
        return ownedBeacons.map { owned ->
            BeaconData(
                beaconId = owned.id,
                ownedBeaconInfo = owned,
                beaconNamingRecord = namingByBeacon[owned.id],
                userBeaconOptions = optionsById[owned.id],
            )
        }
    }

    /**
     * Beacon UUID that a naming record references. For real FindMy exports
     * this lives inside the plist as `associatedBeacon`. Fallback to the
     * row id for test doubles and any future export variant that happens
     * to already key on the beacon's UUID.
     */
    private fun associatedBeaconId(naming: BeaconNamingRecord): String {
        val content = naming.content ?: return naming.id
        val parsed = runCatching { XmlPlist.parse(content) }.getOrNull()
        return (parsed as? PlistValue.Dict)?.string("associatedBeacon") ?: naming.id
    }

    fun combine(beaconData: ImportData): List<BeaconData> =
        combine(beaconData.ownedBeacons, beaconData.beaconNamingRecords, emptyList())

    /**
     * Merge two overlapping report lists, prefer the second's value on
     * hash collision (same content → DB-side replacement semantics), sort
     * ascending by timestamp.
     */
    fun combineAndSort(
        beaconId: String,
        first: List<BeaconLocationReport>,
        second: List<BeaconLocationReport>,
    ): List<BeaconLocationReport> {
        val distinct = linkedMapOf<String, BeaconLocationReport>()
        for (r in first) {
            distinct[BeaconLocationReportHasher.getSha256HashFor(beaconId, r)] = r
        }
        for (r in second) {
            distinct[BeaconLocationReportHasher.getSha256HashFor(beaconId, r)] = r
        }
        return distinct.values.sortedBy { it.timestamp }
    }
}
