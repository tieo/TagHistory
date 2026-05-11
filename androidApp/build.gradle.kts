import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

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
            // Required on Android 6.0+ for apps that run libraries from mmap'd
            // memory (our Rust ELF loader for Apple's .so). Also unblocks
            // 16 KB page-aligned arm64 devices.
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
// Apple .so extraction pipeline
// ---------------------------------------------------------------
//
// omnisette / StoreServicesCoreADIProxy needs Apple's libCoreADI.so and
// libstoreservicescore.so. Same approach as Dadoum/anisette-v3-server:
// pull them out of the public Apple Music for Android APK served from
// apps.mzstatic.com, then bundle the two ABIs we build for as Android
// assets. The first time AnisetteService runs, it copies the assets
// into the app-private filesDir where the Rust side mmaps them via
// android-loader's bigger_pages branch.
//
// The APK URL is pinned by SHA-256 so an Apple rotation breaks the
// build loudly instead of shipping unexpected binaries.
//
// This pipeline lives here (not in :composeApp) because AGP 9's KMP
// library plugin does NOT forward src/androidMain/assets/ into the
// AAR — only jniLibs. The legacy application plugin's mergeDebugAssets
// picks up src/main/assets/ correctly, so the .so files land here.

val appleMusicApkUrl =
    "https://apps.mzstatic.com/content/android-apple-music-apk/applemusic.apk"
// Apple Music 4.9.6.1447 (Last-Modified 2025-04-15)
val appleMusicApkSha256 =
    "9aab4e3bfd44b509dd657b030d46df536c5592512c256fc59012cce47a2e9c9c"
val appleLibAbis = listOf("arm64-v8a", "x86_64")
val appleLibNames = listOf("libCoreADI.so", "libstoreservicescore.so")

val appleLibCacheDir = layout.buildDirectory.dir("apple-libs-cache")
val appleLibAssetsDir = layout.projectDirectory.dir("src/main/assets/apple-libs")

val fetchAppleMusicApk by tasks.registering {
    group = "apple-libs"
    description = "Download Apple Music APK (pinned by SHA-256) to the build cache."

    val cacheDir = appleLibCacheDir
    val expectedSha = appleMusicApkSha256
    val url = appleMusicApkUrl

    outputs.file(cacheDir.map { it.file("applemusic.apk") })
    outputs.cacheIf { true }

    doLast {
        val dst = cacheDir.get().file("applemusic.apk").asFile
        dst.parentFile.mkdirs()

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

        if (dst.exists() && sha256(dst) == expectedSha) {
            logger.lifecycle("fetchAppleMusicApk: cache hit at $dst")
            return@doLast
        }

        logger.lifecycle("fetchAppleMusicApk: downloading $url -> $dst")
        URI(url).toURL().openStream().use { input ->
            dst.outputStream().use { output -> input.copyTo(output) }
        }

        val got = sha256(dst)
        if (got != expectedSha) {
            dst.delete()
            throw GradleException(
                "Apple Music APK SHA-256 mismatch — Apple likely rotated the URL.\n" +
                    "  url:      $url\n" +
                    "  expected: $expectedSha\n" +
                    "  got:      $got\n" +
                    "If the new version is fine, update appleMusicApkSha256 in androidApp/build.gradle.kts."
            )
        }
    }
}

val extractAppleLibs by tasks.registering {
    group = "apple-libs"
    description = "Extract libCoreADI.so + libstoreservicescore.so per ABI into assets/apple-libs/."

    dependsOn(fetchAppleMusicApk)

    val apk = appleLibCacheDir.map { it.file("applemusic.apk") }
    val outDir = appleLibAssetsDir
    val abis = appleLibAbis
    val names = appleLibNames

    inputs.file(apk)
    inputs.property("abis", abis)
    inputs.property("names", names)
    abis.forEach { abi ->
        names.forEach { n -> outputs.file(outDir.file("$abi/$n")) }
    }

    doLast {
        val apkFile = apk.get().asFile
        val root = outDir.asFile
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()

        ZipFile(apkFile).use { zip ->
            abis.forEach { abi ->
                val abiDir = File(root, abi).apply { mkdirs() }
                names.forEach { name ->
                    val entryPath = "lib/$abi/$name"
                    val entry = zip.getEntry(entryPath)
                        ?: throw GradleException(
                            "APK entry not found: $entryPath — Apple Music layout changed."
                        )
                    val dst = File(abiDir, name)
                    zip.getInputStream(entry).use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                    logger.info("extractAppleLibs: wrote $dst (${dst.length()} bytes)")
                }
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(extractAppleLibs) }

