//! Tiny helpers for marshalling Rust types into JNI equivalents.

use crate::errors::{JniBridgeError, JniBridgeResult};
use jni::objects::{JObject, JValue};
use jni::JNIEnv;
use std::collections::HashMap;

/// Convert a `HashMap<String, String>` to a `java.util.HashMap<String, String>`.
///
/// Uses the 3-arg HashMap ctor so the map is sized up-front; marginal perf
/// win that also doubles as a signature sanity check.
pub fn string_map_to_java_hashmap<'l>(
    env: &mut JNIEnv<'l>,
    map: &HashMap<String, String>,
) -> JniBridgeResult<JObject<'l>> {
    let jmap = env
        .new_object(
            "java/util/HashMap",
            "(I)V",
            &[JValue::Int(map.len() as i32)],
        )
        .map_err(|e| JniBridgeError::Jni(format!("HashMap::<init>: {e}")))?;

    for (k, v) in map {
        let jk = env
            .new_string(k)
            .map_err(|e| JniBridgeError::Jni(format!("new_string(k): {e}")))?;
        let jv = env
            .new_string(v)
            .map_err(|e| JniBridgeError::Jni(format!("new_string(v): {e}")))?;
        env.call_method(
            &jmap,
            "put",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            &[JValue::Object(&jk.into()), JValue::Object(&jv.into())],
        )
        .map_err(|e| JniBridgeError::Jni(format!("HashMap.put: {e}")))?;
    }
    Ok(jmap)
}

// No host-side unit tests here: exercising JNI requires a running JVM,
// which we cover with a Gradle-driven smoke test (see Phase 3 / Phase 4
// tasks) rather than pulling in a JVM dep for `cargo test`.
