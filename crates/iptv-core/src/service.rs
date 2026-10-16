//! Versioned, paginated bridge. Each engine is serialized by the JNI owner.
use crate::{
    epg::Guide,
    playlist::{ContentKind, Playlist, filter_content, parse_reader_with_base},
    recovery::{Action, Phase, Recovery, RecoveryPolicy},
    telemetry::Telemetry,
};
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    fs::File,
    io::{BufRead, BufReader, Read},
    sync::{Arc, atomic::AtomicBool},
    time::{Duration, Instant},
};

pub const IMPORT_LIMIT: u64 = 128 * 1024 * 1024;
pub const CHANNEL_LIMIT: usize = 500_000;

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum Request {
    Clear,
    BuildCatalog {
        path: String,
        base: Option<String>,
        target: String,
        key: String,
        #[serde(default)]
        xtream: bool,
    },
    OpenCatalog {
        path: String,
        key: String,
    },
    Entry {
        revision: u64,
        id: usize,
    },
    ResolveSeries {
        revision: u64,
        id: usize,
        path: String,
    },
    Episodes {
        revision: u64,
        id: usize,
        season: Option<i64>,
        offset: usize,
    },
    Info,
    Import {
        path: String,
        base: Option<String>,
    },
    Xtream {
        path: String,
    },
    Categories,
    Search {
        revision: u64,
        kind: Option<String>,
        category: Option<usize>,
        query: String,
        offset: usize,
        limit: usize,
    },
    Select {
        revision: u64,
        id: usize,
    },
    Sample {
        attempt: u64,
        position: Option<f64>,
        buffered: Option<f64>,
        buffering: bool,
        cache_end: Option<f64>,
        ingress: Option<u64>,
    },
    /// One bridge round trip samples the player and advances the watchdog atomically.
    Poll {
        attempt: u64,
        position: Option<f64>,
        buffered: Option<f64>,
        buffering: bool,
        cache_end: Option<f64>,
        ingress: Option<u64>,
        paused: bool,
    },
    Ended {
        attempt: u64,
        error: bool,
    },
    Pause {
        paused: bool,
    },
    Seek,
    Stop,
    Reconnect,
    Tick,
    EpgImport {
        path: String,
    },
    Epg {
        revision: u64,
        id: usize,
        now: i64,
    },
}

