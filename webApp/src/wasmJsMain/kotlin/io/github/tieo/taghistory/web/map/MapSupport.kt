package io.github.tieo.taghistory.web.map

import io.github.tieo.taghistory.ui.map.DARK_STYLE_URL
import io.github.tieo.taghistory.ui.map.LIGHT_STYLE_URL
import io.github.tieo.taghistory.ui.map.MapBasemap
import io.github.tieo.taghistory.ui.map.SATELLITE_STYLE_JSON

/** MapLibre style (URL or JSON) for a basemap. */
fun styleFor(basemap: MapBasemap): String = when (basemap) {
    MapBasemap.LIGHT -> LIGHT_STYLE_URL
    MapBasemap.DARK -> DARK_STYLE_URL
    MapBasemap.SATELLITE -> SATELLITE_STYLE_JSON
}

/** The next basemap in the cycle the map's basemap button steps through. */
fun MapBasemap.next(): MapBasemap = MapBasemap.entries[(ordinal + 1) % MapBasemap.entries.size]

/** A JSON string literal for [s]. */
fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
    }
    append('"')
}
