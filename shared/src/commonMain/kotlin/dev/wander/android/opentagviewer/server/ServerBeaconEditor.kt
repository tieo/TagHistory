package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.data.repo.BeaconEditor
import io.github.tieo.taghistory.data.repo.BeaconRepository
import io.github.tieo.taghistory.db.UserBeaconOptions

/**
 * Writes beacon edits to the server first, so a failed request leaves the
 * local copy unchanged instead of showing an edit the server never saw, then
 * mirrors the change locally.
 */
class ServerBeaconEditor(
    private val client: ServerClient,
    private val beaconRepo: BeaconRepository,
) : BeaconEditor {
    override suspend fun setOptions(options: UserBeaconOptions) {
        client.setBeaconOptions(
            BeaconOptionsDto(options.beacon_id, options.last_update, options.ui_name, options.ui_emoji),
        )
        beaconRepo.storeUserBeaconOptions(options)
    }

    override suspend fun remove(beaconId: String) {
        client.removeBeacon(beaconId)
        beaconRepo.markBeaconAsRemoved(beaconId)
    }
}
