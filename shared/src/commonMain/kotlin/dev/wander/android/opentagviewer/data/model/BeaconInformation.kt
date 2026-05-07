package io.github.tieo.taghistory.data.model

/**
 * High-level view of a beacon assembled by parsing its two plist blobs
 * (`BeaconNamingRecord/<id>/<rec-id>.plist` + `OwnedBeacons/<id>.plist`).
 *
 * No `batteryLevel` field: Apple's offline-finding reports (the only data
 * this app receives) don't carry battery. The plist has a `batteryLevel`
 * int but it's a pairing-time snapshot that never updates, so surfacing
 * it would lie to the user. Live battery requires BLE proximity scanning
 * of the tag's advertisement — a separate capability we don't have.
 */
data class BeaconInformation(
    val beaconId: String,
    // ----- BeaconNamingRecord -----
    val namingRecordId: String?,
    val originalName: String?,
    val originalEmoji: String?,
    val namingRecordCreationTime: Long?,
    val namingRecordModifiedTime: Long?,
    val namingRecordModifiedByDevice: String?,
    // ----- OwnedBeacon -----
    val model: String?,
    val pairingDate: Long?,
    val productId: Int?,
    val stableIdentifier: String?,
    val systemVersion: String?,
    val vendorId: Int?,
    val hasPrivateKey: Boolean,
    // ----- User overrides -----
    val userOverrideName: String?,
    val userOverrideEmoji: String?,
) {
    /** Prefers a user-supplied override, then the Apple-side name, then a UUID snippet. */
    val displayName: String
        get() = userOverrideName?.takeIf { it.isNotBlank() }
            ?: originalName?.takeIf { it.isNotBlank() }
            ?: beaconId.take(8)

    /** Null when the user hasn't picked an emoji AND Apple has none. */
    val displayEmoji: String?
        get() = userOverrideEmoji?.takeIf { it.isNotBlank() }
            ?: originalEmoji?.takeIf { it.isNotBlank() }
}
