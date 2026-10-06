import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The web app: the TagHistory UI as real DOM (Kilua on Kotlin/Wasm), styled
// with Tailwind CSS. It is a client of the sync server it is served from and
// renders the same :shared view models the Android app uses.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kilua)
}

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        useEsModules()
        outputModuleName.set("webApp")
        browser {
            commonWebpackConfig {
                outputFileName = "webApp.js"
                cssSupport { enabled = true }
                sourceMaps = false
            }
        }
        binaries.executable()
        compilerOptions {
            target.set("es2015")
        }
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kilua)
            implementation(libs.kilua.routing)
            implementation(libs.kilua.tailwindcss)
            implementation(libs.kilua.lucide)
            implementation(libs.kotlinx.browser)
            implementation(libs.multiplatform.settings)
            // The database driver (sql.js on the page) lives in :shared.
            implementation(npm("sql.js", "1.10.3"))
            implementation(npm("maplibre-gl", "5.6.0"))
        }
    }
}

// Node and wasm-opt come from the machine (the Nix profile) instead of
// downloads: a downloaded Node binary does not run on NixOS, and the binaryen
// release download stalls and holds the whole build.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec>().download.set(false)
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().apply {
        download.set(false)
        command.set("wasm-opt")
    }
}
