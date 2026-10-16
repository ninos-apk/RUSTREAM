//! JNI never exposes pointers. Destroy invalidates registry handles; in-flight calls own an Arc.
use iptv_core::service::{Engine, Request};
use jni::{
    JNIEnv,
    objects::{JClass, JString},
    sys::{jlong, jstring},
};
use serde_json::json;
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicI64, Ordering},
    },
};

struct Registered {
    engine: Arc<Mutex<Engine>>,
    cancelled: Arc<AtomicBool>,
}
type Registry = HashMap<i64, Arc<Registered>>;
static ENGINES: OnceLock<Mutex<Registry>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn engines() -> &'static Mutex<Registry> {
    ENGINES.get_or_init(Mutex::default)
}

pub fn create() -> Result<i64, String> {
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let engine = Engine::default();
    let cancelled = engine.cancelled.clone();
    engines().lock().map_err(|_| "bridge_unavailable")?.insert(
        id,
        Arc::new(Registered {
            engine: Arc::new(Mutex::new(engine)),
            cancelled,
        }),
    );
    Ok(id)
}
pub fn destroy(id: i64) {
    if let Ok(mut engines) = engines().lock() {
        engines.remove(&id);
    }
}
pub fn dispatch(id: i64, input: &str) -> String {
    let result = std::panic::catch_unwind(|| -> Result<serde_json::Value, String> {
        if input.len() > 16_384 {
            return Err("request_too_large".into());
        }
        let engine = engines()
            .lock()
            .map_err(|_| "bridge_unavailable")?
            .get(&id)
            .cloned()
            .ok_or("invalid_handle")?;
        let request: Request = serde_json::from_str(input).map_err(|_| "invalid_request")?;
        engine
            .engine
            .lock()
            .map_err(|_| "engine_unavailable")?
            .execute(request)
    });
    match result {
        Ok(Ok(value)) => json!({"ok":true,"data":value}),
        Ok(Err(code)) => json!({"ok":false,"error":code,"details":{"code":code,"stage":"core","domain":if code.starts_with("catalog")||code=="disk_full"{"storage"}else{"source"}}}),
        Err(_) => json!({"ok":false,"error":"native_failure"}),
    }
    .to_string()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_de_iptv_tv_NativeBridge_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    if let Ok(registry) = engines().lock()
        && let Some(engine) = registry.get(&id)
    {
        engine.cancelled.store(true, Ordering::Relaxed);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_de_iptv_tv_NativeBridge_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    create().unwrap_or(0)
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_de_iptv_tv_NativeBridge_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) {
    destroy(id);
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_de_iptv_tv_NativeBridge_nativeRequest(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    input: JString,
) -> jstring {
    let response = match env.get_string(&input) {
        Ok(input) => dispatch(id, &input.to_string_lossy()),
        Err(_) => return std::ptr::null_mut(),
    };
    env.new_string(response)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_closed_and_malformed_handles_are_errors() {
        let id = create().unwrap();
        assert!(dispatch(id, r#"{"op":"categories"}"#).contains("\"ok\":true"));
        assert!(dispatch(id, "bad").contains("invalid_request"));
        destroy(id);
        destroy(id);
        assert!(dispatch(id, r#"{"op":"tick"}"#).contains("invalid_handle"));
    }
}
