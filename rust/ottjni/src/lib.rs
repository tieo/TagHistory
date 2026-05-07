//! JNI bridge for TagHistory's on-device anisette header generator.
//!
//! # Error contract
//!
//! Native functions return sentinel values on fatal error (null pointers,
//! empty strings). Anything more specific is surfaced by throwing a
//! `java.lang.RuntimeException` subclass from the Java side via
//! [`throw_jni_err`].

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jobject, jstring};
use jni::JNIEnv;

mod anisette;
mod errors;
mod jni_util;

pub use anisette::AnisetteConfig;
pub use errors::{JniBridgeError, JniBridgeResult};

/// Android-side logger setup. Called once on library load via
/// `JNI_OnLoad` — repeated calls are harmless.
#[cfg(target_os = "android")]
fn init_android_logger() {
    use android_logger::{Config, FilterBuilder};
    use log::LevelFilter;
    android_logger::init_once(
        Config::default()
            .with_max_level(LevelFilter::Debug)
            .with_tag("ottjni")
            .with_filter(FilterBuilder::new().parse("debug").build()),
    );
}

#[cfg(not(target_os = "android"))]
fn init_android_logger() {}

/// Canary: returns the crate version as a Java `String`.
///
/// Used by integration tests to confirm `System.loadLibrary` + JNI
/// symbol lookup round-trips before any Apple `.so` is in the picture.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_AnisetteJni_nativeVersion<
    'l,
>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    init_android_logger();
    let version = env!("CARGO_PKG_VERSION");
    match env.new_string(version) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Fetch on-device anisette headers.
///
/// Args:
/// - `config_path`: absolute path to writable dir containing
///   `lib/<abi>/libstoreservicescore.so` + `libCoreADI.so` and where
///   ADI provisioning state is persisted.
///
/// Returns: `java.util.HashMap<String, String>` on success, or throws
/// `io.github.tieo.taghistory.anisette.AnisetteException` on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_AnisetteJni_nativeGetHeaders<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    config_path: JString<'l>,
) -> jobject {
    init_android_logger();
    match get_headers_impl(&mut env, config_path) {
        Ok(obj) => obj.into_raw(),
        Err(e) => {
            errors::throw_anisette_exception(&mut env, &e);
            std::ptr::null_mut()
        }
    }
}

fn get_headers_impl<'l>(
    env: &mut JNIEnv<'l>,
    config_path: JString<'l>,
) -> JniBridgeResult<JObject<'l>> {
    let path_str: String = env
        .get_string(&config_path)
        .map_err(|e| JniBridgeError::Jni(e.to_string()))?
        .into();
    let cfg = AnisetteConfig::new(std::path::PathBuf::from(&path_str));
    let headers = anisette::fetch_headers(&cfg)?;
    jni_util::string_map_to_java_hashmap(env, &headers)
}
