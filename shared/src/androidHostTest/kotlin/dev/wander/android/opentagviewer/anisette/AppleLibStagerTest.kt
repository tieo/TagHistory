package io.github.tieo.taghistory.anisette

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ported verbatim from Java `AppleLibStagerTest`. In the Kotlin port,
 * [AppleLibStager.stage] wraps all staging IOExceptions (missing asset,
 * copy failure, rename failure) in [AnisetteException] so callers have a
 * single catch type — the `rejects_missing_assets` test reflects that.
 */
class AppleLibStagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val coreAdiBytes = byteArrayOf(1, 2, 3, 4, 5)
    private val sscBytes = byteArrayOf(9, 8, 7, 6)

    private fun fakeAssets(entries: Map<String, ByteArray>): AppleLibStager.AssetSource =
        object : AppleLibStager.AssetSource {
            override fun open(path: String): InputStream {
                val bytes = entries[path] ?: throw IOException("missing asset: $path")
                return ByteArrayInputStream(bytes)
            }

            override fun size(path: String): Long {
                val bytes = entries[path] ?: throw IOException("missing asset: $path")
                return bytes.size.toLong()
            }
        }

    private fun armAssets(): Map<String, ByteArray> = mapOf(
        "apple-libs/arm64-v8a/libCoreADI.so" to coreAdiBytes,
        "apple-libs/arm64-v8a/libstoreservicescore.so" to sscBytes,
    )

    @Test
    fun stage_createsAbiDirectoryLayoutMatchingOmnisette() {
        val cfg = tempFolder.newFolder("anisette")
        AppleLibStager.stage(fakeAssets(armAssets()), cfg, "arm64-v8a")

        val abiDir = File(File(cfg, "lib"), "arm64-v8a")
        assertTrue(abiDir.isDirectory)
        assertTrue(File(abiDir, "libCoreADI.so").isFile)
        assertTrue(File(abiDir, "libstoreservicescore.so").isFile)
    }

    @Test
    fun stage_writesExactAssetBytes() {
        val cfg = tempFolder.newFolder("anisette")
        AppleLibStager.stage(fakeAssets(armAssets()), cfg, "arm64-v8a")

        val dst = File(File(File(cfg, "lib"), "arm64-v8a"), "libCoreADI.so")
        assertContentEquals(coreAdiBytes, Files.readAllBytes(dst.toPath()))
    }

    @Test
    fun stage_isIdempotentWhenFilesAlreadyPresentWithRightSize() {
        val cfg = tempFolder.newFolder("anisette")
        val src = fakeAssets(armAssets())

        AppleLibStager.stage(src, cfg, "arm64-v8a")
        val dst = File(File(File(cfg, "lib"), "arm64-v8a"), "libCoreADI.so")
        val firstWrite = dst.lastModified()

        // Ensure any platform with 1s mtime resolution actually ticks forward
        Thread.sleep(1100)

        AppleLibStager.stage(src, cfg, "arm64-v8a")
        assertEquals(firstWrite, dst.lastModified(), "second stage should be a no-op")
    }

    @Test
    fun stage_replacesFileWhenSizeDiffers() {
        val cfg = tempFolder.newFolder("anisette")
        val abiDir = File(File(cfg, "lib"), "arm64-v8a")
        assertTrue(abiDir.mkdirs())

        val dst = File(abiDir, "libCoreADI.so")
        Files.write(dst.toPath(), byteArrayOf(0))

        AppleLibStager.stage(fakeAssets(armAssets()), cfg, "arm64-v8a")
        assertContentEquals(coreAdiBytes, Files.readAllBytes(dst.toPath()))
    }

    @Test
    fun stage_rejectsMissingAssetsForRequestedAbi() {
        val cfg = tempFolder.newFolder("anisette")
        val ex = assertFailsWith<AnisetteException> {
            AppleLibStager.stage(fakeAssets(emptyMap()), cfg, "arm64-v8a")
        }
        assertTrue("arm64-v8a" in (ex.message ?: ""))
    }

    @Test
    fun stage_requiresNonEmptyAbi() {
        val cfg = tempFolder.newFolder("anisette")
        assertFailsWith<AnisetteException> {
            AppleLibStager.stage(fakeAssets(armAssets()), cfg, "")
        }
    }
}
