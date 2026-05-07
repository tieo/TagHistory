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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
