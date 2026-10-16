use serde::{Deserialize, Serialize};
use std::{collections::VecDeque, time::Duration};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct BufferSettings {
    pub target_seconds: f64,
    pub max_memory_mib: u32,
    pub adaptive_refill: bool,
    pub adaptive_hls: bool,
}

impl Default for BufferSettings {
    fn default() -> Self {
        Self {
            target_seconds: 3.0,
            max_memory_mib: 32,
            adaptive_refill: true,
            adaptive_hls: false,
        }
    }
}

impl BufferSettings {
    pub fn normalized(mut self) -> Self {
        if !self.target_seconds.is_finite() {
            self.target_seconds = Self::default().target_seconds;
        }
        self.target_seconds = self.target_seconds.clamp(1.0, 120.0);
        self.max_memory_mib = self.max_memory_mib.clamp(16, 512);
        self
    }

    pub fn initial_refill_seconds(&self) -> f64 {
        (self.target_seconds / 6.0).clamp(0.25, 3.0)
    }
    pub fn maximum_refill_seconds(&self) -> f64 {
        (self.target_seconds / 3.0).clamp(0.5, 8.0)
    }
}

/// Hysteresis changes the resume watermark, not the user's configured cache ceiling.
pub struct RefillController {
    settings: BufferSettings,
    episodes: VecDeque<Duration>,
    buffering: bool,
    pub watermark: f64,
    last_underrun: Option<Duration>,
}

impl RefillController {
    pub fn new(settings: BufferSettings) -> Self {
        let settings = settings.normalized();
        let watermark = settings.initial_refill_seconds();
        Self {
            settings,
            episodes: VecDeque::new(),
            buffering: false,
            watermark,
            last_underrun: None,
        }
    }

    pub fn observe(&mut self, now: Duration, buffering: bool, has_played: bool) -> Option<f64> {
        let entering = buffering && !self.buffering && has_played;
        self.buffering = buffering;
        while self
            .episodes
            .front()
            .is_some_and(|time| now.saturating_sub(*time) > Duration::from_secs(60))
        {
            self.episodes.pop_front();
        }
        let old = self.watermark;
        if entering {
            self.episodes.push_back(now);
            self.last_underrun = Some(now);
            if self.settings.adaptive_refill && self.episodes.len() >= 2 {
                self.watermark = (self.watermark * 1.5).min(self.settings.maximum_refill_seconds());
            }
        } else if self.settings.adaptive_refill
            && !buffering
            && self
                .last_underrun
                .is_some_and(|time| now.saturating_sub(time) >= Duration::from_secs(120))
        {
            self.watermark = self.settings.initial_refill_seconds();
            self.last_underrun = None;
        }
        ((self.watermark - old).abs() > 0.001).then_some(self.watermark)
    }

    pub fn underruns_in_window(&self) -> usize {
        self.episodes.len()
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct HlsVariant {
    pub video_id: i64,
    pub bitrate: u64,
    pub audio_id: Option<i64>,
}

/// Only use variants actually advertised by a multivariant HLS source.
pub fn hls_variants(tracks: &serde_json::Value) -> Vec<HlsVariant> {
    let Some(tracks) = tracks.as_array() else {
        return Vec::new();
    };
    let mut variants = Vec::new();
    for track in tracks {
        if track.get("type").and_then(|v| v.as_str()) != Some("video")
            || track.get("albumart").and_then(|v| v.as_bool()) == Some(true)
        {
            continue;
        }
        let (Some(video_id), Some(bitrate)) = (
            track.get("id").and_then(|v| v.as_i64()),
            track
                .get("hls-bitrate")
                .and_then(|v| v.as_u64())
                .filter(|b| *b > 0),
        ) else {
            continue;
        };
        let program = track.get("program-id").and_then(|v| v.as_i64());
        let audio_id = tracks
            .iter()
            .find(|audio| {
                audio.get("type").and_then(|v| v.as_str()) == Some("audio")
                    && program.is_some()
                    && audio.get("program-id").and_then(|v| v.as_i64()) == program
            })
            .and_then(|audio| audio.get("id").and_then(|v| v.as_i64()));
        variants.push(HlsVariant {
            video_id,
            bitrate,
            audio_id,
        });
    }
    variants.sort_by_key(|variant| variant.bitrate);
    variants.dedup_by_key(|variant| variant.video_id);
    variants
}

pub fn lower_hls_variant(
    variants: &[HlsVariant],
    selected: i64,
    input_rate: Option<u64>,
) -> Option<&HlsVariant> {
    let current = variants.iter().find(|v| v.video_id == selected)?;
    let lower: Vec<_> = variants
        .iter()
        .filter(|v| v.bitrate < current.bitrate)
        .collect();
    let budget = input_rate.map(|rate| rate.saturating_mul(8).saturating_mul(3) / 4);
    lower
        .iter()
        .rev()
        .find(|variant| budget.is_some_and(|limit| variant.bitrate <= limit))
        .copied()
        .or_else(|| lower.first().copied())
}
