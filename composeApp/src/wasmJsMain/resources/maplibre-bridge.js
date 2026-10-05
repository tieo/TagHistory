// MapLibre GL for the web map views. The map lives in a host element behind
// the Compose canvas (see createHost), and this file owns the map inside it:
// style, camera, and the GeoJSON sources and layers the caller declares. Markers are not drawn
// here; Compose draws them over the map at positions read through project(),
// and forwards the pointer input it receives over the map (see dispatch).
//
// MapLibre is fetched on first use so the map library stays out of the
// initial page load.

(function () {
    const VERSION = "4.7.1";
    let loading = null;

    function loadMaplibre() {
        if (window.maplibregl) return Promise.resolve(window.maplibregl);
        if (loading) return loading;
        loading = new Promise((resolve, reject) => {
            const css = document.createElement("link");
            css.rel = "stylesheet";
            css.href = `https://unpkg.com/maplibre-gl@${VERSION}/dist/maplibre-gl.css`;
            document.head.appendChild(css);
            const s = document.createElement("script");
            s.src = `https://unpkg.com/maplibre-gl@${VERSION}/dist/maplibre-gl.js`;
            s.onload = () => resolve(window.maplibregl);
            s.onerror = reject;
            document.head.appendChild(s);
        });
        return loading;
    }

    const parseStyle = (style) => (style.trim().startsWith("{") ? JSON.parse(style) : style);
    // Sources and layers the caller asked for, as MapLibre style-spec JSON.
    // A style change drops them, so they are re-added on every style load
    // with the data last set.
    function installOverlays(h) {
        const map = h.map;
        for (const [id, data] of Object.entries(h.data)) {
            if (!map.getSource(id)) map.addSource(id, { type: "geojson", data });
        }
        for (const layer of h.layers) {
            if (!map.getLayer(layer.id)) map.addLayer(layer);
        }
    }

    // A host element for a map, painted behind the Compose canvas: inside the
    // shadow root Compose renders into (light-DOM children of <body> are not
    // shown once it has one), positioned with a negative z-index so the
    // canvas, which is transparent where the map shows, stays on top for input
    // and for everything Compose draws over the map.
    function createHost() {
        const root = document.body.shadowRoot || document.body;
        const frame = root.querySelector("canvas").parentElement;
        const host = document.createElement("div");
        host.style.cssText = "position:absolute;left:0;top:0;width:0;height:0;z-index:-1;overflow:hidden;";
        frame.insertBefore(host, frame.firstChild);
        return host;
    }

    window.__taghistoryMap__ = {
        createHost,
        setHostBounds(host, x, y, w, h) {
            Object.assign(host.style, { left: x + "px", top: y + "px", width: w + "px", height: h + "px" });
        },
        removeHost(host) {
            host.remove();
        },
        // Replays a pointer or wheel event Compose received over the map on
        // the map's canvas, so MapLibre's own gesture handling (drag, inertia,
        // wheel and double-click zoom) runs unchanged. x and y are CSS pixels
        // relative to the map.
        dispatch(h, kind, x, y, buttons, deltaX, deltaY) {
            if (!h.map) return;
            const canvas = h.map.getCanvas();
            const r = canvas.getBoundingClientRect();
            const init = { bubbles: true, cancelable: true, clientX: r.left + x, clientY: r.top + y,
                screenX: r.left + x, screenY: r.top + y, buttons, button: 0, view: window };
            if (kind === "wheel") {
                canvas.dispatchEvent(new WheelEvent("wheel", { ...init, deltaX, deltaY, deltaMode: 0 }));
            } else if (kind === "mousemove" || kind === "mouseup") {
                // MapLibre tracks a drag on the window once it started.
                window.dispatchEvent(new MouseEvent(kind, init));
            } else {
                canvas.dispatchEvent(new MouseEvent(kind, init));
            }
        },
        // overlays: {"sources": ["id", ...], "layers": [style-spec layer, ...]}
        mount(container, lat, lon, zoom, style, overlays, onReady, onMove, onIdle) {
            const spec = JSON.parse(overlays);
            const data = {};
            for (const id of spec.sources) data[id] = { type: "FeatureCollection", features: [] };
            const h = { container, map: null, destroyed: false, padding: 0, data, layers: spec.layers };
            container.style.width = "100%";
            container.style.height = "100%";
            loadMaplibre().then((lib) => {
                if (h.destroyed) return;
                const map = new lib.Map({
                    container,
                    style: parseStyle(style),
                    center: [lon, lat],
                    zoom,
                    attributionControl: { compact: true },
                });
                h.map = map;
                window.__taghistoryMap__.last = h;
                // Ready once the style is in: camera and data calls work from
                // here, while "load" would also wait for every visible tile.
                let announced = false;
                map.on("style.load", () => {
                    installOverlays(h);
                    if (!announced) { announced = true; onReady(); }
                });
                map.on("error", (e) => console.warn("TagHistory map:", e && e.error ? e.error.message : e));
                map.on("move", () => onMove(map.getBearing()));
                map.on("moveend", () => {
                    const c = map.getCenter();
                    onIdle(c.lat, c.lng, map.getZoom());
                });
                // The element is sized by Compose; follow it.
                h.resizer = new ResizeObserver(() => map.resize());
                h.resizer.observe(container);
            });
            return h;
        },
        destroy(h) {
            h.destroyed = true;
            if (h.resizer) h.resizer.disconnect();
            if (h.map) h.map.remove();
        },
        // Screen position of a coordinate in CSS pixels, relative to the map.
        projectX: (h, lat, lon) => (h.map ? h.map.project([lon, lat]).x : 0),
        projectY: (h, lat, lon) => (h.map ? h.map.project([lon, lat]).y : 0),
        zoom: (h) => (h.map ? h.map.getZoom() : 0),
        setStyle(h, style) {
            if (h.map) h.map.setStyle(parseStyle(style));
        },
        // Kept and applied with the next camera move: setPadding on its own
        // would cancel a running animation.
        setBottomPadding(h, px) {
            h.padding = px;
        },
        setData(h, source, geojson) {
            h.data[source] = JSON.parse(geojson);
            const src = h.map && h.map.getSource(source);
            if (src) src.setData(h.data[source]);
        },
        setLayerVisible(h, layer, visible) {
            const spec = h.layers.find((l) => l.id === layer);
            if (spec) spec.layout = Object.assign({}, spec.layout, { visibility: visible ? "visible" : "none" });
            if (h.map && h.map.getLayer(layer)) h.map.setLayoutProperty(layer, "visibility", visible ? "visible" : "none");
        },
        easeTo(h, lat, lon, zoom, durationMs) {
            if (h.map) h.map.easeTo({ center: [lon, lat], zoom, duration: durationMs, padding: { top: 0, left: 0, right: 0, bottom: h.padding } });
        },
        centerLat: (h) => (h.map ? h.map.getCenter().lat : 0),
        centerLon: (h) => (h.map ? h.map.getCenter().lng : 0),
        // Fit a box (south, west, north, east) inside the part of the map
        // not covered by overlays at the top and bottom.
        fitBounds(h, s, w, n, e, top, bottom, durationMs) {
            if (h.map) h.map.fitBounds([[w, s], [e, n]], {
                padding: { top: 48 + top, bottom: 48 + bottom, left: 48, right: 48 },
                maxZoom: 17, duration: durationMs,
            });
        },
        resetNorth(h) {
            if (h.map) h.map.easeTo({ bearing: 0, pitch: 0 });
        },
    };
})();
