//! Host-level integration tests for the pure-Rust anisette logic.
//! These run under `cargo test -p ottjni --test anisette_host`
//! on the developer machine (no Android, no JVM) and guard the
//! contract that `AnisetteConfig::fetch_headers` observes.

use ottjni::AnisetteConfig;
use std::fs;
use tempfile::TempDir;

fn scaffold(dir: &TempDir) -> AnisetteConfig {
    let cfg = AnisetteConfig::new(dir.path().to_path_buf());
    let lib_dir = cfg.native_lib_path();
    fs::create_dir_all(&lib_dir).unwrap();
    fs::write(lib_dir.join("libstoreservicescore.so"), b"stub").unwrap();
    fs::write(lib_dir.join("libCoreADI.so"), b"stub").unwrap();
    cfg
}

#[test]
fn config_path_is_exposed_verbatim() {
    let tmp = TempDir::new().unwrap();
    let cfg = AnisetteConfig::new(tmp.path().to_path_buf());
    assert_eq!(cfg.config_path(), tmp.path());
}

#[test]
fn lib_path_is_nested_under_config() {
    let tmp = TempDir::new().unwrap();
    let cfg = AnisetteConfig::new(tmp.path().to_path_buf());
    let p = cfg.native_lib_path();
    assert!(p.starts_with(tmp.path()));
    assert!(p
        .components()
        .any(|c: std::path::Component| c.as_os_str() == "lib"));
}

#[test]
fn ensure_libs_present_happy() {
    let tmp = TempDir::new().unwrap();
    let cfg = scaffold(&tmp);
    cfg.ensure_libs_present().unwrap();
}

#[test]
fn ensure_libs_present_missing_is_stable_error() {
    let tmp = TempDir::new().unwrap();
    let cfg = AnisetteConfig::new(tmp.path().to_path_buf());
    let err = cfg.ensure_libs_present().unwrap_err();
    let msg = err.to_string();
    assert!(msg.contains(".so"), "error should name the missing .so: {msg}");
}
