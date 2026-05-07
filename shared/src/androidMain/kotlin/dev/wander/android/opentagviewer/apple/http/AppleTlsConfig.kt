package io.github.tieo.taghistory.apple.http

import okhttp3.OkHttpClient

/**
 * Placeholder for Apple Root CA pinning / trust-anchor config.
 *
 * TODO(phase-17): wire in the legacy Apple Root CA PEM so GSA's cert
 * chain validates on devices whose default trust store has dropped it.
 * For now this is a no-op so the Android build compiles and everything
 * else (OkHttp defaults) works on devices where the chain still
 * validates out of the box. See reference_apple_gsa_quirks.md quirk #1
 * for the shipping history.
 */
internal object AppleTlsConfig {
    fun applyTo(builder: OkHttpClient.Builder) {
        // No-op today. Intentionally left as a seam so the CA install
        // lives in one place — separate PR per phase plan.
    }
}
