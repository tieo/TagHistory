package io.github.tieo.taghistory.anisette

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from Java `AnisetteServicePathsTest` + `AnisetteServiceAbiTest`.
 * Verifies only the pure path/ABI helpers — no Android `Context`, no JNI.
 */
class NativeAnisetteProviderPathsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ----- anisetteConfigDir -----

    @Test
    fun configDir_isNestedUnderFilesDir() {
        val filesDir = tempFolder.newFolder("files")
        val cfg = NativeAnisetteProvider.Paths.anisetteConfigDir(filesDir)
        assertEquals(File(filesDir, "anisette"), cfg)
    }

    // ----- ensureDir -----

    @Test
    fun ensureDir_createsMissingDirectory() {
        val parent = tempFolder.newFolder("files")
        val cfg = File(parent, "anisette")
        assertFalse(cfg.exists())
        NativeAnisetteProvider.Paths.ensureDir(cfg)
        assertTrue(cfg.isDirectory)
    }

    @Test
    fun ensureDir_toleratesPreExistingDirectory() {
        val cfg = tempFolder.newFolder("anisette")
        NativeAnisetteProvider.Paths.ensureDir(cfg)
        assertTrue(cfg.isDirectory)
    }

    @Test
    fun ensureDir_createsIntermediateParents() {
        val root = tempFolder.newFolder()
        val cfg = File(root, "deep/nested/anisette")
        NativeAnisetteProvider.Paths.ensureDir(cfg)
        assertTrue(cfg.isDirectory)
    }

    @Test
    fun ensureDir_throwsWhenPathIsAFile() {
        val notADir = tempFolder.newFile("anisette")
        val e = assertFailsWith<AnisetteException> {
            NativeAnisetteProvider.Paths.ensureDir(notADir)
        }
        assertTrue("not a directory" in (e.message ?: ""))
    }

    // ----- pickSupportedAbi -----

    @Test
    fun pickSupportedAbi_picksPrimaryAbiWhenSupported() {
        assertEquals(
            "arm64-v8a",
            NativeAnisetteProvider.Paths.pickSupportedAbi(arrayOf("arm64-v8a", "armeabi-v7a")),
        )
    }

    @Test
    fun pickSupportedAbi_picksX86_64WhenPrimaryIsX86_64Emulator() {
        assertEquals(
            "x86_64",
            NativeAnisetteProvider.Paths.pickSupportedAbi(arrayOf("x86_64", "x86")),
        )
    }

    @Test
    fun pickSupportedAbi_skipsUnsupportedPrimaryAndPicksSupportedSecondary() {
        assertEquals(
            "arm64-v8a",
            NativeAnisetteProvider.Paths.pickSupportedAbi(arrayOf("armeabi-v7a", "arm64-v8a")),
        )
    }

    @Test
    fun pickSupportedAbi_throwsWhenNoSupportedAbiPresent() {
        val e = assertFailsWith<AnisetteException> {
            NativeAnisetteProvider.Paths.pickSupportedAbi(arrayOf("armeabi-v7a", "x86"))
        }
        assertTrue("armeabi-v7a" in (e.message ?: ""))
    }

    @Test
    fun pickSupportedAbi_throwsWhenAbiListIsNull() {
        assertFailsWith<AnisetteException> {
            NativeAnisetteProvider.Paths.pickSupportedAbi(null)
        }
    }

    @Test
    fun pickSupportedAbi_throwsWhenAbiListIsEmpty() {
        assertFailsWith<AnisetteException> {
            NativeAnisetteProvider.Paths.pickSupportedAbi(emptyArray())
        }
    }
}
