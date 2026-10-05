package io.github.tieo.taghistory.data.repo

import io.github.tieo.taghistory.db.UserBeaconOptions

/**
 * Where a user's edit to a beacon (a rename, a removal) is made. The
 * standalone app owns its data and writes the local database; a server
 * client writes the server, which owns the data, and mirrors the change
 * locally so the screen updates without waiting for the next pull.
 */
interface BeaconEditor {
    suspend fun setOptions(options: UserBeaconOptions)
    suspend fun remove(beaconId: String)
}

class LocalBeaconEditor(private val beaconRepo: BeaconRepository) : BeaconEditor {
    override suspend fun setOptions(options: UserBeaconOptions) = beaconRepo.storeUserBeaconOptions(options)
    override suspend fun remove(beaconId: String) = beaconRepo.markBeaconAsRemoved(beaconId)
}
