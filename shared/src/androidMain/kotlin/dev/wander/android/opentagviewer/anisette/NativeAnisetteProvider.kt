package io.github.tieo.taghistory.anisette

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android [AnisetteProvider] backed by on-device Apple ADI via the Rust
 * ottjni bridge. Handles:
 *  - Config-directory lifecycle (creates app-private dir on first run).
 *  - First-launch staging of Apple's libCoreADI.so + libstoreservicescore.so
 *    out of app assets.
 *  - Dispatcher — every call runs on [Dispatchers.IO].
 *  - Error translation — [UnsatisfiedLinkError] / [RuntimeException] from
 *    the native layer surface as [AnisetteException].
 *
 * Pure path logic lives in [Paths] so it can be unit-tested without an
 * Android [Context].
 */
class NativeAnisetteProvider internal constructor(
    private val configDir: File,
    private val appleLibs: AppleLibStager.AssetSource,
    private val preferredAbi: String,
) : AnisetteProvider {

    constructor(context: Context) : this(
        Paths.anisetteConfigDir(context.filesDir),
        AppleLibStager.fromAssets(context.assets),
        Paths.pickSupportedAbi(Build.SUPPORTED_ABIS),
    )

    override suspend fun version(): String = withContext(Dispatchers.IO) {
        try {
            AnisetteJni.nativeVersion()
        } catch (e: RuntimeException) {
            throw AnisetteException("Failed to read ottjni version: ${e.message}", e)
        }
    }

    override suspend fun getHeaders(): Map<String, String> = withContext(Dispatchers.IO) {
        Paths.ensureDir(configDir)
        AppleLibStager.stage(appleLibs, configDir, preferredAbi)
        try {
            AnisetteJni.nativeGetHeaders(configDir.absolutePath)
        } catch (e: RuntimeException) {
            throw AnisetteException("Failed to generate anisette headers: ${e.message}", e)
        }
    }

    internal fun configDirForTesting(): File = configDir

    /** Pure helpers — no Android API usage. */
    object Paths {
        /**
         * ABIs we ship Apple .so files for. Anything outside this list means
         * the device is on a platform we haven't bundled libs for — surface a
         * clear error instead of failing cryptically inside the Rust loader.
         */
        val SUPPORTED_APPLE_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

        fun anisetteConfigDir(appFilesDir: File): File = File(appFilesDir, "anisette")

        fun ensureDir(dir: File) {
            if (dir.exists()) {
                if (!dir.isDirectory) {
                    throw AnisetteException("Anisette config path exists but is not a directory: $dir")
                }
                return
            }
            if (!dir.mkdirs()) {
                throw AnisetteException("Failed to create anisette config dir: $dir")
            }
        }

        /**
         * Pick the first ABI from [supportedAbis] we have Apple libs for.
         * `Build.SUPPORTED_ABIS` is in preference order (primary first), so
         * iterating forward matches the runtime's own native lib loader
         * behaviour.
         */
        fun pickSupportedAbi(supportedAbis: Array<String>?): String {
            supportedAbis?.forEach { abi ->
                if (abi in SUPPORTED_APPLE_ABIS) return abi
            }
            throw AnisetteException(
                "Device ABIs ${supportedAbis?.toList()} don't overlap bundled Apple libs $SUPPORTED_APPLE_ABIS"
            )
        }
    }
}
