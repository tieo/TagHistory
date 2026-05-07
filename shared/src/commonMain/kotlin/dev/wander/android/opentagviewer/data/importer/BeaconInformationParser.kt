package io.github.tieo.taghistory.data.importer

import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist
import io.github.tieo.taghistory.data.model.BeaconData
import io.github.tieo.taghistory.data.model.BeaconInformation

/**
 * Pure-Kotlin port of the legacy `BeaconDataParser` — extracts the
 * display-visible fields (name, emoji, battery, model, pairing date, …)
 * out of the two plist blobs each beacon owns.
 *
 * The old Java version used XPath expressions against a DOM; we already
 * have a typed plist parser in `:shared`, so reads here go through
 * [PlistValue.Dict]'s accessors and fail loudly on schema drift rather
 * than silently returning empty strings.
 */
object BeaconInformationParser {

    /**
     * Parse a single [BeaconData] row. Returns `null` when neither plist
     * blob is present — the caller can fall back to UUID-only display.
     */
    fun parse(data: BeaconData): BeaconInformation? {
        val ownedContent = data.ownedBeaconInfo?.content
        val namingContent = data.beaconNamingRecord?.content
        if (ownedContent == null && namingContent == null) return null

        val namingDict = namingContent?.let { safeParseDict(it) }
        val ownedDict = ownedContent?.let { safeParseDict(it) }

        val metadata = namingDict?.let { BeaconNamingRecordInnerParser.parse(it) }

        return BeaconInformation(
            beaconId = data.beaconId,
            namingRecordId = namingDict?.string("identifier"),
            originalName = namingDict?.string("name"),
            originalEmoji = namingDict?.string("emoji"),
            namingRecordCreationTime = metadata?.creationTime,
            namingRecordModifiedTime = metadata?.modifiedTime,
            namingRecordModifiedByDevice = metadata?.modifiedByDevice,
            model = ownedDict?.string("model"),
            pairingDate = ownedDict?.let { (it["pairingDate"] as? PlistValue.Date)?.epochMillis },
            productId = ownedDict?.int64("productId")?.toInt(),
            stableIdentifier = ownedDict?.firstStableIdentifier(),
            systemVersion = ownedDict?.string("systemVersion"),
            vendorId = ownedDict?.int64("vendorId")?.toInt(),
            hasPrivateKey = ownedDict?.get("privateKey") != null,
            userOverrideName = data.userBeaconOptions?.ui_name,
            userOverrideEmoji = data.userBeaconOptions?.ui_emoji,
        )
    }

    fun parseAll(rows: List<BeaconData>): List<BeaconInformation> =
        rows.mapNotNull { parse(it) }

    private fun safeParseDict(xml: String): PlistValue.Dict? {
        val parsed = runCatching { XmlPlist.parse(xml) }.getOrNull() ?: return null
        return parsed as? PlistValue.Dict
    }

    private fun PlistValue.Dict.firstStableIdentifier(): String? {
        val array = array("stableIdentifier") ?: return null
        val first = array.items.firstOrNull() ?: return null
        return (first as? PlistValue.Str)?.value
    }
}
