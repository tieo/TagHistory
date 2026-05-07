package io.github.tieo.taghistory.ui.map

import android.content.Context
import org.maplibre.android.maps.Style

private fun Context.readAsset(path: String): String =
    assets.open(path).bufferedReader().use { it.readText() }

/**
 * CartoDB Voyager — vector OSM-derived style with **POI labels, landmarks,
 * parks, transit**, not just streets. Free, no API key, © CARTO + © OSM
 * attribution (listed on Information screen). OpenFreeMap Liberty was
 * sparser so user couldn't see what neighbourhoods / shops their tags
 * were near — Voyager fixes that while staying free.
 */
private const val LIGHT_STYLE_URL =
    "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"

/**
 * CartoDB Dark Matter vector. Sharper than the raster variant we shipped
 * first, perceivably less-dark thanks to crisp labels.
 */
private const val DARK_STYLE_URL =
    "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

/**
 * Esri World Imagery — raster tiles, free without API key up to reasonable
 * quotas. Wrapped in a minimal MapLibre style so it loads through the same
 * setStyle(fromJson) pipeline as every other basemap.
 */
private val SATELLITE_STYLE_JSON = """
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

/**
 * Loads the style synchronously from the bundled asset when available so
 * the first map open doesn't pay a remote fetch. Sprite + glyph URLs in
 * those JSONs still point at the CDN, which MapLibre fetches on demand
 * and caches across runs.
 */
fun Style.Builder.fromBasemap(basemap: MapBasemap, context: Context? = null): Style.Builder =
    when (basemap) {
        MapBasemap.LIGHT -> if (context != null) fromJson(context.readAsset("styles/voyager.json"))
                           else fromUri(LIGHT_STYLE_URL)
        MapBasemap.DARK -> if (context != null) fromJson(context.readAsset("styles/dark-matter.json"))
                          else fromUri(DARK_STYLE_URL)
        MapBasemap.SATELLITE -> fromJson(SATELLITE_STYLE_JSON)
    }
