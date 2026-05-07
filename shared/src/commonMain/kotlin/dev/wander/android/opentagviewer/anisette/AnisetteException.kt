package io.github.tieo.taghistory.anisette

/**
 * Thrown when anisette header generation fails — native ottjni errors,
 * config/staging errors, or a device without a supported ABI for Apple's
 * bundled .so files.
 */
class AnisetteException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
