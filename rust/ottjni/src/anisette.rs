//! Pure-Rust business logic above omnisette. No JNI in this module —
//! everything here is host-testable.

use crate::errors::{JniBridgeError, JniBridgeResult};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone)]
pub struct AnisetteConfig {
    config_path: PathBuf,
}

impl AnisetteConfig {
    pub fn new(config_path: PathBuf) -> Self {
        Self { config_path }
    }

    pub fn config_path(&self) -> &Path {
        &self.config_path
    }

    /// Where we expect the per-ABI Apple `.so` to live.
    /// Matches what omnisette's [`StoreServicesCoreADIProxy`] looks for.
    pub fn native_lib_path(&self) -> PathBuf {
        let arch = if cfg!(target_arch = "aarch64") {
            "arm64-v8a"
        } else if cfg!(target_arch = "x86_64") {
            "x86_64"
        } else if cfg!(target_arch = "arm") {
            "armeabi-v7a"
        } else if cfg!(target_arch = "x86") {
            "x86"
        } else {
            "unknown"
        };
        self.config_path.join("lib").join(arch)
    }

    /// Returns an error if the Apple `.so` files are missing.
    /// Called early so `fetch_headers` can fail fast with a clear message
    /// before omnisette's own less-specific `MissingLibraries`.
    pub fn ensure_libs_present(&self) -> JniBridgeResult<()> {
        let dir = self.native_lib_path();
        for name in ["libstoreservicescore.so", "libCoreADI.so"] {
            let p = dir.join(name);
            if !p.exists() {
                return Err(JniBridgeError::MissingLibs(format!(
                    "{} not found",
                    p.display()
                )));
            }
        }
        Ok(())
    }
}

/// Build the omnisette SSC headers provider rooted at `cfg.config_path()`
/// and run `get_authentication_headers()` on it. Errors from omnisette are
/// translated into [`JniBridgeError`] so callers (the JNI layer) see a
/// uniform error type.
///
/// Note: under omnisette's default feature set (`remove-async-await`
/// enabled), the `async fn` on `AnisetteHeadersProvider` is rewritten to
/// a sync call — so this whole function is synchronous.
pub fn fetch_headers(cfg: &AnisetteConfig) -> JniBridgeResult<HashMap<String, String>> {
    if !cfg.config_path().exists() {
        return Err(JniBridgeError::ConfigDir(format!(
            "{} does not exist",
            cfg.config_path().display()
        )));
    }
    cfg.ensure_libs_present()?;

    let config = omnisette::AnisetteConfiguration::new()
        .set_configuration_path(cfg.config_path().to_path_buf());

    let mut res = omnisette::AnisetteHeaders::get_ssc_anisette_headers_provider(config)
        .map_err(|e| JniBridgeError::Omnisette(e.to_string()))?;

    res.provider
        .get_authentication_headers()
        .map_err(|e| JniBridgeError::Omnisette(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn make_cfg_with_libs(dir: &TempDir) -> AnisetteConfig {
        let cfg = AnisetteConfig::new(dir.path().to_path_buf());
        let lib_dir = cfg.native_lib_path();
        std::fs::create_dir_all(&lib_dir).unwrap();
        std::fs::write(lib_dir.join("libstoreservicescore.so"), b"").unwrap();
        std::fs::write(lib_dir.join("libCoreADI.so"), b"").unwrap();
        cfg
    }

    #[test]
    fn native_lib_path_uses_target_arch() {
        let cfg = AnisetteConfig::new(PathBuf::from("/tmp/x"));
        let p = cfg.native_lib_path();
        let last = p.file_name().unwrap().to_str().unwrap();
        assert!(
            matches!(last, "arm64-v8a" | "x86_64" | "armeabi-v7a" | "x86" | "unknown"),
            "unexpected arch subdir: {last}"
        );
    }

    #[test]
    fn ensure_libs_present_happy_path() {
        let tmp = TempDir::new().unwrap();
        let cfg = make_cfg_with_libs(&tmp);
        cfg.ensure_libs_present().expect("libs should be present");
    }

    #[test]
    fn ensure_libs_present_missing_ssc() {
        let tmp = TempDir::new().unwrap();
        let cfg = AnisetteConfig::new(tmp.path().to_path_buf());
        std::fs::create_dir_all(cfg.native_lib_path()).unwrap();
        let err = cfg.ensure_libs_present().unwrap_err();
        assert!(matches!(err, JniBridgeError::MissingLibs(_)));
        assert!(err.to_string().contains("libstoreservicescore.so"));
    }

    #[test]
    fn ensure_libs_present_missing_coreadi() {
        let tmp = TempDir::new().unwrap();
        let cfg = AnisetteConfig::new(tmp.path().to_path_buf());
        let dir = cfg.native_lib_path();
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("libstoreservicescore.so"), b"").unwrap();
        let err = cfg.ensure_libs_present().unwrap_err();
        assert!(err.to_string().contains("libCoreADI.so"));
    }

    #[test]
    fn fetch_headers_errors_when_config_dir_missing() {
        let cfg = AnisetteConfig::new(PathBuf::from("/nonexistent/path/does/not/exist"));
        let err = fetch_headers(&cfg).unwrap_err();
        assert!(matches!(err, JniBridgeError::ConfigDir(_)));
    }

    #[test]
    fn fetch_headers_errors_when_libs_missing() {
        let tmp = TempDir::new().unwrap();
        let cfg = AnisetteConfig::new(tmp.path().to_path_buf());
        let err = fetch_headers(&cfg).unwrap_err();
        assert!(matches!(err, JniBridgeError::MissingLibs(_)));
    }

    #[test]
    fn fetch_headers_wraps_omnisette_errors() {
        // With bogus .so bytes the android-loader ELF parser will reject
        // the file — we expect that to surface as JniBridgeError::Omnisette,
        // not a raw panic or generic error.
        let tmp = TempDir::new().unwrap();
        let cfg = make_cfg_with_libs(&tmp);
        let err = fetch_headers(&cfg).expect_err("empty .so cannot load");
        assert!(
            matches!(err, JniBridgeError::Omnisette(_)),
            "expected Omnisette error, got {err:?}"
        );
    }
}
