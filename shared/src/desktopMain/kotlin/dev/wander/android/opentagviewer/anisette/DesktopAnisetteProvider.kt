package io.github.tieo.taghistory.anisette

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [AnisetteProvider] for a plain JVM host, backed by the same Rust ottjni
 * bridge the Android app uses. The bridge loads Apple's x86_64 Android ADI
 * libraries with a userspace ELF loader, so it runs on any x86_64 Linux.
 *
 * [configDir] holds the ADI provisioning state and must persist: Apple ties
 * the session to the device identity stored there, and a new directory makes
 * the host look like a new device. The Apple libraries are expected under
 * `configDir/lib/x86_64/`.
 *
 * Calls are serialized because omnisette rewrites the provisioning files in
 * place and two concurrent header requests could interleave those writes.
 */
class DesktopAnisetteProvider(private val configDir: File) : AnisetteProvider {

    private val lock = Mutex()

    override suspend fun version(): String = withContext(Dispatchers.IO) {
        try {
            AnisetteJni.nativeVersion()
        } catch (e: RuntimeException) {
            throw AnisetteException("Failed to read ottjni version: ${e.message}", e)
        }
    }

    override suspend fun getHeaders(): Map<String, String> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!configDir.isDirectory && !configDir.mkdirs()) {
                throw AnisetteException("Failed to create anisette config dir: $configDir")
            }
            try {
                AnisetteJni.nativeGetHeaders(configDir.absolutePath)
            } catch (e: RuntimeException) {
                throw AnisetteException("Failed to generate anisette headers: ${e.message}", e)
            }
        }
    }
}
