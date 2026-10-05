package io.github.tieo.taghistory.anisette

/**
 * Thin JNI bindings for the Rust ottjni crate. Do not call these directly
 * from app code — go through [NativeAnisetteProvider] (Android) or
 * [DesktopAnisetteProvider] (JVM server), which handle config-dir setup,
 * dispatcher, and error translation.
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
        /**
         * Absolute path of a host-built `libottjni.so`. A plain JVM has no APK
         * native-library directory, so the server names the file explicitly;
         * Android leaves it unset and resolves the library from the APK.
         */
        const val LIBRARY_PATH_PROPERTY = "taghistory.ottjni.path"

        init {
            val path = System.getProperty(LIBRARY_PATH_PROPERTY)
            if (path != null) System.load(path) else System.loadLibrary("ottjni")
        }

        @JvmStatic external fun nativeVersion(): String

        @JvmStatic external fun nativeGetHeaders(configPath: String): HashMap<String, String>
    }
}
