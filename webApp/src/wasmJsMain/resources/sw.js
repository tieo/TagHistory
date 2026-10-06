// Earlier builds of the web app registered a service worker at this path.
// Browsers fetch it again on their next visit; this version takes over at
// once, drops the caches it kept and unregisters, so the page talks to the
// server directly.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => {
    event.waitUntil((async () => {
        for (const key of await caches.keys()) await caches.delete(key);
        await self.registration.unregister();
        for (const client of await self.clients.matchAll({ type: "window" })) client.navigate(client.url);
    })());
});
