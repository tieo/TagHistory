// Glue between the wasm Kotlin code (AnisetteJsProvider) and the
// vendored lbr77/anisette-js bundle. Holds the Anisette instance
// + persists provisioning state to IndexedDB so the user only sees
// Apple's provisioning step once per browser profile.
//
// Bundle layout expected (drop in via scripts/build-web-anisette.sh):
//   /anisette/anisette.js          — anisette-js dist bundle
//   /anisette/anisette_rs.wasm     — Unicorn engine WASM
//   /anisette/libstoreservicescore.so
//   /anisette/libCoreADI.so

(function () {
    const IDB_NAME = "taghistory-anisette";
    const IDB_STORE = "provisioning";
    const KEY_DEVICE = "device.json";
    const KEY_ADI = "adi.pb";

    let anisette = null;

    function openIdb() {
        return new Promise((resolve, reject) => {
            const req = indexedDB.open(IDB_NAME, 1);
            req.onupgradeneeded = () => req.result.createObjectStore(IDB_STORE);
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => reject(req.error);
        });
    }

    async function idbGet(key) {
        const idb = await openIdb();
        return new Promise((resolve, reject) => {
            const tx = idb.transaction(IDB_STORE, "readonly");
            const r = tx.objectStore(IDB_STORE).get(key);
            r.onsuccess = () => resolve(r.result || null);
            r.onerror = () => reject(r.error);
        });
    }

    async function idbPut(key, value) {
        const idb = await openIdb();
        await new Promise((resolve, reject) => {
            const tx = idb.transaction(IDB_STORE, "readwrite");
            tx.objectStore(IDB_STORE).put(value, key);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
    }

    async function fetchBytes(url) {
        const res = await fetch(url);
        if (!res.ok) throw new Error(`fetch ${url} -> ${res.status}`);
        return new Uint8Array(await res.arrayBuffer());
    }

    async function init() {
        if (anisette) return;

        // The dist file is an ES module; import dynamically so a
        // missing file fails with a clear error instead of breaking
        // the whole page load.
        const mod = await import("./anisette/anisette.js");
        const wasmModule = await mod.loadWasm();

        const [ss, ca] = await Promise.all([
            fetchBytes("./anisette/libstoreservicescore.so"),
            fetchBytes("./anisette/libCoreADI.so"),
        ]);

        const savedDevice = await idbGet(KEY_DEVICE);
        const savedAdi = await idbGet(KEY_ADI);

        if (savedDevice && savedAdi) {
            anisette = await mod.Anisette.fromSaved(ss, ca, savedDevice, savedAdi, wasmModule);
        } else {
            anisette = await mod.Anisette.fromSo(ss, ca, wasmModule);
            await anisette.provision();
            await idbPut(KEY_DEVICE, anisette.getDeviceJson());
            // anisette-js exposes adi state via its emulated FS. The
            // exact accessor differs across releases; fall back to
            // re-provisioning if we cannot read it back.
            if (typeof anisette.getAdiBlob === "function") {
                await idbPut(KEY_ADI, anisette.getAdiBlob());
            }
        }
    }

    async function getHeaders() {
        if (!anisette) await init();
        const headers = await anisette.getData();
        // Persist after every header refresh — provisioning state can
        // mutate as the Apple library refreshes its internal counters.
        try {
            await idbPut(KEY_DEVICE, anisette.getDeviceJson());
            if (typeof anisette.getAdiBlob === "function") {
                await idbPut(KEY_ADI, anisette.getAdiBlob());
            }
        } catch (_) {
            // Persistence failure is not fatal for header generation.
        }
        return JSON.stringify(headers);
    }

    window.__tagAnisette__ = { init, getHeaders };
})();
