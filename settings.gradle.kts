pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
dependencyResolutionManagement {
    // Kotlin/Wasm + JS targets register a NodeJS distribution repo at
    // configuration time (nodejs.org/dist). PREFER_SETTINGS lets them
    // add that without aborting the build.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Synthesized artifacts that fill gaps in the offline cache (e.g.
        // org.jetbrains.compose.material3:material3-desktop:1.9.0 has
        // pom+module cached but no jar; the jar is bundled here).
        maven {
            name = "local-maven"
            url = uri("${rootDir}/local-maven")
            metadataSources {
                gradleMetadata()
                mavenPom()
            }
        }
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // Kotlin/Wasm + JS need Node.js binaries; the plugin tries to add
        // this repo at project scope which conflicts with PREFER_SETTINGS.
        // Declaring it here lets `kotlinWasmNodeJsSetup` resolve cleanly.
        ivy("https://nodejs.org/dist/") {
            name = "Node Distributions at https://nodejs.org/dist"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn Distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "TagHistory"

// KMP shared business logic (apple protocol, repos, data layer).
include(":shared")

// KMP UI library — Compose MP common/android/desktop/iOS targets. Consumed by :androidApp
// on Android; desktop target produces its own distribution via compose.desktop.application.
include(":composeApp")

// Android application host — thin module wrapping :composeApp's androidMain (AGP 9 no longer
// allows com.android.application + kotlin-multiplatform in the same module).
include(":androidApp")
