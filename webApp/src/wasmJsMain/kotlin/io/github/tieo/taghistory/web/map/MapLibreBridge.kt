@file:JsModule("./map/maplibre-bridge.mjs")

package io.github.tieo.taghistory.web.map

import web.html.HTMLElement

// Bindings to map/maplibre-bridge.mjs; see that file for what each does.

internal external interface MapHandle : JsAny

internal external fun mount(
    container: HTMLElement,
    lat: Double,
    lon: Double,
    zoom: Double,
    style: String,
    overlays: String,
    onReady: () -> Unit,
    onIdle: (lat: Double, lon: Double, zoom: Double) -> Unit,
    onClick: (lat: Double, lon: Double, x: Double, y: Double) -> Unit,
): MapHandle

internal external fun destroy(h: MapHandle)
internal external fun setStyle(h: MapHandle, style: String)
internal external fun setData(h: MapHandle, source: String, geojson: String)
internal external fun setLayerVisible(h: MapHandle, layer: String, visible: Boolean)
internal external fun setPadding(h: MapHandle, top: Double, right: Double, bottom: Double, left: Double)
internal external fun easeTo(h: MapHandle, lat: Double, lon: Double, zoom: Double, durationMs: Int)
internal external fun fitBounds(h: MapHandle, s: Double, w: Double, n: Double, e: Double, durationMs: Int)
internal external fun zoom(h: MapHandle): Double
internal external fun projectX(h: MapHandle, lat: Double, lon: Double): Double
internal external fun projectY(h: MapHandle, lat: Double, lon: Double): Double
internal external fun setMarkers(h: MapHandle, markers: String, selectedId: String?, onClick: (id: String) -> Unit)
