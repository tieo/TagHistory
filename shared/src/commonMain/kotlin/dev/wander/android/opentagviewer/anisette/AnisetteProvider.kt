package io.github.tieo.taghistory.anisette

/**
 * Produces anisette headers for Apple GSA / iCloud requests. The Android
 * implementation ([io.github.tieo.taghistory.anisette.NativeAnisetteProvider])
 * delegates to on-device Apple ADI provisioning via the Rust ottjni bridge;
 * desktop and iOS don't have a provider yet.
 *
 * Replaces the external anisette-v3-server dependency — see
 * `project_anisette_bridge_migration.md` in persistent memory for context.
 */
interface AnisetteProvider {
    /** Anisette headers (`X-Apple-I-MD`, `X-Apple-I-Client-Time`, …). */
    suspend fun getHeaders(): Map<String, String>

    /** Underlying ottjni crate version. Useful as a preflight/canary. */
    suspend fun version(): String
}
