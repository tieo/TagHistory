package io.github.tieo.taghistory.ui.map

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// Map pieces every client draws alike, on Android and on the web: basemap
// styles, camera timing, the accuracy-circle geometry and colours.

/** Three basemap modes the user cycles through on the map. */
enum class MapBasemap { LIGHT, DARK, SATELLITE }

/** Default zoom when focusing a tag: street level. */
const val DEFAULT_FOCUS_ZOOM = 16.0

/** A saved camera below this zoom (world view) is not worth restoring. */
const val MEANINGFUL_ZOOM_FLOOR = 6.0

private const val CAMERA_MIN_MS = 300
private const val CAMERA_MAX_MS = 1200
private const val CAMERA_LERP_KM = 100.0

fun cameraDurationFor(distMeters: Double): Int {
    val km = distMeters / 1000.0
    val frac = (km / CAMERA_LERP_KM).coerceIn(0.0, 1.0)
    return (CAMERA_MIN_MS + frac * (CAMERA_MAX_MS - CAMERA_MIN_MS)).toInt()
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = (lat2 - lat1) * PI / 180.0
    val dLon = (lon2 - lon1) * PI / 180.0
    val a = sin(dLat / 2).let { it * it } +
        cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
        sin(dLon / 2).let { it * it }
    val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return r * c
}

/**
 * CartoDB Voyager: vector OSM style with POI labels, landmarks, parks and
 * transit, so a tag can be placed near the shop or street it is at. Free, no
 * API key, attribution to CARTO and OSM.
 */
const val LIGHT_STYLE_URL = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"

/** CartoDB Dark Matter, the vector variant for crisp labels. */
const val DARK_STYLE_URL = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

/**
 * Esri World Imagery raster tiles, free without an API key at reasonable
 * volume, wrapped in a minimal style so it loads like every other basemap.
 */
val SATELLITE_STYLE_JSON = """
{
  "version": 8,
  "name": "Esri World Imagery",
  "sources": {
    "esri-imagery": {
      "type": "raster",
      "tiles": [
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
      ],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© Esri, Maxar, Earthstar Geographics, GIS User Community"
    }
  },
  "layers": [
    {"id": "bg", "type": "background", "paint": {"background-color": "#000000"}},
    {"id": "base", "type": "raster", "source": "esri-imagery"}
  ]
}
""".trimIndent()

/** Accuracy circle colours, shared so both maps tint them alike. */
const val ACCURACY_COLOR = "#8B2A2A"

/** WGS-84 circle approximation as a closed ring of (longitude, latitude). */
fun circleRing(
    centerLat: Double,
    centerLon: Double,
    radiusM: Double,
    segments: Int,
): List<Pair<Double, Double>> {
    val earthR = 6_371_000.0
    val lat0 = centerLat * PI / 180.0
    val lon0 = centerLon * PI / 180.0
    val angularDist = radiusM / earthR
    val out = ArrayList<Pair<Double, Double>>(segments + 1)
    for (i in 0..segments) {
        val bearing = 2.0 * PI * i / segments
        val newLat = asin(
            sin(lat0) * cos(angularDist) +
                cos(lat0) * sin(angularDist) * cos(bearing),
        )
        val newLon = lon0 + atan2(
            sin(bearing) * sin(angularDist) * cos(lat0),
            cos(angularDist) - sin(lat0) * sin(newLat),
        )
        out += (newLon * 180.0 / PI) to (newLat * 180.0 / PI)
    }
    return out
}