pub struct Engine {
    playlist: Playlist,
    revision: u64,
    selected: Option<usize>,
    recovery: Recovery,
    attempt: u64,
    clock: Instant,
    guide: Guide,
    filter: Option<SearchResults>,
    paused: bool,
    import_ms: f64,
    catalog: Option<crate::catalog::Catalog>,
    pub cancelled: Arc<AtomicBool>,
}
struct SearchResults {
    kind: Option<ContentKind>,
    category: Option<usize>,
    query: String,
    ids: Vec<usize>,
}
fn kind_key(kind: ContentKind) -> &'static str {
    match kind {
        ContentKind::LiveTv => "live",
        ContentKind::Movie => "movies",
        ContentKind::Series => "series",
    }
}
fn parse_kind(kind: &str) -> Result<ContentKind, String> {
    match kind {
        "live" => Ok(ContentKind::LiveTv),
        "movies" => Ok(ContentKind::Movie),
        "series" => Ok(ContentKind::Series),
        _ => Err("invalid_kind".into()),
    }
}
impl Default for Engine {
    fn default() -> Self {
        Self {
            playlist: Playlist::default(),
            revision: 0,
            selected: None,
            recovery: Recovery::new(RecoveryPolicy::default()),
            attempt: 0,
            clock: Instant::now(),
            guide: Guide::default(),
            filter: None,
            paused: false,
            import_ms: 0.0,
            catalog: None,
            cancelled: Arc::new(AtomicBool::new(false)),
        }
    }
}
fn reader(path: &str) -> Result<BufReader<std::io::Take<File>>, String> {
    let file = File::open(path).map_err(|_| "import_open_failed")?;
    if file.metadata().map_err(|_| "import_open_failed")?.len() > IMPORT_LIMIT {
        return Err("import_too_large".into());
    }
    Ok(BufReader::with_capacity(
        64 * 1024,
        file.take(IMPORT_LIMIT + 1),
    ))
}
impl Engine {
    pub fn execute(&mut self, request: Request) -> Result<Value, String> {
        self.execute_at(request, self.clock.elapsed())
    }
    pub fn execute_at(&mut self, request: Request, now: Duration) -> Result<Value, String> {
        match request {
            Request::Clear => {
                self.catalog = None;
                self.playlist = Playlist::default();
                self.reset_library();
                Ok(json!({"revision":self.revision}))
            }
            Request::Info => Ok(
                json!({"core_version":"0.3.0","catalog_schema":1,"catalog_bytes":crate::catalog::BYTE_LIMIT,"catalog_entries":crate::catalog::ENTRY_LIMIT}),
            ),
            Request::BuildCatalog {
                path,
                base,
                target,
                key,
                xtream,
            } => {
                if std::path::Path::new(&target).exists() {
                    return Err("catalog_target_exists".into());
                }
                let started = Instant::now();
                let result = (|| {
                    let mut writer = crate::catalog::Writer::create(&target, &key)?;
                    writer.cancelled = Some(self.cancelled.clone());
                    let summary = if xtream {
                        crate::xtream::build_manifest(&path, &mut writer)?
                    } else {
                        let base = base
                            .as_deref()
                            .map(url::Url::parse)
                            .transpose()
                            .map_err(|_| "invalid_base")?;
                        crate::catalog::build_m3u(&path, base.as_ref(), &mut writer)?
                    };
                    let catalog = writer.finish(summary.skipped)?;
                    Ok(
                        json!({"count":catalog.count,"skipped":catalog.skipped,"import_ms":started.elapsed().as_secs_f64()*1000.0}),
                    )
                })();
                if result.is_err() {
                    let _ = std::fs::remove_file(&target);
                    let _ = std::fs::remove_file(format!("{target}-journal"));
                }
                result
            }
            Request::OpenCatalog { path, key } => {
                let catalog = crate::catalog::Catalog::open(&path, &key)?;
                let result = json!({"revision":self.revision+1,"count":catalog.count,"skipped":catalog.skipped});
                self.catalog = Some(catalog);
                self.playlist = Playlist::default();
                self.reset_library();
                Ok(result)
            }
            Request::Entry { revision, id } => {
                self.check_revision(revision)?;
                let c = self.channel(id)?;
                Ok(json!({"id":id,"series_id":c.series_id,"name":c.name}))
            }
            Request::ResolveSeries { revision, id, path } => {
                self.check_revision(revision)?;
                self.catalog
                    .as_mut()
                    .ok_or("catalog_required")?
                    .resolve_series(id, &path)
            }
            Request::Episodes {
                revision,
                id,
                season,
                offset,
            } => {
                self.check_revision(revision)?;
                self.catalog
                    .as_ref()
                    .ok_or("catalog_required")?
                    .episode_page(id, season, offset)
            }
            Request::Import { path, base } => {
                let import_started = Instant::now();
                let base = base
                    .map(|s| url::Url::parse(&s).map_err(|_| "invalid_base"))
                    .transpose()?;
                let mut input = reader(&path)?;
                let prefix =
                    String::from_utf8_lossy(input.fill_buf().map_err(|_| "import_read_failed")?);
                let hls = prefix.lines().any(|line| {
                    line.starts_with("#EXT-X-TARGETDURATION:")
                        || line.starts_with("#EXT-X-STREAM-INF:")
                });
                let playlist = if hls {
                    let address = base.as_ref().ok_or("hls_requires_remote_url")?;
                    if !matches!(address.scheme(), "http" | "https") {
                        return Err("invalid_base".into());
                    }
                    let mut finite = false;
                    for line in input.lines() {
                        if line
                            .map_err(|_| "import_read_failed")?
                            .starts_with("#EXT-X-ENDLIST")
                        {
                            finite = true;
                        }
                    }
                    let mut playlist = crate::playlist::parse_reader(std::io::Cursor::new(
                        format!("#EXTINF:-1,HLS-Stream\n{address}\n"),
                    ))
                    .map_err(|_| "invalid_playlist")?;
                    playlist.channels[0].is_live = !finite;
                    let kind = if finite {
                        ContentKind::Movie
                    } else {
                        ContentKind::LiveTv
                    };
                    playlist.channels[0].kind = kind;
                    playlist.categories[0].kind = kind;
                    playlist
                } else {
                    parse_reader_with_base(input, base.as_ref())
                        .map_err(|e| crate::catalog::io_code(&e))?
                };
                let mut result = self.replace(playlist)?;
                self.import_ms = import_started.elapsed().as_secs_f64() * 1000.0;
                result["import_ms"] = json!(self.import_ms);
                Ok(result)
            }
            Request::Xtream { path } => {
                let v: Value =
                    serde_json::from_reader(reader(&path)?).map_err(|_| "invalid_xtream")?;
                let playlist = crate::xtream::parse(
                    v["server"].as_str().ok_or("invalid_server")?,
                    v["username"].as_str().ok_or("missing_username")?,
                    v["password"].as_str().ok_or("missing_password")?,
                    &v["account"],
                    &v["live_categories"],
                    &v["live"],
                    v.get("movies").map(|m| (&v["movie_categories"], m)),
                )?;
                self.replace(playlist)
            }
            Request::Categories => {
                if let Some(catalog) = &self.catalog {
                    catalog.categories(self.revision, self.import_ms)
                } else {
                    Ok(
                        json!({"revision": self.revision, "count": self.playlist.channels.len(), "import_ms":self.import_ms,
                    "preferred_kind":kind_key(self.playlist.preferred_kind()),
                    "kinds":ContentKind::ALL.map(|kind| json!({"id":kind_key(kind),"name":kind.label(),"count":self.playlist.count(kind)})),
                    "categories": self.playlist.categories.iter().enumerate().map(|(id,c)| json!({"id": id, "name": c.name, "kind":kind_key(c.kind), "count": c.channels.len()})).collect::<Vec<_>>()}),
                    )
                }
            }
            Request::Search {
                revision,
                kind,
                category,
                query,
                offset,
                limit,
            } => {
                self.check_revision(revision)?;
                let kind = kind.as_deref().map(parse_kind).transpose()?;
                if query.len() > 1024 {
                    return Err("query_too_large".into());
                }
                if let Some(catalog) = &self.catalog {
                    return catalog.search(revision, kind, category, &query, offset, limit);
                }
                if category.is_some_and(|id| id >= self.playlist.categories.len()) {
                    return Err("invalid_category".into());
                }
                if !self.filter.as_ref().is_some_and(|cached| {
                    cached.kind == kind && cached.category == category && cached.query == query
                }) {
                    self.filter = Some(SearchResults {
                        kind,
                        category,
                        query: query.clone(),
                        ids: filter_content(&self.playlist, kind, category, &query),
                    });
                }
                let ids = &self.filter.as_ref().ok_or("missing_filter")?.ids;
                let items: Vec<_> = ids
                    .iter()
                    .skip(offset)
                    .take(limit.clamp(1, 128))
                    .map(|&id| {
                        let c = &self.playlist.channels[id];
                        json!({"id":id, "name":c.name, "category":c.category, "kind":kind_key(c.kind), "live":c.is_live})
                    })
                    .collect();
                Ok(json!({"revision": self.revision, "total": ids.len(), "items": items}))
            }
            Request::Select { revision, id } => {
                self.check_revision(revision)?;
                let channel = self.channel(id)?;
                if channel.series_id.is_some() {
                    return Err("series_requires_episode".into());
                }
                let scheme = url::Url::parse(&channel.url).map_err(|_| "invalid_stream")?;
                if !matches!(scheme.scheme(), "http" | "https" | "rtsp") {
                    return Err("unsupported_protocol".into());
                }
                self.selected = Some(id);
                self.paused = false;
                self.recovery.select(now, channel.is_live);
                self.load()
            }
            Request::Sample {
                attempt,
                position,
                buffered,
                buffering,
                cache_end,
                ingress,
            } => {
                if attempt == self.attempt {
                    self.recovery.observe(
                        self.recovery.generation,
                        now,
                        &Telemetry {
                            position_seconds: position,
                            buffered_seconds: buffered,
                            paused_for_cache: buffering,
                            cache_end_seconds: cache_end,
                            input_bytes_per_second: ingress,
                            ..Default::default()
                        },
                    );
                }
                Ok(self.status())
            }
            Request::Ended { attempt, error } => {
                if attempt == self.attempt {
                    self.recovery
                        .ended(self.recovery.generation, now, error, !error);
                }
                Ok(self.status_at(now))
            }
            Request::Poll {
                attempt,
                position,
                buffered,
                buffering,
                cache_end,
                ingress,
                paused,
            } => {
                if attempt != self.attempt {
                    return Ok(self.status_at(now));
                }
                if self.paused != paused {
                    self.recovery.pause(now, paused);
                    self.paused = paused;
                }
                self.recovery.observe(
                    self.recovery.generation,
                    now,
                    &Telemetry {
                        position_seconds: position,
                        buffered_seconds: buffered,
                        paused_for_cache: buffering,
                        cache_end_seconds: cache_end,
                        input_bytes_per_second: ingress,
                        ..Default::default()
                    },
                );
                self.tick(now)
            }
            Request::Pause { paused } => {
                if self.paused != paused {
                    self.recovery.pause(now, paused);
                    self.paused = paused;
                }
                Ok(self.status())
            }
            Request::Seek => {
                self.recovery.seek(now);
                Ok(self.status())
            }
            Request::Stop => {
                self.selected = None;
                self.paused = false;
                self.attempt += 1;
                self.recovery.stop();
                Ok(self.status())
            }
            Request::Reconnect => {
                if self.selected.is_none() {
                    return Err("no_selection".into());
                }
                self.recovery.begin_load(now);
                self.paused = false;
                self.load()
            }
            Request::Tick => self.tick(now),
            Request::EpgImport { path } => {
                let guide = Guide::parse(reader(&path)?)?;
                let channels = guide.0.len();
                self.guide = guide;
                Ok(json!({"channels":channels}))
            }
            Request::Epg { revision, id, now } => {
                self.check_revision(revision)?;
                let c = self.channel(id)?;
                Ok(json!({"programmes": self.guide.now_next(&c.tvg_id, now)}))
            }
        }
    }
    fn check_revision(&self, revision: u64) -> Result<(), String> {
        if revision == self.revision {
            Ok(())
        } else {
            Err("stale_revision".into())
        }
    }
    fn tick(&mut self, now: Duration) -> Result<Value, String> {
        if self.recovery.tick(now) == Action::Reload {
            self.load()
        } else {
            Ok(self.status_at(now))
        }
    }
    fn replace(&mut self, playlist: Playlist) -> Result<Value, String> {
        if playlist.channels.is_empty() {
            return Err("empty_playlist".into());
        }
        if playlist.channels.len() > CHANNEL_LIMIT {
            return Err("too_many_channels".into());
        }
        self.playlist = playlist;
        self.catalog = None;
        self.reset_library();
        Ok(
            json!({"revision":self.revision,"count":self.playlist.channels.len(),"skipped":self.playlist.skipped}),
        )
    }
    fn channel(&self, id: usize) -> Result<crate::playlist::Channel, String> {
        match &self.catalog {
            Some(c) => c.channel(id),
            None => self
                .playlist
                .channels
                .get(id)
                .cloned()
                .ok_or("invalid_channel".into()),
        }
    }
    fn reset_library(&mut self) {
        self.revision += 1;
        self.filter = None;
        // Current media continues in Android until an explicit channel selection. Rust cancels its old watchdog.
        self.selected = None;
        self.paused = false;
        self.attempt += 1;
        self.recovery.stop();
    }
    fn load(&mut self) -> Result<Value, String> {
        let channel = self.channel(self.selected.ok_or("no_selection")?)?;
        self.attempt += 1;
        Ok(
            json!({"action":"load", "attempt":self.attempt, "url":channel.url, "name":channel.name, "live":channel.is_live, "user_agent":channel.headers.user_agent, "referrer":channel.headers.referrer, "resume":self.recovery.resume_position()}),
        )
    }
    fn status(&self) -> Value {
        let phase = match self.recovery.phase {
            Phase::Idle => "idle",
            Phase::Connecting => "connecting",
            Phase::Playing => "playing",
            Phase::Buffering => "buffering",
            Phase::Paused => "paused",
            Phase::Waiting { .. } => "waiting",
            Phase::Finished => "finished",
        };
        json!({"action":"none", "phase": phase, "attempt":self.attempt, "retries":self.recovery.attempts, "recoveries":self.recovery.recoveries})
    }
    fn status_at(&self, now: Duration) -> Value {
        let mut status = self.status();
        if let Phase::Waiting { until } = self.recovery.phase {
            // Round up so the Android timer cannot fire just before the deadline.
            status["retry_in_ms"] =
                json!(until.saturating_sub(now).as_nanos().div_ceil(1_000_000) as u64);
        }
        status
    }
}
