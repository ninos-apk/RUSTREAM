use iptv_core::recovery::{Action, Phase, Recovery, RecoveryPolicy};
use std::time::Duration;
fn sec(value: u64) -> Duration {
    Duration::from_secs(value)
}

#[test]
fn stalled_playback_reloads_without_manual_channel_switch() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(0), Some(0.0), false);
    recovery.sample(generation, sec(1), Some(1.0), false);
    recovery.tick(sec(14));
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
    assert_eq!(recovery.tick(sec(16)), Action::Reload);
    assert_eq!(recovery.recoveries, 1);
    assert_eq!(recovery.resume_position(), None);
}

#[test]
fn startup_has_timeout_even_without_any_mpv_events() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    recovery.select(sec(0), true);
    recovery.tick(sec(26));
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
    assert_eq!(recovery.tick(sec(28)), Action::Reload);
}

#[test]
fn explicit_pause_and_stop_cancel_the_watchdog() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(1), Some(1.0), false);
    recovery.pause(sec(2), true);
    assert_eq!(recovery.tick(sec(900)), Action::None);
    assert_eq!(recovery.phase, Phase::Paused);
    recovery.pause(sec(901), false);
    assert_eq!(recovery.tick(sec(902)), Action::None);
    recovery.stop();
    assert_eq!(recovery.tick(sec(1800)), Action::None);
    recovery.ended(generation, sec(1801), true, false);
    assert_eq!(recovery.phase, Phase::Idle);
}

#[test]
fn stale_session_events_cannot_restart_a_new_channel() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let old = recovery.select(sec(0), true);
    let new = recovery.select(sec(1), true);
    recovery.ended(old, sec(2), true, false);
    recovery.sample(old, sec(3), Some(99.0), false);
    assert_eq!(recovery.phase, Phase::Connecting);
    assert_eq!(recovery.position, None);
    recovery.sample(new, sec(4), Some(1.0), false);
    assert_eq!(recovery.phase, Phase::Connecting);
    recovery.sample(new, sec(5), Some(2.0), false);
    assert_eq!(recovery.phase, Phase::Playing);
}

#[test]
fn live_eof_recovers_but_vod_eof_finishes() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let live = recovery.select(sec(0), true);
    recovery.ended(live, sec(1), false, true);
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
    let vod = recovery.select(sec(2), false);
    recovery.sample(vod, sec(3), Some(83.0), false);
    assert_eq!(recovery.resume_position(), Some(83.0));
    recovery.ended(vod, sec(4), false, true);
    assert_eq!(recovery.phase, Phase::Finished);
    assert_eq!(recovery.tick(sec(900)), Action::None);
}

#[test]
fn retry_backoff_is_bounded_and_does_not_duplicate_on_repeated_error_events() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    let mut now = sec(1);
    for _ in 0..30 {
        recovery.ended(generation, now, true, false);
        let Phase::Waiting { until } = recovery.phase else {
            panic!("retry missing")
        };
        assert!(until - now <= Duration::from_millis(20_250));
        let attempts = recovery.attempts;
        recovery.ended(generation, now, true, false);
        assert_eq!(recovery.attempts, attempts);
        assert_eq!(recovery.tick(until), Action::Reload);
        now = until + sec(1);
    }
    assert_eq!(recovery.recoveries, 30);
}

#[test]
fn healthy_playback_resets_backoff_and_ignores_nan() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.ended(generation, sec(1), true, false);
    recovery.tick(sec(3));
    recovery.sample(generation, sec(4), Some(f64::NAN), false);
    assert_eq!(recovery.position, None);
    for i in 4..=40 {
        recovery.sample(generation, sec(i), Some(i as f64), false);
    }
    assert_eq!(recovery.attempts, 0);
    assert_eq!(recovery.phase, Phase::Playing);
}

#[test]
fn seeking_preserves_explicit_pause_and_resets_stall_timer() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), false);
    recovery.sample(generation, sec(1), Some(1.0), false);
    recovery.pause(sec(2), true);
    recovery.seek(sec(20));
    assert_eq!(recovery.phase, Phase::Paused);
    assert_eq!(recovery.tick(sec(900)), Action::None);
    recovery.pause(sec(901), false);
    recovery.seek(sec(910));
    assert_eq!(recovery.tick(sec(911)), Action::None);
}

#[test]
fn manual_reconnect_while_paused_starts_playback_again() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.pause(sec(1), true);
    recovery.begin_load(sec(2));
    recovery.sample(generation, sec(2), Some(0.0), false);
    recovery.sample(generation, sec(3), Some(1.0), false);
    assert_eq!(recovery.phase, Phase::Playing);
    recovery.tick(sec(20));
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
}

