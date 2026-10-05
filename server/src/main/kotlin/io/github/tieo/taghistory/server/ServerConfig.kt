package io.github.tieo.taghistory.server

import java.io.File

/** Runtime configuration, read from the environment the service unit sets. */
data class ServerConfig(
    /** Persistent state: the database, settings and the anisette identity. */
    val dataDir: File,
    val bindHost: String,
    val port: Int,
    /** 32 raw bytes that encrypt the stored Apple session. */
    val blobKeyFile: File,
    /** Host-built `libottjni.so`. */
    val ottjniLibrary: File,
    /** Directory holding Apple's x86_64 `libCoreADI.so` and `libstoreservicescore.so`. */
    val appleLibsDir: File,
    /** Built web app to serve at `/`, or null to serve only the API. */
    val webDir: File?,
    /** Minutes between scheduled syncs. */
    val syncIntervalMinutes: Int,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            fun required(name: String) = env[name] ?: error("$name is not set")
            return ServerConfig(
                dataDir = File(required("TAGHISTORY_DATA_DIR")),
                bindHost = env["TAGHISTORY_BIND"] ?: "127.0.0.1",
                port = env["TAGHISTORY_PORT"]?.toInt() ?: 8095,
                blobKeyFile = File(required("TAGHISTORY_BLOB_KEY_FILE")),
                ottjniLibrary = File(required("TAGHISTORY_OTTJNI")),
                appleLibsDir = File(required("TAGHISTORY_APPLE_LIBS")),
                webDir = env["TAGHISTORY_WEB_DIR"]?.let(::File),
                syncIntervalMinutes = env["TAGHISTORY_SYNC_INTERVAL_MINUTES"]?.toInt() ?: 30,
            )
        }
    }
}
