package io.github.tieo.taghistory.anisette

/**
 * Thin JNI bindings for the Rust ottjni crate. Do not call these directly
 * from app code — go through [NativeAnisetteProvider], which handles
 * config-dir setup, dispatcher, and error translation.
 *
 * Package + class name + method names MUST match the Rust
 * `Java_io_github_tieo_taghistory_anisette_AnisetteJni_*` symbol
 * naming — renaming breaks the JNI binding silently at call time.
 *
 * Implemented as a class with a `@JvmStatic` companion so the static
 * method form matches the Rust signature `(JNIEnv, JClass)` rather than
 * an instance method's `(JNIEnv, JObject)`.
 */
internal class AnisetteJni private constructor() {
    companion object {
        init {
            System.loadLibrary("ottjni")
        }

        @JvmStatic external fun nativeVersion(): String

        @JvmStatic external fun nativeGetHeaders(configPath: String): HashMap<String, String>
    }
}
