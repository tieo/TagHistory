# ottjni — on-device anisette generator for OpenTagViewer

Rust workspace that builds `libottjni.so` (per-ABI) bundling a vendored
[`omnisette`] + [`android-loader`]. The Android app loads this `.so`
via `System.loadLibrary("ottjni")` and calls two native functions:

    native fun nativeVersion(): String
    native fun nativeGetHeaders(configPath: String): java.util.HashMap<String, String>

## Layout

    rust/
    ├── Cargo.toml           # workspace root
    ├── rust-toolchain.toml  # pinned Rust + cross-compile targets
    ├── VENDORING.md         # provenance of vendored sources
    ├── android-loader/      # vendored — userspace ELF loader for Apple .so
    ├── omnisette/           # vendored — anisette header generator
    └── ottjni/              # first-party — JNI bridge (this crate)

## Testing

Host unit + integration tests:

    cargo test -p ottjni

Cross-compile for Android (requires Android NDK + cargo-ndk):

    cargo ndk -t arm64-v8a -t x86_64 --platform 24 \
        --output-dir ../app/src/main/jniLibs build --release -p ottjni

Gradle task `cargoBuildAll` wires this into the regular app build.

## Nix shell

    nix develop

brings in Rust + cargo-ndk + the Android NDK. `cd rust && cargo test`.

[`omnisette`]: https://github.com/nab138/apple-private-apis
[`android-loader`]: https://github.com/Dadoum/android-loader
