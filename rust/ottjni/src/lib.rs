//! JNI bridge between TagHistory and Apple's macOS CoreADI, which `adi-core`
//! runs under Unicorn. CoreADI makes the anisette data (machine id and
//! one-time password) that proves a request comes from a provisioned Mac.
//!
//! The bridge only runs the library. Talking to Apple (provisioning, resync)
//! and persisting the machine happen in Kotlin, which already has the HTTP
//! stack that trusts Apple's roots.
//!
//! # Error contract
//!
//! Every failure throws `io.github.tieo.taghistory.anisette.AnisetteException`
//! (falling back to `java.lang.RuntimeException`) and returns a null or zero
//! sentinel. A panic inside the emulator is caught and thrown the same way,
//! so it never unwinds into the JVM.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use adi_core::CoreAdi;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

/// Apple's "no account" pseudo DSID, the one `akd` uses for the machine itself.
const DS_ID: i64 = -2;

/// Android-side logger setup; repeated calls are harmless.
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

fn throw(env: &mut JNIEnv, msg: &str) {
    if env.exception_check().unwrap_or(false) {
        return;
    }
    if env
        .throw_new("io/github/tieo/taghistory/anisette/AnisetteException", msg)
        .is_err()
    {
        let _ = env.throw_new("java/lang/RuntimeException", msg);
    }
}

/// Run `f`; on an error or a panic throw and return `default`.
fn run<'l, T>(
    env: &mut JNIEnv<'l>,
    default: T,
    f: impl FnOnce(&mut JNIEnv<'l>) -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(v)) => v,
        Ok(Err(msg)) => {
            throw(env, &msg);
            default
        }
        Err(_) => {
            throw(env, "CoreADI emulation panicked");
            default
        }
    }
}

fn handle<'a>(h: jlong) -> Result<std::sync::MutexGuard<'a, CoreAdi>, String> {
    if h == 0 {
        return Err("CoreADI handle is closed".into());
    }
    // SAFETY: `h` came from `nativeOpen` and stays valid until `nativeClose`;
    // the Kotlin owner never uses a handle after closing it.
    let m = unsafe { &*(h as *const Mutex<CoreAdi>) };
    m.lock().map_err(|_| "CoreADI handle is poisoned".to_string())
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Result<Vec<u8>, String> {
    env.convert_byte_array(a).map_err(|e| e.to_string())
}

fn byte_arrays<'l>(env: &mut JNIEnv<'l>, items: &[&[u8]]) -> Result<JObjectArray<'l>, String> {
    let class = env.find_class("[B").map_err(|e| e.to_string())?;
    let out = env
        .new_object_array(items.len() as i32, class, JObject::null())
        .map_err(|e| e.to_string())?;
    for (i, b) in items.iter().enumerate() {
        let a = env.byte_array_from_slice(b).map_err(|e| e.to_string())?;
        env.set_object_array_element(&out, i as i32, a)
            .map_err(|e| e.to_string())?;
    }
    Ok(out)
}

fn adi<T>(r: Result<T, adi_core::Error>) -> Result<T, String> {
    r.map_err(|e| e.to_string())
}

/// Serialises the CoreADI store as repeated `u16 name length, name, u32 data
/// length, data`, all big endian.
fn encode_store(files: &[(String, Vec<u8>)]) -> Vec<u8> {
    let mut out = Vec::new();
    for (name, data) in files {
        out.extend((name.len() as u16).to_be_bytes());
        out.extend(name.as_bytes());
        out.extend((data.len() as u32).to_be_bytes());
        out.extend(data);
    }
    out
}

fn decode_store(mut data: &[u8]) -> Result<Vec<(String, Vec<u8>)>, String> {
    let bad = || "malformed CoreADI store".to_string();
    let mut out = Vec::new();
    while !data.is_empty() {
        let name_len = u16::from_be_bytes(data.get(..2).ok_or_else(bad)?.try_into().unwrap()) as usize;
        let name = std::str::from_utf8(data.get(2..2 + name_len).ok_or_else(bad)?)
            .map_err(|_| bad())?
            .to_string();
        let len_at = 2 + name_len;
        let data_len =
            u32::from_be_bytes(data.get(len_at..len_at + 4).ok_or_else(bad)?.try_into().unwrap()) as usize;
        let body = data.get(len_at + 4..len_at + 4 + data_len).ok_or_else(bad)?;
        out.push((name, body.to_vec()));
        data = &data[len_at + 4 + data_len..];
    }
    Ok(out)
}

