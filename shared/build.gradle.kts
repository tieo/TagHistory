import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    // Wire up iosMain / iosTest as intermediate source sets between the
    // three iOS targets and commonMain. Without this the per-target
    // source sets are siblings of commonMain and our iosMain/ actuals
    // aren't found for the expect declarations they fulfil.
    applyDefaultHierarchyTemplate()

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
        namespace = "io.github.tieo.taghistory.shared"
        compileSdk = 36
        minSdk = 24

        withHostTestBuilder {}.configure {}

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
            baseName = "Shared"
            isStatic = true
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)

            implementation(libs.koin.core)

            implementation(libs.cryptography.kotlin.core)

            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)

            // KMP ViewModel base — viewModelScope + lifecycle-aware
            // cancellation wired into the host's Activity/Composable.
            // (lifecycle-runtime-desktop:2.10.0 isn't in the offline cache;
            // viewmodel alone ships `viewModelScope` so that's all we need.)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Intermediate source set shared between JVM-based targets (Android +
        // desktop). Lets us write the JDK-crypto actuals once instead of
        // duplicating them per target.
        val jvmMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // BouncyCastle supplies the secp224r1 curve parameters and
                // ECPoint arithmetic used by FindMy key derivation + ECDH
                // (JDK's standard EC APIs don't expose raw point multiplication).
                implementation(libs.bouncycastle.bcprov)
            }
        }
        val jvmTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.kotlinx.coroutines.test.jvm)
                implementation(libs.ktor.client.mock.jvm)
            }
        }

        androidMain {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.cryptography.kotlin.provider.jdk)
                implementation(libs.datastore)
                // CoroutineWorker lives in work-runtime since 2.5.
                implementation(libs.androidx.work.runtime)
                // Force core to the cached 1.18.0 variant — work-runtime's
                // transitive 1.12.0 has pom+module only in the local cache.
                implementation(libs.androidx.core)
                // Same reason for room-runtime: work pulls 2.7.0, cache has 2.8.4.
                implementation(libs.androidx.room.runtime.pinned)
            }
        }

        val androidHostTest by getting {
            dependsOn(jvmTest)
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test.jvm)
                implementation(libs.ktor.client.mock.jvm)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.cryptography.kotlin.provider.jdk)
            }
        }

        val iosMain by getting {
            dependencies {
                implementation(libs.sqldelight.native.driver)
            }
        }
        val wasmJsMain by getting {
            dependencies {
                // Pure-Kotlin SHA-256 + HMAC-SHA-256 so the report
                // hasher and any HMAC call sites work on web. AES +
                // BigInt + P-224 are still throwing stubs.
                implementation(libs.kotlincrypto.sha2)
                implementation(libs.kotlincrypto.hmac.sha2)
                implementation(libs.kotlinx.crypto.aes)
            }
        }
        val desktopTest by getting {
            dependsOn(jvmTest)
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.kotlinx.coroutines.test.jvm)
                implementation(libs.ktor.client.mock.jvm)
            }
        }
    }
}

sqldelight {
    databases {
        create("TagHistoryDatabase") {
            packageName.set("io.github.tieo.taghistory.db")
        }
    }
}
