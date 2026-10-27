import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "io.github.tieo.taghistory.compose"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.tieo.taghistory"
        minSdk = 24
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release-keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isDebuggable = true
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Keep libottjni.so uncompressed and page aligned in the APK, so
            // it loads straight from the APK, also on 16 KB page devices.
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":composeApp"))
    // Direct dep so `AppleLoginViewModel` (returned by AndroidAppHost) is
    // visible on androidApp's compile classpath. composeApp pulls :shared
    // with `implementation` scope, which doesn't leak shared's types here.
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}

// ---------------------------------------------------------------
// Apple CoreADI extraction pipeline
// ---------------------------------------------------------------
//
// Anisette comes from Apple's macOS CoreADI, which the Rust ottjni bridge
// runs under Unicorn: Apple refuses its Android ADI for sign-in and gives Find
// My tokens only to Mac and iOS identities. CoreADI 4.19 ships inside the
// iTunes 12.8.3 for Mac installer that Apple still serves; this pulls the
// x86_64 slice out of it and bundles it as an asset. The image the Rust side
// loads is pinned by SHA-256 too, since its entry point offset is only known
// for that build.
//
// The DMG, the installer package and its payload need 7z, which runs through
// `nix shell` unless OTTJNI_SKIP_NIX=1 says it is on PATH (CI images).
//
// This lives here (not in :composeApp) because AGP 9's KMP library plugin
// does NOT forward src/androidMain/assets/ into the AAR, only jniLibs.

val itunesDmgUrl =
    "https://secure-appldnld.apple.com/itunes12/041-56365-20210413-79A5AC6D-6B0A-46EC-B399-5A381183E3BE/iTunes12.8.3.dmg"
val itunesDmgSha256 = "dd08b9967db3e5847955fa168ce880303abf5f36de12abb2061e7ef1a25be214"
val coreAdiSha256 = "2eafc3c79c7f41a8afbfbeaaf23ee5dab5ad5bda55ec8e54dbc0ddf1d20ce630"
val coreAdiCacheDir = layout.buildDirectory.dir("coreadi-cache")
val coreAdiAsset = layout.projectDirectory.file("src/main/assets/coreadi/CoreADI.x86_64")
val skipNix = System.getenv("OTTJNI_SKIP_NIX") == "1"

val extractCoreAdi by tasks.registering {
    group = "apple-libs"
    description = "Extract Apple's macOS CoreADI (x86_64) from iTunes 12.8.3 for Mac into assets/coreadi/."

    val cacheDir = coreAdiCacheDir
    val out = coreAdiAsset
    val dmgUrl = itunesDmgUrl
    val dmgSha = itunesDmgSha256
    val imageSha = coreAdiSha256
    val nix = !skipNix

    inputs.property("dmgSha", dmgSha)
    inputs.property("imageSha", imageSha)
    outputs.file(out)

    doLast {
        // A local function: a script-level one would capture the build script,
        // which the configuration cache cannot serialize.
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { b -> "%02x".format(b) }
        }

        val dst = out.asFile
        if (dst.isFile && sha256(dst) == imageSha) return@doLast

        val work = cacheDir.get().asFile.apply { mkdirs() }
        val dmg = File(work, "iTunes12.8.3.dmg")
        if (!dmg.isFile || sha256(dmg) != dmgSha) {
            logger.lifecycle("extractCoreAdi: downloading $dmgUrl")
            URI(dmgUrl).toURL().openStream().use { input -> dmg.outputStream().use { input.copyTo(it) } }
            val got = sha256(dmg)
            if (got != dmgSha) {
                dmg.delete()
                throw GradleException("iTunes DMG SHA-256 mismatch: expected $dmgSha, got $got ($dmgUrl)")
            }
        }

        fun sevenZip(vararg args: String) {
            val command = (if (nix) listOf("nix", "shell", "nixpkgs#p7zip", "--command", "7z") else listOf("7z")) +
                args.toList()
            val process = ProcessBuilder(command).directory(work).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("7z ${args.joinToString(" ")} failed:\n$log")
        }
        sevenZip("x", dmg.name, "iTunes/Install iTunes.pkg", "-oipkg", "-y")
        sevenZip("x", "ipkg/iTunes/Install iTunes.pkg", "CoreADI.pkg/Payload", "-opkg", "-y")
        // The payload is a gzipped cpio archive holding the universal framework binary.
        sevenZip("x", "pkg/CoreADI.pkg/Payload", "-opayload", "-y")
        val cpio = File(work, "payload").walk().first { it.isFile }
        sevenZip("x", cpio.relativeTo(work).path, "-oroot", "-y")
        val fat = File(work, "root/System/Library/PrivateFrameworks/CoreADI.framework/Versions/A/CoreADI").readBytes()

        // Universal binary: big-endian header, then (cputype, subtype, offset, size, align) per slice.
        val header = ByteBuffer.wrap(fat)
        check(header.getInt(0) == 0xCAFEBABE.toInt()) { "CoreADI is not a universal binary" }
        val x86_64 = 0x01000007
        val slice = (0 until header.getInt(4)).map { 8 + 20 * it }
            .firstOrNull { header.getInt(it) == x86_64 }
            ?: throw GradleException("CoreADI has no x86_64 slice")
        val image = fat.copyOfRange(header.getInt(slice + 8), header.getInt(slice + 8) + header.getInt(slice + 12))

        dst.parentFile.mkdirs()
        dst.writeBytes(image)
        val got = sha256(dst)
        if (got != imageSha) {
            dst.delete()
            throw GradleException("CoreADI x86_64 SHA-256 mismatch: expected $imageSha, got $got")
        }
        File(work, "ipkg").deleteRecursively()
        File(work, "pkg").deleteRecursively()
        File(work, "payload").deleteRecursively()
        File(work, "root").deleteRecursively()
        logger.lifecycle("extractCoreAdi: wrote $dst (${dst.length()} bytes)")
    }
}

tasks.named("preBuild") { dependsOn(extractCoreAdi) }
