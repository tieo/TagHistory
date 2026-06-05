// IndexedDB-backed sqljs worker for SqlDelight's web-worker-driver.
// Same message protocol as @cashapp/sqldelight-sqljs-worker (exec /
// begin_transaction / end_transaction / rollback_transaction), with
// two additions:
//   * On startup, load the SQLite blob from IndexedDB if one was
//     persisted previously.
//   * After every successful exec / commit, schedule a debounced
//     write of the SQLite blob back to IndexedDB so reloads keep
//     the user's data.

import initSqlJs from "sql.js";

const IDB_NAME = "taghistory-db";
const IDB_STORE = "blobs";
const IDB_KEY = "sqlite";
const WRITE_DEBOUNCE_MS = 500;

let db = null;
let writeTimer = null;
let writePending = false;
let writeInFlight = false;

function openIdb() {
    return new Promise((resolve, reject) => {
        const req = indexedDB.open(IDB_NAME, 1);
        req.onupgradeneeded = () => req.result.createObjectStore(IDB_STORE);
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    });
}

async function loadBlob() {
    try {
        const idb = await openIdb();
        return await new Promise((resolve, reject) => {
            const tx = idb.transaction(IDB_STORE, "readonly");
            const req = tx.objectStore(IDB_STORE).get(IDB_KEY);
            req.onsuccess = () => resolve(req.result || null);
            req.onerror = () => reject(req.error);
        });
    } catch (e) {
        console.warn("idb-sqljs-worker: load failed, starting fresh", e);
        return null;
    }
}

async function flushBlob() {
    if (writeInFlight) {
        // Coalesce: another change came in mid-write. Re-arm so we
        // run again right after this flush finishes.
        writePending = true;
        return;
    }
    writeInFlight = true;
    writePending = false;
    try {
        const data = db.export();
        const idb = await openIdb();
        await new Promise((resolve, reject) => {
            const tx = idb.transaction(IDB_STORE, "readwrite");
            tx.objectStore(IDB_STORE).put(data, IDB_KEY);
            tx.oncomplete = () => resolve();
            tx.onerror = () => reject(tx.error);
        });
    } catch (e) {
        console.warn("idb-sqljs-worker: flush failed", e);
    } finally {
        writeInFlight = false;
        if (writePending) scheduleFlush();
    }
}

function scheduleFlush() {
    if (writeTimer != null) clearTimeout(writeTimer);
    writeTimer = setTimeout(() => {
        writeTimer = null;
        flushBlob();
    }, WRITE_DEBOUNCE_MS);
}

async function createDatabase() {
    const SQL = await initSqlJs({ locateFile: file => "/sql-wasm.wasm" });
    const persisted = await loadBlob();
    db = persisted ? new SQL.Database(persisted) : new SQL.Database();
}

function isMutating(sql) {
    if (typeof sql !== "string") return false;
    return /^\s*(insert|update|delete|create|drop|alter|replace|truncate|vacuum|attach|detach)/i.test(sql);
}

function onModuleReady() {
    const data = this.data;
    switch (data && data.action) {
        case "exec": {
            if (!data.sql) throw new Error("exec: Missing query string");
            const results = db.exec(data.sql, data.params)[0] ?? { values: [] };
            postMessage({ id: data.id, results });
            if (isMutating(data.sql)) scheduleFlush();
            return;
        }
        case "begin_transaction":
            postMessage({ id: data.id, results: db.exec("BEGIN TRANSACTION;") });
            return;
        case "end_transaction":
            postMessage({ id: data.id, results: db.exec("END TRANSACTION;") });
            scheduleFlush();
            return;
        case "rollback_transaction":
            postMessage({ id: data.id, results: db.exec("ROLLBACK TRANSACTION;") });
            return;
        default:
            throw new Error(`Unsupported action: ${data && data.action}`);
    }
}

function onError(err) {
    postMessage({ id: this.data.id, error: err });
}

if (typeof importScripts === "function" || typeof self !== "undefined") {
    const ready = createDatabase();
    self.onmessage = (event) =>
        ready.then(onModuleReady.bind(event)).catch(onError.bind(event));
}
