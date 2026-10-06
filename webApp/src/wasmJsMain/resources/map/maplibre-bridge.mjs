// MapLibre GL for the web app's maps. Kotlin owns the state; this module owns
// the map inside a container element: style, camera, the GeoJSON sources and
// layers the caller declares, and the tag markers, which are ordinary DOM
// elements MapLibre keeps positioned over the map.
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";

const parseStyle = (style) => (style.trim().startsWith("{") ? JSON.parse(style) : style);

// Sources and layers the caller asked for, as MapLibre style-spec JSON. A
// style change drops them, so they are re-added on every style load with the
// data last set.
function installOverlays(h) {
    for (const [id, data] of Object.entries(h.data)) {
        if (!h.map.getSource(id)) h.map.addSource(id, { type: "geojson", data });
    }
    for (const layer of h.layers) {
        if (!h.map.getLayer(layer.id)) h.map.addLayer(layer);
    }
}

// overlays: {"sources": ["id", ...], "layers": [style-spec layer, ...]}
export function mount(container, lat, lon, zoom, style, overlays, onReady, onIdle, onClick) {
    const spec = JSON.parse(overlays);
    const data = {};
    for (const id of spec.sources) data[id] = { type: "FeatureCollection", features: [] };
    const map = new maplibregl.Map({
        container,
        style: parseStyle(style),
        center: [lon, lat],
        zoom,
        attributionControl: { compact: true },
    });
    const h = { map, data, layers: spec.layers, markers: new Map(), padding: { top: 0, right: 0, bottom: 0, left: 0 } };
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), "top-right");
    // Ready once the style is in: camera and data calls work from here, while
    // "load" would also wait for every visible tile.
    let announced = false;
    map.on("style.load", () => {
        installOverlays(h);
        if (!announced) { announced = true; onReady(); }
    });
    map.on("error", (e) => console.warn("TagHistory map:", e && e.error ? e.error.message : e));
    map.on("moveend", () => {
        const c = map.getCenter();
        onIdle(c.lat, c.lng, map.getZoom());
    });
    map.on("click", (e) => onClick(e.lngLat.lat, e.lngLat.lng, e.point.x, e.point.y));
    h.resizer = new ResizeObserver(() => map.resize());
    h.resizer.observe(container);
    return h;
}

export function destroy(h) {
    h.resizer.disconnect();
    h.map.remove();
}

export function setStyle(h, style) {
    h.map.setStyle(parseStyle(style));
}

export function setData(h, source, geojson) {
    h.data[source] = JSON.parse(geojson);
    const src = h.map.getSource(source);
    if (src) src.setData(h.data[source]);
}

export function setLayerVisible(h, layer, visible) {
    const spec = h.layers.find((l) => l.id === layer);
    if (spec) spec.layout = Object.assign({}, spec.layout, { visibility: visible ? "visible" : "none" });
    if (h.map.getLayer(layer)) h.map.setLayoutProperty(layer, "visibility", visible ? "visible" : "none");
}

// Room taken by panels over the map, in CSS pixels. Applied with the next
// camera move, since setPadding on its own would cancel a running animation.
export function setPadding(h, top, right, bottom, left) {
    h.padding = { top, right, bottom, left };
}

export function easeTo(h, lat, lon, zoom, durationMs) {
    h.map.easeTo({ center: [lon, lat], zoom, duration: durationMs, padding: h.padding });
}

// Fit a box (south, west, north, east) inside the part of the map that panels
// leave visible.
export function fitBounds(h, s, w, n, e, durationMs) {
    const p = h.padding;
    h.map.fitBounds([[w, s], [e, n]], {
        padding: { top: 48 + p.top, right: 48 + p.right, bottom: 48 + p.bottom, left: 48 + p.left },
        maxZoom: 17,
        duration: durationMs,
    });
}

export const zoom = (h) => h.map.getZoom();
export const projectX = (h, lat, lon) => h.map.project([lon, lat]).x;
export const projectY = (h, lat, lon) => h.map.project([lon, lat]).y;

// Tag markers as chips (emoji and name over a small tail whose tip sits on the
// position). markers: JSON [{"id","lat","lon","label","emoji"}]. A chip is
// rebuilt only when its text changes; the selected one is raised and lit.
const CHIP = "flex items-center gap-1.5 rounded-full px-3 py-1 text-sm font-semibold shadow-lg ring-1 transition";
const CHIP_IDLE = "bg-white/90 text-zinc-800 ring-black/10 opacity-80 scale-90 dark:bg-zinc-800/90 dark:text-zinc-100 dark:ring-white/10";
const CHIP_SELECTED = "bg-violet-600 text-white ring-violet-700 scale-110";

function chip(label, emoji, onClick, id) {
    const root = document.createElement("button");
    root.type = "button";
    root.className = "flex cursor-pointer flex-col items-center";
    root.dataset.testid = "marker_" + id;
    const pill = document.createElement("span");
    if (emoji) {
        const e = document.createElement("span");
        e.textContent = emoji;
        pill.append(e);
    }
    const name = document.createElement("span");
    name.className = "max-w-40 truncate";
    name.textContent = label;
    pill.append(name);
    const tail = document.createElement("span");
    tail.className = "h-2 w-2 -mt-1 rotate-45 shadow";
    root.append(pill, tail);
    root.addEventListener("click", (e) => { e.stopPropagation(); onClick(id); });
    return { root, pill, tail, text: label + "\u0000" + (emoji || "") };
}

export function setMarkers(h, markersJson, selectedId, onClick) {
    const keep = new Set();
    for (const m of JSON.parse(markersJson)) {
        keep.add(m.id);
        let entry = h.markers.get(m.id);
        const text = m.label + "\u0000" + (m.emoji || "");
        if (!entry || entry.chip.text !== text) {
            if (entry) entry.marker.remove();
            const c = chip(m.label, m.emoji, onClick, m.id);
            entry = { chip: c, marker: new maplibregl.Marker({ element: c.root, anchor: "bottom" }).setLngLat([m.lon, m.lat]).addTo(h.map) };
            h.markers.set(m.id, entry);
        } else {
            entry.marker.setLngLat([m.lon, m.lat]);
        }
        const selected = m.id === selectedId;
        entry.chip.pill.className = CHIP + " " + (selected ? CHIP_SELECTED : CHIP_IDLE);
        entry.chip.tail.className = "h-2 w-2 -mt-1 rotate-45 " + (selected ? "bg-violet-600" : "bg-white/90 dark:bg-zinc-800/90 opacity-80");
        entry.chip.root.style.zIndex = selected ? "2" : "1";
    }
    for (const [id, entry] of h.markers) {
        if (!keep.has(id)) { entry.marker.remove(); h.markers.delete(id); }
    }
}
