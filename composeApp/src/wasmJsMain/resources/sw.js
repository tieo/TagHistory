// Service worker for the TagHistory web app.
// Network first for every same-origin GET, so a deploy reaches the next page
// load; each successful response is cached as the fallback when the network
// is gone. The app's bundle names are stable across builds, so serving them
// cache-first would pin a browser to the build it first saw.

const CACHE = "taghistory-v2";

self.addEventListener("install", () => self.skipWaiting());

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
    if (url.origin !== self.location.origin) return;

    // Server API responses are live data; the app keeps its own copy in
    // IndexedDB.
    if (url.pathname.startsWith("/api/")) return;

    event.respondWith(
        fetch(req)
            .then((res) => {
                if (res.ok) {
                    const copy = res.clone();
                    caches.open(CACHE).then((c) => c.put(req, copy));
                }
                return res;
            })
            .catch(() => caches.match(req).then((m) => m || caches.match("/"))),
    );
});
