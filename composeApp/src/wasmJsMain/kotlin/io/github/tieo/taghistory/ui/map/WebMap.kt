package io.github.tieo.taghistory.ui.map

import org.w3c.dom.HTMLElement

/**
 * Kotlin side of `maplibre-bridge.js`: one MapLibre map in a host element
 * behind the Compose canvas. Shared by the main map and the history map.
 * Calls before the map has loaded are dropped by the bridge, and the callers
 * re-send their state once [onReady] fires.
 *
 * Compose's WebElementView cannot be used: it stacks the element above the
 * canvas, and the tag sheet and the markers are drawn over the map.
 */
internal class WebMap(
    lat: Double,
    lon: Double,
    zoom: Double,
    style: String,
    /** Sources and layers this map shows; see [MapOverlays]. */
    overlays: MapOverlays,
    onReady: () -> Unit,
    onMove: (bearing: Double) -> Unit,
    onIdle: (lat: Double, lon: Double, zoom: Double) -> Unit,
) {
    private val host: HTMLElement = jsCreateHost()
    private val handle: JsAny = jsMount(
        host, lat, lon, zoom, style, overlays.json, onReady, onMove, onIdle,
    )

    val zoom: Double get() = jsZoom(handle)
    val centerLat: Double get() = jsCenterLat(handle)
    val centerLon: Double get() = jsCenterLon(handle)

    /** Position of a coordinate in CSS pixels from the map's top left. */
    fun project(lat: Double, lon: Double): Pair<Double, Double> =
        jsProjectX(handle, lat, lon) to jsProjectY(handle, lat, lon)

    fun setStyle(style: String) = jsSetStyle(handle, style)
    fun setBottomPadding(cssPx: Double) = jsSetBottomPadding(handle, cssPx)
    /** Replaces the GeoJSON of one of the declared sources. */
    fun setData(source: String, geojson: String) = jsSetData(handle, source, geojson)
    fun setLayerVisible(layer: String, visible: Boolean) = jsSetLayerVisible(handle, layer, visible)
    fun easeTo(lat: Double, lon: Double, zoom: Double, durationMs: Int) = jsEaseTo(handle, lat, lon, zoom, durationMs)
    /** Fits the box, keeping [topCss] and [bottomCss] pixels clear for overlays. */
    fun fitBounds(south: Double, west: Double, north: Double, east: Double, topCss: Double, bottomCss: Double, durationMs: Int) =
        jsFitBounds(handle, south, west, north, east, topCss, bottomCss, durationMs)
    fun resetNorth() = jsResetNorth(handle)
    /** Places the map at the given box, in CSS pixels from the canvas's top left. */
    fun setBounds(x: Double, y: Double, width: Double, height: Double) = jsSetHostBounds(host, x, y, width, height)

    /** Replays an input event on the map; see `dispatch` in the bridge. */
    fun dispatch(kind: String, x: Double, y: Double, buttons: Int, deltaX: Double = 0.0, deltaY: Double = 0.0) =
        jsDispatch(handle, kind, x, y, buttons, deltaX, deltaY)

    fun destroy() {
        jsDestroy(handle)
        jsRemoveHost(host)
    }
}

/** The style MapLibre loads for [basemap]: a URL, or inline JSON for satellite. */
internal fun MapBasemap.webStyle(): String = when (this) {
    MapBasemap.LIGHT -> LIGHT_STYLE_URL
    MapBasemap.DARK -> DARK_STYLE_URL
    MapBasemap.SATELLITE -> SATELLITE_STYLE_JSON
}

/**
 * GeoJSON sources a map declares up front and the MapLibre style-spec layers
 * drawn from them, in drawing order.
 */
internal class MapOverlays(sources: List<String>, layers: List<String>) {
    val json: String = """{"sources":[${sources.joinToString(",") { "\"$it\"" }}],"layers":[${layers.joinToString(",")}]}"""
}

/** Minimal GeoJSON writers for the overlay data. */
internal object GeoJson {
    fun collection(features: List<String>) = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    fun point(lat: Double, lon: Double, properties: String = "{}") =
        """{"type":"Feature","properties":$properties,"geometry":{"type":"Point","coordinates":[$lon,$lat]}}"""

    fun line(points: List<Pair<Double, Double>>) =
        """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[${points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }}]}}"""

