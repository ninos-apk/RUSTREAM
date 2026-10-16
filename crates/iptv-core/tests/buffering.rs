use iptv_core::{
    buffering::{BufferSettings, RefillController, hls_variants, lower_hls_variant},
    telemetry::Telemetry,
};
use serde_json::json;
use std::time::Duration;

#[test]
fn settings_are_bounded_and_small_buffers_have_reachable_watermarks() {
    let settings = BufferSettings {
        target_seconds: f64::NAN,
        max_memory_mib: u32::MAX,
        ..Default::default()
    }
    .normalized();
    assert_eq!(settings.target_seconds, 3.0);
    assert_eq!(settings.max_memory_mib, 512);
    let small = BufferSettings {
        target_seconds: -1.0,
        max_memory_mib: 0,
        ..Default::default()
    }
    .normalized();
    assert_eq!(small.target_seconds, 1.0);
    assert_eq!(small.max_memory_mib, 16);
    assert!(small.initial_refill_seconds() < small.target_seconds);
    assert!(small.maximum_refill_seconds() < small.target_seconds);
}

#[test]
fn sport_balanced_and_stable_profiles_keep_reachable_refill_thresholds() {
    for (target, start, max) in [(1.0, 0.25, 0.5), (3.0, 0.5, 1.0), (6.0, 1.0, 2.0)] {
        let settings = BufferSettings {
            target_seconds: target,
            ..Default::default()
        }
        .normalized();
        assert_eq!(settings.initial_refill_seconds(), start);
        assert_eq!(settings.maximum_refill_seconds(), max);
        assert!(max < target);
    }
}

#[test]
fn repeated_underruns_raise_resume_watermark_without_changing_user_target() {
    let settings = BufferSettings {
        target_seconds: 2.0,
        ..Default::default()
    };
    let mut controller = RefillController::new(settings);
    assert!(
        controller
            .observe(Duration::from_secs(1), true, false)
            .is_none()
    );
    for second in 2..30 {
        controller.observe(Duration::from_secs(second), false, true);
        controller.observe(Duration::from_secs(second), true, true);
    }
    assert_eq!(controller.watermark, 2.0 / 3.0);
    controller.observe(Duration::from_secs(200), false, true);
    assert_eq!(controller.watermark, 2.0 / 6.0);
}

#[test]
fn disabled_adaptation_keeps_the_initial_watermark() {
    let mut controller = RefillController::new(BufferSettings {
        adaptive_refill: false,
        ..Default::default()
    });
    for second in 1..10 {
        controller.observe(Duration::from_secs(second), false, true);
        controller.observe(Duration::from_secs(second), true, true);
    }
    assert_eq!(controller.watermark, 0.5);
}

#[test]
fn missing_cache_estimate_is_not_reported_as_zero() {
    let sample = Telemetry::from_cache_state(Some(&json!({"fw-bytes":12345,"raw-input-rate":0})));
    assert_eq!(sample.buffered_seconds, None);
    assert_eq!(sample.forward_bytes, Some(12345));
    assert_eq!(sample.input_bytes_per_second, Some(0));
    assert_eq!(sample.packet_loss_count, None);
    assert_eq!(sample.network_rtt_ms, None);
    assert_eq!(Telemetry::from_cache_state(None).buffered_seconds, None);
    assert_eq!(
        Telemetry::from_cache_state(Some(&json!({"cache-duration":0.0}))).buffered_seconds,
        Some(0.0)
    );
}

#[test]
fn hls_degradation_uses_only_advertised_lower_variants_and_matching_audio() {
    let tracks = json!([
        {"id":1,"type":"video","hls-bitrate":500000,"program-id":1},
        {"id":2,"type":"audio","program-id":1},
        {"id":3,"type":"video","hls-bitrate":1500000,"program-id":2},
        {"id":4,"type":"audio","program-id":2},
        {"id":5,"type":"video","hls-bitrate":3000000,"program-id":3},
        {"id":6,"type":"video"}
    ]);
    let variants = hls_variants(&tracks);
    assert_eq!(variants.len(), 3);
    let lower = lower_hls_variant(&variants, 5, Some(250000)).unwrap();
    assert_eq!(lower.video_id, 3);
    assert_eq!(lower.audio_id, Some(4));
    assert_eq!(
        lower_hls_variant(&variants, 5, Some(10000))
            .unwrap()
            .video_id,
        1
    );
    assert!(lower_hls_variant(&variants, 1, Some(100000)).is_none());
    assert!(hls_variants(&json!([{"id":1,"type":"video"}])).is_empty());
}
