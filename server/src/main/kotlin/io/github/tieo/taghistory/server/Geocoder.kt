package io.github.tieo.taghistory.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Turns coordinates into a one-line address, or null when none is known. */
fun interface Geocoder {
    suspend fun reverse(latitude: Double, longitude: Double): String?
}

/**
 * Reverse geocoding through OpenStreetMap's Nominatim. Its usage policy
 * allows one request per second with an identifying User-Agent, so requests
 * are serialized and spaced; the caller caches results, which keeps the
 * volume to new places only.
 */
class NominatimGeocoder(
    private val http: HttpClient,
    private val endpoint: String = "https://nominatim.openstreetmap.org/reverse",
) : Geocoder {
    private val lock = Mutex()
    private var lastRequestMs = 0L

    override suspend fun reverse(latitude: Double, longitude: Double): String? = lock.withLock {
        val wait = lastRequestMs + MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastRequestMs = System.currentTimeMillis()
        val response = runCatching {
            http.get(endpoint) {
                parameter("format", "jsonv2")
                parameter("lat", latitude)
                parameter("lon", longitude)
                parameter("zoom", 18)
                parameter("addressdetails", 1)
                header("User-Agent", "TagHistory-server (self-hosted)")
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            ?: return null
        formatAddress(body)
    }

    companion object {
        private const val MIN_GAP_MS = 1_100L

        /**
         * "Street 12, 89073 Ulm", the shape of the first line Android's
         * Geocoder gives, falling back to Nominatim's full display name.
         */
        internal fun formatAddress(body: JsonObject): String? {
            val address = body["address"] as? JsonObject
            fun part(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
                address?.get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }
            val street = listOfNotNull(
                part("road", "pedestrian", "footway", "path", "square", "place"),
                part("house_number"),
            ).joinToString(" ").ifBlank { null }
            val locality = listOfNotNull(
                part("postcode"),
                part("city", "town", "village", "hamlet", "municipality", "suburb"),
            ).joinToString(" ").ifBlank { null }
            val line = listOfNotNull(street, locality).joinToString(", ")
            return line.ifBlank { body["display_name"]?.jsonPrimitive?.content }
        }
    }
}
