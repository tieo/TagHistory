package io.github.tieo.taghistory.server

import io.github.tieo.taghistory.apple.plist.PlistValue
import io.github.tieo.taghistory.apple.plist.XmlPlist

/**
 * Reduces an OwnedBeacons accessory plist to the descriptive fields clients
 * display, so the key material that decrypts every location report never
 * leaves the server.
 *
 * It is an allow-list: a field a future client needs has to be added here
 * deliberately. `privateKey` survives only as an empty value, because clients
 * show whether a beacon has one but must not receive it.
 */
object KeyRedaction {
    private val DESCRIPTIVE_FIELDS = setOf(
        "identifier",
        "model",
        "pairingDate",
        "productId",
        "stableIdentifier",
        "systemVersion",
        "vendorId",
        "batteryLevel",
        "name",
        "emoji",
    )
    private const val PRIVATE_KEY = "privateKey"

    fun redactOwnedBeacon(xml: String?): String? {
        if (xml == null) return null
        val dict = runCatching { XmlPlist.parse(xml) }.getOrNull() as? PlistValue.Dict ?: return null
        val kept = LinkedHashMap<String, PlistValue>()
        for ((key, value) in dict.entries) {
            when (key) {
                in DESCRIPTIVE_FIELDS -> kept[key] = value
                PRIVATE_KEY -> kept[key] = PlistValue.Data(ByteArray(0))
            }
        }
        return XmlPlist.encode(PlistValue.Dict(kept)).decodeToString()
    }
}
