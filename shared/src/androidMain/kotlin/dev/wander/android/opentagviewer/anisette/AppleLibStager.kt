package io.github.tieo.taghistory.anisette

import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies Apple's libCoreADI.so + libstoreservicescore.so out of bundled
 * `assets/apple-libs/<abi>/` into the app-private anisette config dir on
 * first launch, producing the layout omnisette expects:
 *
 * ```
 *   <configDir>/lib/<abi>/libCoreADI.so
 *   <configDir>/lib/<abi>/libstoreservicescore.so
 * ```
 *
 * Staging is idempotent — a file is skipped when its size already matches
 * the bundled asset. Size-equality is a strong enough trigger on its own;
 * we don't hash because each app version bakes in a fixed ABI set and the
 * assets are read-only inside the APK.
 */
internal object AppleLibStager {

    const val LIB_CORE_ADI = "libCoreADI.so"
    const val LIB_STORE_SERVICES_CORE = "libstoreservicescore.so"

    /** Abstraction so we can unit-test staging without a live Android runtime. */
    interface AssetSource {
        fun open(path: String): InputStream
        fun size(path: String): Long
    }

    fun fromAssets(assets: AssetManager): AssetSource = object : AssetSource {
        override fun open(path: String): InputStream = assets.open(path)

        override fun size(path: String): Long = assets.open(path).use { input ->
            val buf = ByteArray(1 shl 15)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
            }
            total
        }
    }

    fun stage(source: AssetSource, configDir: File, abi: String) {
        if (abi.isEmpty()) throw AnisetteException("ABI required for Apple lib staging")

        val abiDir = File(File(configDir, "lib"), abi)
        if (!abiDir.exists() && !abiDir.mkdirs()) {
            throw AnisetteException("Failed to create $abiDir")
        }
        if (!abiDir.isDirectory) {
            throw AnisetteException("Apple lib dir path is not a directory: $abiDir")
        }

        for (name in arrayOf(LIB_CORE_ADI, LIB_STORE_SERVICES_CORE)) {
            val assetPath = "apple-libs/$abi/$name"
            val dst = File(abiDir, name)
            val tmp = File(abiDir, "$name.tmp")

            try {
                val expected = source.size(assetPath)
                if (dst.isFile && dst.length() == expected) continue

                source.open(assetPath).use { input ->
                    Files.newOutputStream(tmp.toPath()).use { output ->
                        val buf = ByteArray(1 shl 15)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                        }
                    }
                }
                Files.move(
                    tmp.toPath(),
                    dst.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: IOException) {
                tmp.delete()
                throw AnisetteException("Failed to stage $assetPath → $dst: ${e.message}", e)
            }
        }
    }
}
