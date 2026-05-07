package io.github.tieo.taghistory.data.model

import io.github.tieo.taghistory.db.BeaconNamingRecord
import io.github.tieo.taghistory.db.Import
import io.github.tieo.taghistory.db.OwnedBeacons

/**
 * One logical "import" operation — the parent Import row plus the child
 * OwnedBeacons + BeaconNamingRecord rows that should be linked to it. The
 * repository sets each child's `importId` to the DB-assigned parent id
 * before persisting.
 */
data class ImportData(
    val anImport: Import,
    val ownedBeacons: List<OwnedBeacons>,
    val beaconNamingRecords: List<BeaconNamingRecord>,
)
