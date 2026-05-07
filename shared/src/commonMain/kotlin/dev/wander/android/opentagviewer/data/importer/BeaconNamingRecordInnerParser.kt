package io.github.tieo.taghistory.data.importer

import io.github.tieo.taghistory.apple.plist.NSKeyedArchive
import io.github.tieo.taghistory.apple.plist.PlistValue

/**
 * Decodes the nested `cloudKitMetadata` blob that lives inside each
 * `BeaconNamingRecord/<id>/<rec-id>.plist`.
 *
 * Apple stores it as a `<data>` node whose payload is an
 * `NSKeyedArchiver`-serialized `CKRecord`. We don't need the full record
 * — only the three timestamps/device fields the legacy Python helper
 * extracted for the device-info screen.
 *
 * Returns `null` if the blob is missing, malformed, or the archive lacks
 * the keys. No exceptions bubble up — callers shouldn't have to care.
 */
object BeaconNamingRecordInnerParser {

    data class Metadata(
        val creationTime: Long?,
        val modifiedTime: Long?,
        val modifiedByDevice: String?,
    )

    fun parse(outerDict: PlistValue.Dict): Metadata? {
        val blob = outerDict.data("cloudKitMetadata") ?: return null
        val archive = runCatching { NSKeyedArchive.parse(blob) }.getOrNull() as? PlistValue.Dict
            ?: return null
        val ctime = archive["RecordCtime"] as? PlistValue.Date
        val mtime = archive["RecordMtime"] as? PlistValue.Date
        val modifiedBy = archive.string("ModifiedByDevice")

        if (ctime == null && mtime == null && modifiedBy == null) return null
        return Metadata(
            creationTime = ctime?.epochMillis,
            modifiedTime = mtime?.epochMillis,
            modifiedByDevice = modifiedBy,
        )
    }
}
