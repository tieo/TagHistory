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
