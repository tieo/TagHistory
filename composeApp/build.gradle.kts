import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Compose compiler performance metrics + reports. Lets us see, per
// composable, whether it's `skippable` and `restartable`, and whether
// every parameter is `stable`. Output under `composeApp/build/compose_*`.
// Also loads the stability config so data classes from :shared are
// treated as stable (otherwise cross-module types fall back to unstable
// and every state emission recomposes the whole subtree).
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_stability_config.conf")
    )
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    android {
        namespace = "io.github.tieo.taghistory"
        compileSdk = 36
        minSdk = 24

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.koin.core)

            // `collectAsStateWithLifecycle` lives here. We already pull the
            // KMP viewmodel through :shared; add the compose binding on top.
            implementation(libs.androidx.lifecycle.runtime.compose)
            // Needed because :shared's `AppleLoginViewModel` extends
            // androidx.lifecycle.ViewModel — the consumer module needs the
            // base class on its classpath to reference the subtype.
            implementation(libs.androidx.lifecycle.viewmodel)
            // Needed because `SettingsFactory.create` (called from the
            // android host) returns `com.russhwolf.settings.Settings`, and
            // the consumer needs it to name the repo constructors.
            implementation(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.maplibre.android.sdk)
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.core)
                // Gallery renderer builds real ViewModels over an in-memory DB.
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.test.jvm)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                // IDB-backed SqlDelight driver. sql.js npm is the
                // underlying SQLite WASM; the worker JS lives in
                // src/wasmJsMain/resources/idb-sqljs-worker.js.
                implementation(libs.sqldelight.web.worker.driver)
                implementation(libs.sqldelight.async.coroutines.extensions)
                implementation(npm("sql.js", "1.10.3"))
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.tieo.taghistory.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TagHistory"
            packageVersion = "1.0.4"
        }
    }
}

// ---------------------------------------------------------------
// ottjni (Rust JNI) build wiring
// ---------------------------------------------------------------
//
// Cross-compiles the `rust/ottjni` crate into per-ABI libottjni.so
// files under composeApp's androidMain jniLibs dir. The KMP Android
// library packages those into its AAR, which the :androidApp APK
// consumes transitively.

val rustDir = layout.projectDirectory.dir("../rust")
val jniLibsDir = layout.projectDirectory.dir("src/androidMain/jniLibs")
val rustAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "x86_64" to "x86_64-linux-android",
)

// NDK lookup happens at configuration time into a Provider so the result
// serializes cleanly into Gradle's configuration cache. `androidComponents`
// + Project references must not leak into task-action closures.
val ndkHomeProvider: Provider<String> = providers.provider {
    System.getenv("ANDROID_NDK_HOME")?.let { return@provider it }
    System.getenv("NDK_HOME")?.let { return@provider it }
    // AGP 9's sdkComponents.ndkDirectory provider throws "NDK is not
    // installed" when no ndkVersion is pinned in the android DSL, which
    // is a poor way to express "not found". Probe the SDK's ndk/ directly.
    val sdkDir = androidComponents.sdkComponents.sdkDirectory.orNull?.asFile
    val ndkRoot = sdkDir?.let { File(it, "ndk") }
    if (ndkRoot == null || !ndkRoot.isDirectory) return@provider null
    ndkRoot.listFiles()?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }?.absolutePath
}

