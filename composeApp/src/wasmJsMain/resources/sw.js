// Service worker for TagHistory web preview.
// Caches the wasm bundle + static assets on install so the app loads
// offline after the first visit. Network-first for HTML (so updates
// to index.html land on next reload), cache-first for everything
// else (wasm/js chunks are content-hashed).

const CACHE = "taghistory-v1";
const PRECACHE = ["/", "/index.html", "/manifest.webmanifest"];

self.addEventListener("install", (event) => {
    event.waitUntil(
        caches.open(CACHE).then((c) => c.addAll(PRECACHE)).then(() => self.skipWaiting()),
    );
});

self.addEventListener("activate", (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
            .then(() => self.clients.claim()),
    );
});

self.addEventListener("fetch", (event) => {
    const req = event.request;
    if (req.method !== "GET") return;
    const url = new URL(req.url);

    // Never cache the CORS proxy, IndexedDB sync, or Apple endpoints.
    if (
        url.pathname.startsWith("/anisette") ||
        url.pathname.startsWith("/gsa") ||
        url.pathname.startsWith("/mobileme") ||
        url.pathname.startsWith("/findmy")
    ) {
        return;
    }

    if (req.mode === "navigate" || req.destination === "document") {
        event.respondWith(
            fetch(req).catch(() => caches.match(req).then((m) => m || caches.match("/"))),
        );
        return;
    }

    event.respondWith(
        caches.match(req).then((cached) => {
            if (cached) return cached;
            return fetch(req).then((res) => {
                if (res.ok && url.origin === self.location.origin) {
                    const copy = res.clone();
                    caches.open(CACHE).then((c) => c.put(req, copy));
                }
                return res;
            });
        }),
    );
});
