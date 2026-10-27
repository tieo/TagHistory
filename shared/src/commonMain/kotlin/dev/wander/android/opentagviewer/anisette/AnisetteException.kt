package io.github.tieo.taghistory.anisette

/**
 * Thrown when anisette fails: CoreADI errors from ottjni, a missing CoreADI
 * image, or Apple refusing to provision or resync the machine. ottjni throws
 * it through JNI's `(String)` constructor, which [JvmOverloads] provides.
 */
class AnisetteException @kotlin.jvm.JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