#[test]
fn empty_cache_during_healthy_playback_does_not_reconnect() {
    use iptv_core::telemetry::Telemetry;
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(0), Some(0.0), false);
    for second in 1..100 {
        recovery.observe(
            generation,
            sec(second),
            &Telemetry {
                position_seconds: Some(second as f64),
                buffered_seconds: Some(0.0),
                ..Default::default()
            },
        );
        assert_eq!(recovery.tick(sec(second)), Action::None);
        assert_eq!(recovery.phase, Phase::Playing);
    }
    assert_eq!(recovery.recoveries, 0);
}

#[test]
fn eof_drain_does_not_refresh_ingress_from_a_stale_positive_rate() {
    use iptv_core::telemetry::Telemetry;
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.observe(
        generation,
        sec(1),
        &Telemetry {
            position_seconds: Some(10.0),
            input_bytes_per_second: Some(600_000),
            cache_end_seconds: Some(13.0),
            forward_bytes: Some(2_000_000),
            ..Default::default()
        },
    );
    recovery.observe(
        generation,
        sec(3),
        &Telemetry {
            position_seconds: Some(12.0),
            input_bytes_per_second: Some(8_928),
            cache_end_seconds: Some(13.0),
            forward_bytes: Some(600_000),
            reader_eof: Some(true),
            ..Default::default()
        },
    );
    assert_eq!(recovery.ingress_idle_seconds(sec(3)), 2.0);
    assert_eq!(recovery.phase, Phase::Playing);
    assert_eq!(recovery.tick(sec(3)), Action::None);
}

#[test]
fn zero_or_backward_first_positions_are_not_verified_playback() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(1), Some(0.0), false);
    assert!(!recovery.has_played);
    recovery.sample(generation, sec(2), Some(0.0), false);
    assert!(!recovery.has_played);
    recovery.sample(generation, sec(3), Some(-10.0), false);
    assert!(!recovery.has_played);
    recovery.sample(generation, sec(4), Some(-9.0), false);
    assert!(recovery.has_played);
}

#[test]
fn first_healthy_live_eof_is_quick_but_repeated_eof_backs_off() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(1), Some(0.0), false);
    recovery.sample(generation, sec(2), Some(1.0), false);
    recovery.ended(generation, sec(3), false, true);
    let Phase::Waiting { until } = recovery.phase else {
        panic!("expected retry")
    };
    assert!(until - sec(3) <= Duration::from_millis(250));
    recovery.tick(until);
    recovery.ended(generation, sec(4), false, true);
    let Phase::Waiting { until } = recovery.phase else {
        panic!("expected retry")
    };
    assert!(until - sec(4) >= sec(2));
}

#[test]
fn slow_but_progressing_refill_survives_the_old_twelve_second_watchdog() {
    use iptv_core::telemetry::Telemetry;
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(1), Some(1.0), false);
    for second in 2..50 {
        recovery.observe(
            generation,
            sec(second),
            &Telemetry {
                position_seconds: Some(1.0),
                buffered_seconds: Some(0.0),
                paused_for_cache: true,
                forward_bytes: Some(second * 1000),
                ..Default::default()
            },
        );
        assert_eq!(recovery.tick(sec(second)), Action::None);
        assert_eq!(recovery.phase, Phase::Buffering);
    }
    recovery.sample(generation, sec(50), Some(2.0), false);
    assert_eq!(recovery.phase, Phase::Playing);
    assert_eq!(recovery.attempts, 0);
}

#[test]
fn progressing_refill_still_has_a_hard_deadline() {
    use iptv_core::{recovery::RecoveryReason, telemetry::Telemetry};
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(1), Some(1.0), false);
    for second in 2..=92 {
        recovery.observe(
            generation,
            sec(second),
            &Telemetry {
                position_seconds: Some(1.0),
                paused_for_cache: true,
                input_bytes_per_second: Some(1),
                ..Default::default()
            },
        );
        recovery.tick(sec(second));
    }
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
    assert_eq!(recovery.last_reason, Some(RecoveryReason::RefillDeadline));
}

#[test]
fn refill_without_any_ingress_reconnects_with_an_explicit_reason() {
    use iptv_core::recovery::RecoveryReason;
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), true);
    recovery.sample(generation, sec(1), Some(1.0), false);
    recovery.sample(generation, sec(2), Some(1.0), true);
    recovery.tick(sec(14));
    assert_eq!(recovery.phase, Phase::Buffering);
    recovery.tick(sec(22));
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
    assert_eq!(recovery.last_reason, Some(RecoveryReason::RefillNoIngress));
}

#[test]
fn retained_vod_position_does_not_shorten_new_load_startup_timeout() {
    let mut recovery = Recovery::new(RecoveryPolicy::default());
    let generation = recovery.select(sec(0), false);
    recovery.sample(generation, sec(1), Some(1234.0), false);
    recovery.ended(generation, sec(2), true, false);
    assert_eq!(recovery.tick(sec(4)), Action::Reload);
    recovery.tick(sec(17));
    assert_eq!(recovery.phase, Phase::Connecting);
    assert_eq!(recovery.resume_position(), Some(1234.0));
    recovery.tick(sec(30));
    assert!(matches!(recovery.phase, Phase::Waiting { .. }));
}
