package io.github.tieo.taghistory.ui.map

import android.content.Context
import org.maplibre.android.maps.Style

private fun Context.readAsset(path: String): String =
    assets.open(path).bufferedReader().use { it.readText() }

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
