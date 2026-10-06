package io.github.tieo.taghistory.ui.history

import io.github.tieo.taghistory.ui.map.MapBasemap

/**
 * Track line and endpoint color per basemap (ARGB), shared by every history
 * map. A single material primary does not read on light streets, dark
 * matter and satellite alike, so each gets a tone picked against its tiles:
 * deep teal on the pastel light style, warm coral on near-black, hot magenta
 * through busy aerial imagery.
 */
fun historyLineColor(basemap: MapBasemap): Long = when (basemap) {
    MapBasemap.LIGHT -> 0xFF0F766E
    MapBasemap.DARK -> 0xFFFB923C
    MapBasemap.SATELLITE -> 0xFFEC4899
}

/** Selected point halo and accuracy fill, paired with [historyLineColor]. */
fun historySelectedColor(basemap: MapBasemap): Long = when (basemap) {
    MapBasemap.LIGHT -> 0xFFB45309
    MapBasemap.DARK -> 0xFFFCD34D
    MapBasemap.SATELLITE -> 0xFF38BDF8
}

/** `#rrggbb` for an ARGB color, for map style JSON. */
fun cssHex(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
