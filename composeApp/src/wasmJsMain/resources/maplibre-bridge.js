// JS interop bridge for the wasmJs PlatformMapView.
// Mounts a MapLibre canvas in a position:fixed div whose bounds are
// driven by Compose's onGloballyPositioned, and pushes marker /
// style / camera updates without ever owning state on the Kotlin side.
//
// The file is hand-loaded by index.html (as opposed to importing
// maplibre-gl through webpack) so the heavy GL bundle stays out of
// the main composeApp.js entry — the map is only fetched when the
// app actually needs to render it.

(function () {
    const handles = new Map();
    let nextId = 1;
    let maplibreLib = null;

    async function ensureMaplibre() {
        if (maplibreLib) return maplibreLib;
        await new Promise((resolve, reject) => {
            const css = document.createElement("link");
            css.rel = "stylesheet";
            css.href = "https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css";
            document.head.appendChild(css);
            const s = document.createElement("script");
            s.src = "https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js";
            s.onload = resolve;
            s.onerror = reject;
            document.head.appendChild(s);
        });
        maplibreLib = window.maplibregl;
        return maplibreLib;
    }

    function makeEntry(lat, lon, zoom, styleUrl, onMarkerClick) {
        const id = nextId++;
        const container = document.createElement("div");
        container.style.cssText =
            "position:fixed;top:0;left:0;width:0;height:0;z-index:0;pointer-events:auto;";
        document.body.appendChild(container);
        const entry = {
            id,
            container,
            map: null,
            markers: [],
            onMarkerClick,
        };
        handles.set(id, entry);
        ensureMaplibre().then((lib) => {
            entry.map = new lib.Map({
                container,
                style: styleUrl,
                center: [lon, lat],
                zoom,
                attributionControl: { compact: true },
            });
        });
        return id;
    }

    window.__taghistoryMap__ = {
        create(lat, lon, zoom, styleUrl, onMarkerClick) {
            return makeEntry(lat, lon, zoom, styleUrl, onMarkerClick);
        },
        destroy(id) {
            const entry = handles.get(id);
            if (!entry) return;
            entry.markers.forEach((m) => m.remove());
            if (entry.map) entry.map.remove();
            entry.container.remove();
            handles.delete(id);
        },
        setBounds(id, x, y, w, h) {
            const entry = handles.get(id);
            if (!entry) return;
            const s = entry.container.style;
            s.left = x + "px";
            s.top = y + "px";
            s.width = w + "px";
            s.height = h + "px";
            if (entry.map) entry.map.resize();
        },
        setMarkers(id, encoded) {
            const entry = handles.get(id);
            if (!entry || !entry.map) return;
            entry.markers.forEach((m) => m.remove());
            entry.markers = [];
            if (!encoded) return;
            ensureMaplibre().then((lib) => {
                encoded.split("|").forEach((row) => {
                    const [beaconId, lat, lon, emoji, selected] = row.split(",");
                    const el = document.createElement("div");
                    el.style.cssText =
                        "font-size:" +
                        (selected === "1" ? "32px" : "24px") +
                        ";cursor:pointer;line-height:1;";
                    el.textContent = emoji;
                    el.addEventListener("click", () => entry.onMarkerClick(beaconId));
                    const marker = new lib.Marker({ element: el })
                        .setLngLat([Number(lon), Number(lat)])
                        .addTo(entry.map);
                    entry.markers.push(marker);
                });
                if (entry.markers.length > 0 && encoded.indexOf(",") > 0) {
                    const first = encoded.split("|")[0].split(",");
                    entry.map.flyTo({
                        center: [Number(first[2]), Number(first[1])],
                        zoom: 13,
                    });
                }
            });
        },
        setStyle(id, styleUrl) {
            const entry = handles.get(id);
            if (entry && entry.map) entry.map.setStyle(styleUrl);
        },
    };
})();
