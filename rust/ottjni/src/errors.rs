use jni::JNIEnv;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum JniBridgeError {
    #[error("JNI error: {0}")]
    Jni(String),
    #[error("config dir missing or unreadable: {0}")]
    ConfigDir(String),
    #[error("omnisette error: {0}")]
    Omnisette(String),
    #[error("Apple .so libraries missing under {0}")]
    MissingLibs(String),
}

pub type JniBridgeResult<T> = Result<T, JniBridgeError>;

impl From<omnisette::AnisetteError> for JniBridgeError {
    fn from(value: omnisette::AnisetteError) -> Self {
        match value {
            omnisette::AnisetteError::MissingLibraries => {
                JniBridgeError::MissingLibs(String::from("omnisette reported MissingLibraries"))
            }
            other => JniBridgeError::Omnisette(other.to_string()),
        }
    }
}

impl From<std::io::Error> for JniBridgeError {
    fn from(value: std::io::Error) -> Self {
        JniBridgeError::ConfigDir(value.to_string())
    }
}

/// Throw a Java exception matching our canonical Kotlin-side class.
/// Best-effort — if the class isn't found we fall back to RuntimeException.
pub fn throw_anisette_exception(env: &mut JNIEnv, err: &JniBridgeError) {
    const PREFERRED: &str = "io/github/tieo/taghistory/anisette/AnisetteException";
    const FALLBACK: &str = "java/lang/RuntimeException";

    let msg = err.to_string();
    if env.throw_new(PREFERRED, &msg).is_err() {
        let _ = env.throw_new(FALLBACK, &msg);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn io_error_maps_to_config_dir() {
        let io_err = std::io::Error::new(std::io::ErrorKind::NotFound, "nope");
        let bridge: JniBridgeError = io_err.into();
        assert!(matches!(bridge, JniBridgeError::ConfigDir(_)));
        assert!(bridge.to_string().contains("nope"));
    }

    #[test]
    fn missing_libraries_is_special_cased() {
        let omni = omnisette::AnisetteError::MissingLibraries;
        let bridge: JniBridgeError = omni.into();
        assert!(matches!(bridge, JniBridgeError::MissingLibs(_)));
    }

    #[test]
    fn other_omnisette_errors_fall_through() {
        let omni = omnisette::AnisetteError::InvalidArgument("x".into());
        let bridge: JniBridgeError = omni.into();
        assert!(matches!(bridge, JniBridgeError::Omnisette(_)));
        assert!(bridge.to_string().contains('x'));
    }

    #[test]
    fn error_displays_are_non_empty() {
        for err in [
            JniBridgeError::Jni("a".into()),
            JniBridgeError::ConfigDir("b".into()),
            JniBridgeError::Omnisette("c".into()),
            JniBridgeError::MissingLibs("d".into()),
        ] {
            assert!(!err.to_string().is_empty());
        }
    }
}
