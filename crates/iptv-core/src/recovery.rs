use crate::telemetry::Telemetry;
use serde::Serialize;
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct RecoveryPolicy {
    pub startup_timeout: Duration,
    pub stall_timeout: Duration,
    pub refill_timeout: Duration,
    pub refill_max_wait: Duration,
    pub live_refill_timeout: Duration,
    pub live_refill_max_wait: Duration,
    pub stable_reset: Duration,
    pub retry_base: Duration,
    pub retry_max: Duration,
}
impl Default for RecoveryPolicy {
    fn default() -> Self {
        Self {
            startup_timeout: Duration::from_secs(25),
            stall_timeout: Duration::from_secs(12),
            refill_timeout: Duration::from_secs(20),
            refill_max_wait: Duration::from_secs(90),
            live_refill_timeout: Duration::from_secs(12),
            live_refill_max_wait: Duration::from_secs(30),
            stable_reset: Duration::from_secs(30),
            retry_base: Duration::from_secs(1),
            retry_max: Duration::from_secs(20),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Idle,
    Connecting,
    Playing,
    Buffering,
    Paused,
    Waiting { until: Duration },
    Finished,
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Action {
    None,
    Reload,
}
#[derive(Clone, Copy, Debug, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RecoveryReason {
    StartupTimeout,
    PlaybackStalled,
    RefillNoIngress,
    RefillNoProgress,
    RefillDeadline,
    BackendError,
    LiveEof,
    CommandError,
}

/// Empty/unknown buffer values do not trigger recovery. Refill progress has its own deadline.
#[derive(Debug)]
pub struct Recovery {
    pub policy: RecoveryPolicy,
    pub phase: Phase,
    pub generation: u64,
    pub attempts: u32,
    pub recoveries: u64,
    pub position: Option<f64>,
    pub last_reason: Option<RecoveryReason>,
    pub has_played: bool,
    is_live: bool,
    started: Duration,
    last_progress: Duration,
    last_ingress: Duration,
    last_refill_progress: Duration,
    healthy_since: Option<Duration>,
    buffering_since: Option<Duration>,
    last_sample_position: Option<f64>,
    previous_cache_end: Option<f64>,
    previous_forward_bytes: Option<u64>,
    previous_buffered: Option<f64>,
    previous_video_frames: Option<u64>,
    paused: bool,
}
impl Recovery {
    pub fn new(policy: RecoveryPolicy) -> Self {
        Self {
            policy,
            phase: Phase::Idle,
            generation: 0,
            attempts: 0,
            recoveries: 0,
            position: None,
            last_reason: None,
            has_played: false,
            is_live: true,
            started: Duration::ZERO,
            last_progress: Duration::ZERO,
            last_ingress: Duration::ZERO,
            last_refill_progress: Duration::ZERO,
            healthy_since: None,
            buffering_since: None,
            last_sample_position: None,
            previous_cache_end: None,
            previous_forward_bytes: None,
            previous_buffered: None,
            previous_video_frames: None,
            paused: false,
        }
    }
    pub fn select(&mut self, now: Duration, is_live: bool) -> u64 {
        self.generation = self.generation.wrapping_add(1);
        self.is_live = is_live;
        self.attempts = 0;
        self.recoveries = 0;
        self.position = None;
        self.last_reason = None;
        self.begin_load(now);
        self.generation
    }
    pub fn begin_load(&mut self, now: Duration) {
        self.phase = Phase::Connecting;
        self.paused = false;
        self.has_played = false;
        self.started = now;
        self.last_progress = now;
        self.last_ingress = now;
        self.last_refill_progress = now;
        self.healthy_since = None;
        self.buffering_since = None;
        self.last_sample_position = None;
        self.previous_cache_end = None;
        self.previous_forward_bytes = None;
        self.previous_buffered = None;
        self.previous_video_frames = None;
    }
    pub fn stop(&mut self) {
        self.generation = self.generation.wrapping_add(1);
        self.phase = Phase::Idle;
        self.position = None;
        self.paused = false;
        self.buffering_since = None;
    }
    pub fn seek(&mut self, now: Duration) {
        self.last_progress = now;
        self.last_ingress = now;
        self.last_refill_progress = now;
        self.healthy_since = None;
        self.buffering_since = None;
    }
    pub fn pause(&mut self, now: Duration, paused: bool) {
        self.paused = paused;
        self.seek(now);
        if paused && !matches!(self.phase, Phase::Idle | Phase::Finished) {
            self.phase = Phase::Paused;
        } else if !paused && self.phase == Phase::Paused {
            self.started = now;
            self.phase = Phase::Connecting;
        }
    }
    pub fn sample(
        &mut self,
        generation: u64,
        now: Duration,
        position: Option<f64>,
        buffering: bool,
    ) {
        self.observe(
            generation,
            now,
            &Telemetry {
                position_seconds: position,
                paused_for_cache: buffering,
                ..Default::default()
            },
        );
    }
    pub fn observe(&mut self, generation: u64, now: Duration, sample: &Telemetry) {
        if generation != self.generation
            || matches!(
                self.phase,
                Phase::Idle | Phase::Finished | Phase::Waiting { .. }
            )
        {
            return;
        }
        if self.paused {
            self.last_progress = now;
            self.last_ingress = now;
            return;
        }
        // The estimated rate can remain positive while an EOF queue drains.
        let input = sample.reader_eof != Some(true)
            && (sample.input_bytes_per_second.is_some_and(|rate| rate > 0)
                || sample
                    .cache_end_seconds
                    .zip(self.previous_cache_end)
                    .is_some_and(|(end, previous)| end > previous + 0.02)
                || sample
                    .forward_bytes
                    .zip(self.previous_forward_bytes)
                    .is_some_and(|(bytes, previous)| bytes > previous));
        if input {
            self.last_ingress = now;
        }
        // Raw bytes (including manifests or unusable media) are not proof that a live
        // refill is succeeding. Require growth of playable media when reported.
        let buffered = sample.buffered_seconds.filter(|value| value.is_finite());
        let refill_progress = buffered
            .zip(self.previous_buffered)
            .is_some_and(|(value, old)| value > old + 0.02)
            || (buffered.is_none() && input);
        if refill_progress {
            self.last_refill_progress = now;
        }
        self.previous_buffered = buffered;
        self.previous_cache_end = sample.cache_end_seconds;
        self.previous_forward_bytes = sample.forward_bytes;
        let position = sample.position_seconds.filter(|value| value.is_finite());
        let video_advanced = sample
            .video_frames
            .zip(self.previous_video_frames)
            .is_some_and(|(frames, previous)| frames > previous);
        self.previous_video_frames = sample.video_frames;
        let advanced = video_advanced
            || position
                .zip(self.last_sample_position)
                .is_some_and(|(value, old)| value > old + 0.02);
        // A first sample is a baseline, not evidence of movement. Timestamp
        // resets must establish a new baseline rather than masquerade as progress.
        if self.last_sample_position.is_none()
            || position
                .zip(self.last_sample_position)
                .is_some_and(|(value, old)| value < old - 0.02)
        {
            self.last_sample_position = position;
        }
        if let Some(value) = position {
            self.position = Some(value);
        }
        if advanced {
            self.last_progress = now;
            self.last_sample_position = position;
            if !sample.paused_for_cache {
                self.has_played = true;
            }
        }
        if sample.paused_for_cache {
            if self.buffering_since.is_none() {
                self.buffering_since = Some(now);
                self.last_refill_progress = now;
            }
            self.phase = Phase::Buffering;
            self.healthy_since = None;
        } else if advanced {
            self.buffering_since = None;
            self.phase = Phase::Playing;
            let healthy = *self.healthy_since.get_or_insert(now);
            if now.saturating_sub(healthy) >= self.policy.stable_reset {
                self.attempts = 0;
            }
        } else if now.saturating_sub(self.last_progress) > Duration::from_secs(1) {
            self.healthy_since = None;
        }
    }
    pub fn ended(&mut self, generation: u64, now: Duration, error: bool, natural_eof: bool) {
        if natural_eof
            && !self.is_live
            && !error
            && generation == self.generation
            && !matches!(self.phase, Phase::Idle | Phase::Waiting { .. })
        {
            self.phase = Phase::Finished;
            self.paused = false;
        } else if error || (natural_eof && self.is_live) {
            self.fail(
                generation,
                now,
                if error {
                    RecoveryReason::BackendError
                } else {
                    RecoveryReason::LiveEof
                },
            );
        }
    }
    pub fn fail(&mut self, generation: u64, now: Duration, reason: RecoveryReason) {
        if generation != self.generation
            || self.paused
            || matches!(
                self.phase,
                Phase::Idle | Phase::Finished | Phase::Waiting { .. }
            )
        {
            return;
        }
        self.schedule(now, reason);
    }
    pub fn tick(&mut self, now: Duration) -> Action {
        if self.paused {
            return Action::None;
        }
        match self.phase {
            Phase::Waiting { until } if now >= until => {
                self.recoveries += 1;
                self.begin_load(now);
                return Action::Reload;
            }
            Phase::Buffering => {
                let since = self.buffering_since.unwrap_or(self.started);
                let max_wait = if self.is_live {
                    self.policy.live_refill_max_wait
                } else {
                    self.policy.refill_max_wait
                };
                let progress_timeout = if self.has_played {
                    self.policy.live_refill_timeout
                } else {
                    self.policy.startup_timeout
                };
                if now.saturating_sub(since) >= max_wait {
                    self.schedule(now, RecoveryReason::RefillDeadline);
                } else if now.saturating_sub(self.last_ingress.max(self.last_progress))
                    >= if self.is_live {
                        progress_timeout
                    } else {
                        self.policy.refill_timeout
                    }
                {
                    self.schedule(now, RecoveryReason::RefillNoIngress);
                } else if self.is_live
                    && now.saturating_sub(self.last_refill_progress) >= progress_timeout
                {
                    self.schedule(now, RecoveryReason::RefillNoProgress);
                }
            }
            Phase::Connecting if !self.has_played => {
                let active_refill = self.last_ingress > self.started
                    && now.saturating_sub(self.last_ingress) < self.policy.refill_timeout;
                let timeout = if active_refill {
                    if self.is_live {
                        self.policy.live_refill_max_wait
                    } else {
                        self.policy.refill_max_wait.max(self.policy.startup_timeout)
                    }
                } else {
                    self.policy.startup_timeout
                };
                if now.saturating_sub(self.started) >= timeout {
                    self.schedule(now, RecoveryReason::StartupTimeout);
                }
            }
            Phase::Connecting | Phase::Playing
                if now.saturating_sub(self.last_progress) >= self.policy.stall_timeout =>
            {
                self.schedule(now, RecoveryReason::PlaybackStalled);
            }
            _ => {}
        }
        Action::None
    }
    fn schedule(&mut self, now: Duration, reason: RecoveryReason) {
        let quick_live_eof =
            reason == RecoveryReason::LiveEof && self.has_played && self.attempts == 0;
        self.attempts = self.attempts.saturating_add(1);
        self.last_reason = Some(reason);
        let multiplier = 1u32 << self.attempts.saturating_sub(1).min(10);
        let backoff = if quick_live_eof {
            Duration::ZERO
        } else {
            self.policy
                .retry_base
                .saturating_mul(multiplier)
                .min(self.policy.retry_max)
        };
        let jitter = Duration::from_millis(
            (self.generation.wrapping_mul(73) + u64::from(self.attempts) * 37) % 251,
        );
        self.phase = Phase::Waiting {
            until: now + backoff + jitter,
        };
        self.healthy_since = None;
    }
    pub fn resume_position(&self) -> Option<f64> {
        if self.is_live { None } else { self.position }
    }
    pub fn playback_stall_seconds(&self, now: Duration) -> f64 {
        now.saturating_sub(self.last_progress).as_secs_f64()
    }
    pub fn ingress_idle_seconds(&self, now: Duration) -> f64 {
        now.saturating_sub(self.last_ingress).as_secs_f64()
    }
}