/// Crate version, a canary that the library loads and its symbols resolve.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeVersion<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    init_android_logger();
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Loads a CoreADI image for the machine `host_uuid` (16 bytes) with its
/// saved `store` (empty for a machine that was never provisioned).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeOpen<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    image: JByteArray<'l>,
    host_uuid: JByteArray<'l>,
    store: JByteArray<'l>,
) -> jlong {
    init_android_logger();
    run(&mut env, 0, |env| {
        let uuid: [u8; 16] = bytes(env, &host_uuid)?
            .try_into()
            .map_err(|_| "host UUID needs 16 bytes".to_string())?;
        let mut core = adi(CoreAdi::new(&bytes(env, &image)?))?;
        core.set_host_uuid(uuid);
        for (name, data) in decode_store(&bytes(env, &store)?)? {
            core.store_put(&name, &data);
        }
        Ok(Box::into_raw(Box::new(Mutex::new(core))) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeClose<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
) {
    if h != 0 {
        // SAFETY: see `handle`; the Kotlin owner closes a handle once.
        drop(unsafe { Box::from_raw(h as *mut Mutex<CoreAdi>) });
    }
}

/// The store to persist after every call that can change it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeStore<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
) -> JByteArray<'l> {
    run(&mut env, JByteArray::from(JObject::null()), |env| {
        let encoded = encode_store(&handle(h)?.store_files());
        env.byte_array_from_slice(&encoded).map_err(|e| e.to_string())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeIsProvisioned<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
) -> jboolean {
    run(&mut env, JNI_FALSE, |_| {
        let mut core = handle(h)?;
        adi(core.begin_session())?;
        let reply = adi(core.is_machine_provisioned(DS_ID))?;
        Ok(if reply.ret == 0 { JNI_TRUE } else { JNI_FALSE })
    })
}

/// `[machine id, one-time password, routing as 8 big-endian bytes]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeHeaders<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
) -> JObjectArray<'l> {
    run(&mut env, JObjectArray::from(JObject::null()), |env| {
        let headers = adi(handle(h)?.headers(DS_ID))?;
        byte_arrays(env, &[&headers.mid, &headers.otp, &headers.routing.to_be_bytes()])
    })
}

/// `[cpim, session as 4 big-endian bytes]` for Apple's `spim`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeProvisioningBegin<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
    spim: JByteArray<'l>,
) -> JObjectArray<'l> {
    run(&mut env, JObjectArray::from(JObject::null()), |env| {
        let spim = bytes(env, &spim)?;
        let (cpim, session) = adi(handle(h)?.provisioning_begin(DS_ID, &spim))?;
        byte_arrays(env, &[&cpim, &session.to_be_bytes()])
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeProvisioningFinish<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
    session: jint,
    ptm: JByteArray<'l>,
    tk: JByteArray<'l>,
    routing: jlong,
) {
    run(&mut env, (), |env| {
        let (ptm, tk) = (bytes(env, &ptm)?, bytes(env, &tk)?);
        adi(handle(h)?.provisioning_finish(DS_ID, session as u32, &ptm, &tk, routing as u64))
    })
}

/// `[machine id, srm]` answering the `sim` of Apple's resync request.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeSync<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
    sim: JByteArray<'l>,
) -> JObjectArray<'l> {
    run(&mut env, JObjectArray::from(JObject::null()), |env| {
        let sim = bytes(env, &sim)?;
        let (mid, srm) = adi(handle(h)?.sync(DS_ID, &sim))?;
        byte_arrays(env, &[&mid, &srm])
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tieo_taghistory_anisette_CoreAdiJni_nativeUpdateRouting<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    h: jlong,
    routing: jlong,
) {
    run(&mut env, (), |_| adi(handle(h)?.update_routing(DS_ID, routing as u64)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn store_round_trips() {
        let files = vec![
            ("adi-000001F5.pb".to_string(), vec![1, 2, 3]),
            ("empty".to_string(), Vec::new()),
        ];
        assert_eq!(decode_store(&encode_store(&files)).unwrap(), files);
    }

    #[test]
    fn empty_store_decodes_to_nothing() {
        assert!(decode_store(&[]).unwrap().is_empty());
    }

    #[test]
    fn truncated_store_is_rejected() {
        let mut encoded = encode_store(&[("a".to_string(), vec![9; 8])]);
        encoded.truncate(encoded.len() - 1);
        assert!(decode_store(&encoded).is_err());
    }
}