val cargoBuildOttjni by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile ottjni for all configured Android ABIs and drop into jniLibs/."

    inputs.dir(rustDir)
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file("rust-toolchain.toml"))
    rustAbis.keys.forEach { abi ->
        outputs.file(jniLibsDir.file("$abi/libottjni.so"))
    }

    workingDir = rustDir.asFile

    val ndkHome = ndkHomeProvider
    doFirst {
        val ndk = ndkHome.orNull
            ?: throw GradleException(
                "Android NDK not found. Set ANDROID_NDK_HOME or install NDK via sdkmanager " +
                    "(`sdkmanager --install 'ndk;27.0.12077973'`)."
            )
        environment("ANDROID_NDK_HOME", ndk)
        logger.lifecycle("cargoBuildOttjni: using NDK at $ndk")
    }

    // Build the cargo-ndk argument list. We invoke it through `nix shell` so
    // contributors don't have to manually install rustup + cargo-ndk — the
    // single prerequisite is a working Nix installation. Set
    // OTTJNI_SKIP_NIX=1 to bypass the wrapper when cargo + cargo-ndk are
    // already on PATH (e.g. inside CI images).
    val cargoArgs = mutableListOf("cargo", "ndk")
    rustAbis.keys.forEach { abi -> cargoArgs += listOf("-t", abi) }
    cargoArgs += listOf(
        "--platform", "24",
        "--output-dir", jniLibsDir.asFile.absolutePath,
        "build", "--release", "-p", "ottjni",
    )

    commandLine = if (System.getenv("OTTJNI_SKIP_NIX") == "1") {
        cargoArgs
    } else {
        listOf(
            "nix", "shell",
            "nixpkgs#rustup", "nixpkgs#cargo-ndk",
            "--command",
        ) + cargoArgs
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoBuildOttjni) }

// ---------------------------------------------------------------
// Apple .so extraction pipeline lives in :androidApp
// ---------------------------------------------------------------
//
// AGP 9's com.android.kotlin.multiplatform.library plugin does not
// propagate `src/androidMain/assets/` into the AAR (jniLibs ARE
// propagated via mergeAndroidMainJniLibFolders, but there is no
// equivalent for assets as of AGP 9.1.1). Keeping the cargo pipeline
// here so ottjni rides the library's jniLib plumbing, but moving the
// Apple .so extraction to :androidApp (the legacy application plugin)
// where `src/main/assets/` is picked up by mergeDebugAssets as it
// always has been.

// ---------------------------------------------------------------
// Off-screen gallery renderer (viewbook)
// ---------------------------------------------------------------
// Draws the real composables to PNGs on the JVM desktop target with sample
// data, no device or emulator. `./make-renders.sh` runs this and copies the
// output into docs/model/img for the viewbook model.
tasks.register<JavaExec>("renderGallery") {
    group = "viewbook"
    description = "Render every view to composeApp/build/gallery/*.png"
    val desktopMain = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopMain.compileAllTaskName)
    classpath = desktopMain.output.allOutputs +
        configurations.getByName("desktopRuntimeClasspath")
    mainClass.set(project.findProperty("galleryMain") as String? ?: "io.github.tieo.taghistory.gallery.GalleryKt")
    systemProperty("gallery.out", layout.buildDirectory.dir("gallery").get().asFile.absolutePath)
    (findProperty("only") as String?)?.let { systemProperty("gallery.only", it) }
    (findProperty("sizes") as String?)?.let { systemProperty("gallery.sizes", it) }
    (findProperty("themes") as String?)?.let { systemProperty("gallery.themes", it) }
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.renderApi", "SOFTWARE")
    // Skiko's software renderer needs GL/X11/fontconfig/freetype at runtime,
    // which NixOS does not put on the default library path.
    environment(
        "LD_LIBRARY_PATH",
        listOf(
            "/nix/store/fdqacryg2w9kiwb94c9rzfsyff4im8xj-libglvnd-1.7.0/lib",
            "/nix/store/5m91jqg1526jzsahrgmd37k4ml3nc5l4-libx11-1.8.13/lib",
            "/nix/store/fc1g44pg3i10wfzh3gb4m54pfgclsn76-libxcb-1.17.0/lib",
            "/nix/store/2krkc90x3ch0mgkk48fxlglq14nqapdr-libxau-1.0.12/lib",
            "/nix/store/yr83qw7bdfdxf5lb2xmfs70qb5hap0hj-libxdmcp-1.1.5/lib",
            "/nix/store/bg6ms0vw071g1fdbx2my6bbzsk62p6vd-fontconfig-2.17.1-lib/lib",
            "/nix/store/zr22ggqbv79yv4y4wv06r4grla9h59yx-freetype-2.14.2/lib",
            "/nix/store/si4q3zks5mn5jhzzyri9hhd3cv789vlm-gcc-15.2.0-lib/lib",
        ).joinToString(":"),
    )
}
