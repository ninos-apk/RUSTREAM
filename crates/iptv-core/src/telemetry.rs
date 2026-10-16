use serde::Serialize;
use serde_json::Value;

#[derive(Clone, Debug, Default, Serialize)]
pub struct Telemetry {
    pub position_seconds: Option<f64>,
    pub buffered_seconds: Option<f64>,
    pub forward_bytes: Option<u64>,
    pub input_bytes_per_second: Option<u64>,
    pub cache_end_seconds: Option<f64>,
    pub paused_for_cache: bool,
    pub cache_underrun: Option<bool>,
    pub demuxer_idle: Option<bool>,
    pub reader_eof: Option<bool>,
    /// Progress towards unpausing, never a percentage of the forward cache.
    pub refill_progress_percent: Option<f64>,
    pub dropped_frames: Option<f64>,
    pub decoder_dropped_frames: Option<f64>,
    pub av_sync_seconds: Option<f64>,
    pub encoded_bitrate: Option<f64>,
    pub packet_loss_count: Option<u64>,
    pub network_rtt_ms: Option<f64>,
}

impl Telemetry {
    pub fn from_cache_state(state: Option<&Value>) -> Self {
        let Some(state) = state else {
            return Self::default();
        };
        Self {
            buffered_seconds: state
                .get("cache-duration")
                .and_then(Value::as_f64)
                .filter(|v| v.is_finite())
                .map(|v| v.max(0.0)),
            cache_end_seconds: state
                .get("cache-end")
                .and_then(Value::as_f64)
                .filter(|v| v.is_finite()),
            forward_bytes: state.get("fw-bytes").and_then(Value::as_u64),
            input_bytes_per_second: state.get("raw-input-rate").and_then(Value::as_u64),
            cache_underrun: state.get("underrun").and_then(Value::as_bool),
            demuxer_idle: state.get("idle").and_then(Value::as_bool),
            reader_eof: state.get("eof").and_then(Value::as_bool),
            ..Default::default()
        }
    }
}