    /** A true-to-scale circle of [radiusM] metres. */
    fun circle(lat: Double, lon: Double, radiusM: Double): String {
        val ring = circleRing(lat, lon, radiusM, segments = 48).joinToString(",") { (x, y) -> "[$x,$y]" }
        return """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$ring]]}}"""
    }
}

/** Accuracy circles under the main map's markers, as on Android. */
internal val markerAccuracyOverlays = MapOverlays(
    sources = listOf("accuracy"),
    layers = listOf(
        """{"id":"accuracy-fill","type":"fill","source":"accuracy","paint":{"fill-color":"$ACCURACY_COLOR","fill-opacity":0.10}}""",
        """{"id":"accuracy-stroke","type":"line","source":"accuracy","paint":{"line-color":"$ACCURACY_COLOR","line-opacity":0.30,"line-width":1.5}}""",
    ),
)

/** GeoJSON accuracy circles for [markers]; under 5 m is not worth drawing. */
internal fun accuracyGeoJson(markers: List<BeaconMarkerUi>): String =
    GeoJson.collection(
        markers.filter { it.horizontalAccuracy >= 5 }
            .map { GeoJson.circle(it.latitude, it.longitude, it.horizontalAccuracy.toDouble()) },
    )

private fun jsMount(
    container: HTMLElement,
    lat: Double,
    lon: Double,
    zoom: Double,
    style: String,
    overlays: String,
    onReady: () -> Unit,
    onMove: (Double) -> Unit,
    onIdle: (Double, Double, Double) -> Unit,
): JsAny = js(
    "window.__taghistoryMap__.mount(container, lat, lon, zoom, style, overlays, onReady, onMove, onIdle)",
)

private fun jsCenterLat(h: JsAny): Double = js("window.__taghistoryMap__.centerLat(h)")
private fun jsCenterLon(h: JsAny): Double = js("window.__taghistoryMap__.centerLon(h)")
private fun jsZoom(h: JsAny): Double = js("window.__taghistoryMap__.zoom(h)")
private fun jsProjectX(h: JsAny, lat: Double, lon: Double): Double = js("window.__taghistoryMap__.projectX(h, lat, lon)")
private fun jsProjectY(h: JsAny, lat: Double, lon: Double): Double = js("window.__taghistoryMap__.projectY(h, lat, lon)")
private fun jsSetStyle(h: JsAny, style: String): Unit = js("window.__taghistoryMap__.setStyle(h, style)")
private fun jsSetBottomPadding(h: JsAny, px: Double): Unit = js("window.__taghistoryMap__.setBottomPadding(h, px)")
private fun jsSetData(h: JsAny, source: String, geojson: String): Unit =
    js("window.__taghistoryMap__.setData(h, source, geojson)")
private fun jsSetLayerVisible(h: JsAny, layer: String, visible: Boolean): Unit =
    js("window.__taghistoryMap__.setLayerVisible(h, layer, visible)")
private fun jsEaseTo(h: JsAny, lat: Double, lon: Double, zoom: Double, ms: Int): Unit =
    js("window.__taghistoryMap__.easeTo(h, lat, lon, zoom, ms)")
private fun jsFitBounds(h: JsAny, s: Double, w: Double, n: Double, e: Double, top: Double, bottom: Double, ms: Int): Unit =
    js("window.__taghistoryMap__.fitBounds(h, s, w, n, e, top, bottom, ms)")
private fun jsResetNorth(h: JsAny): Unit = js("window.__taghistoryMap__.resetNorth(h)")
private fun jsDestroy(h: JsAny): Unit = js("window.__taghistoryMap__.destroy(h)")
private fun jsCreateHost(): HTMLElement = js("window.__taghistoryMap__.createHost()")
private fun jsRemoveHost(host: HTMLElement): Unit = js("window.__taghistoryMap__.removeHost(host)")
private fun jsSetHostBounds(host: HTMLElement, x: Double, y: Double, w: Double, h: Double): Unit =
    js("window.__taghistoryMap__.setHostBounds(host, x, y, w, h)")
private fun jsDispatch(h: JsAny, kind: String, x: Double, y: Double, buttons: Int, dx: Double, dy: Double): Unit =
    js("window.__taghistoryMap__.dispatch(h, kind, x, y, buttons, dx, dy)")
