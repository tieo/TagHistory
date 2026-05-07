package io.github.tieo.taghistory.apple.crypto

/**
 * Cryptographically secure random source. Abstracted so tests can pin a
 * deterministic stream (SRP vectors rely on a fixed client ephemeral
 * `a`) without relying on the JVM's `SecureRandom`.
 */
fun interface Rng {
    /** Fill [out] with random bytes. */
    fun nextBytes(out: ByteArray)
}

/** Platform-default secure RNG (JVM: `java.security.SecureRandom`). */
expect fun secureRng(): Rng
