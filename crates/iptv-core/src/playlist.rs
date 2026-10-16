use std::{
    collections::HashMap,
    fmt,
    fs::File,
    io::{self, BufRead, BufReader},
    path::Path,
};

#[derive(Clone, Default, PartialEq, Eq)]
pub struct StreamHeaders {
    pub user_agent: Option<String>,
    pub referrer: Option<String>,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum ContentKind {
    #[default]
    LiveTv,
    Movie,
    Series,
}

impl ContentKind {
    pub const ALL: [Self; 3] = [Self::LiveTv, Self::Movie, Self::Series];

    pub fn label(self) -> &'static str {
        match self {
            Self::LiveTv => "Live-TV",
            Self::Movie => "Filme",
            Self::Series => "Serien",
        }
    }
}

#[derive(Clone)]
pub struct Channel {
    pub name: String,
    pub kind: ContentKind,
    pub category: usize,
    pub url: String,
    pub tvg_id: String,
    pub logo: Option<String>,
    pub headers: StreamHeaders,
    pub is_live: bool,
    pub search_key: String,
    pub series_id: Option<String>,
}

// Provider URLs often contain credentials. They must never enter debug logs.
impl fmt::Debug for Channel {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Channel")
            .field("name", &self.name)
            .field("category", &self.category)
            .field("url", &"[redacted]")
            .finish()
    }
}

#[derive(Debug)]
pub struct Category {
    pub name: String,
    pub kind: ContentKind,
    pub channels: Vec<usize>,
}

#[derive(Debug, Default)]
pub struct Playlist {
    pub channels: Vec<Channel>,
    pub categories: Vec<Category>,
    pub skipped: usize,
}

impl Playlist {
    pub fn count(&self, kind: ContentKind) -> usize {
        self.categories
            .iter()
            .filter(|group| group.kind == kind)
            .map(|group| group.channels.len())
            .sum()
    }

    pub fn preferred_kind(&self) -> ContentKind {
        ContentKind::ALL
            .into_iter()
            .find(|kind| self.count(*kind) > 0)
            .unwrap_or_default()
    }
}

#[derive(Default)]
struct Entry {
    name: String,
    group: String,
    id: String,
    logo: Option<String>,
    duration: f64,
    kind: Option<ContentKind>,
    headers: StreamHeaders,
}

/// Streaming, bounded-line parser; never fetches URLs while importing a playlist.
pub fn parse_file(path: &Path) -> io::Result<Playlist> {
    parse_reader(BufReader::with_capacity(256 * 1024, File::open(path)?))
}

pub fn parse_reader(mut reader: impl BufRead) -> io::Result<Playlist> {
    parse_reader_with_base(&mut reader, None)
}

pub fn parse_reader_with_base(
    reader: impl BufRead,
    base: Option<&url::Url>,
) -> io::Result<Playlist> {
    let mut result = Playlist::default();
    let mut groups = HashMap::<(ContentKind, String), usize>::new();
    let summary = parse_stream(reader, base, usize::MAX, |mut channel, group| {
        let category = *groups
            .entry((channel.kind, group.clone()))
            .or_insert_with(|| {
                let index = result.categories.len();
                result.categories.push(Category {
                    name: group,
                    kind: channel.kind,
                    channels: Vec::new(),
                });
                index
            });
        channel.category = category;
        result.categories[category]
            .channels
            .push(result.channels.len());
        result.channels.push(channel);
        Ok(())
    })?;
    result.skipped = summary.skipped;
    Ok(result)
}

pub struct ParseSummary {
    pub count: usize,
    pub skipped: usize,
}

/// Emits one entry at a time; the sink owns storage and bounds total allocations.
pub fn parse_stream(
    mut reader: impl BufRead,
    base: Option<&url::Url>,
    limit: usize,
    mut emit: impl FnMut(Channel, String) -> io::Result<()>,
) -> io::Result<ParseSummary> {
    let mut result = ParseSummary {
        count: 0,
        skipped: 0,
    };
    let mut format_verified = false;
    let mut pending: Option<Entry> = None;
    let mut line = Vec::with_capacity(1024);
    let mut default_headers = StreamHeaders::default();
    // Reject an oversized line before allowing it to consume unbounded memory.
    const MAX_LINE: usize = 1024 * 1024;
    loop {
        line.clear();
        let mut oversized = false;
        loop {
            let buffer = reader.fill_buf()?;
            if buffer.is_empty() {
                break;
            }
            let n = buffer
                .iter()
                .position(|&b| b == b'\n')
                .map_or(buffer.len(), |p| p + 1);
            if line.len() + n <= MAX_LINE && !oversized {
                line.extend_from_slice(&buffer[..n]);
            } else {
                oversized = true;
            }
            let ended = buffer[n - 1] == b'\n';
            reader.consume(n);
            if ended {
                break;
            }
        }
        if oversized {
            result.skipped += 1;
            pending = None;
            continue;
        }
        if line.is_empty() {
            break;
        }
        let decoded = String::from_utf8_lossy(&line);
        let text = decoded.trim().trim_start_matches('\u{feff}');
        if text.is_empty() {
            continue;
        }
        if !format_verified {
            if text.starts_with("#EXTM3U") || text.starts_with("#EXTINF:") {
                format_verified = true;
            } else if !text.starts_with('#') {
                let absolute = text.split('|').next().unwrap_or(text);
                if !url::Url::parse(absolute).is_ok_and(|u| {
                    matches!(
                        u.scheme(),
                        "http" | "https" | "rtsp" | "rtmp" | "rtmps" | "udp" | "rtp"
                    ) && u.host_str().is_some()
                }) {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidData,
                        "invalid_playlist_format",
                    ));
                }
                format_verified = true;
            }
        }
        if let Some(info) = text.strip_prefix("#EXTINF:") {
            if pending.is_some() {
                result.skipped += 1;
            }
            let (metadata, title) = split_title(info);
            let attrs = attributes(metadata);
            let name = if title.trim().is_empty() {
                attrs.get("tvg-name").map(str::to_owned).unwrap_or_default()
            } else {
                title.trim().to_owned()
            };
            pending = Some(Entry {
                name,
                group: attrs
                    .get("group-title")
                    .map(str::to_owned)
                    .unwrap_or_default(),
                id: attrs.get("tvg-id").map(str::to_owned).unwrap_or_default(),
                logo: attrs
                    .get("tvg-logo")
                    .filter(|v| !v.is_empty())
                    .map(str::to_owned),
                duration: metadata
                    .split_whitespace()
                    .next()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(-1.0),
                kind: ["content-type", "tvg-type", "stream-type", "type"]
                    .iter()
                    .filter_map(|key| attrs.get(key))
                    .find_map(kind_hint),
                headers: default_headers.clone(),
            });
        } else if let Some(group) = text.strip_prefix("#EXTGRP:") {
            if let Some(entry) = &mut pending {
                entry.group = group.trim().to_owned();
            }
        } else if let Some(option) = text.strip_prefix("#EXTVLCOPT:") {
            if let Some((key, value)) = option.split_once('=') {
                let headers = pending
                    .as_mut()
                    .map(|e| &mut e.headers)
                    .unwrap_or(&mut default_headers);
                apply_header(headers, key, value);
            }
        } else if !text.starts_with('#') {
            if text.starts_with(['<', '{', '[']) || text.contains('\0') {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid_playlist_format",
                ));
            }
            let mut entry = pending.take().unwrap_or_default();
            let (raw_url, options) = text.split_once('|').unwrap_or((text, ""));
            for (key, value) in url::form_urlencoded::parse(options.as_bytes()) {
                apply_header(&mut entry.headers, &key, &value);
            }
            let Ok(parsed) = url::Url::parse(raw_url)
                .or_else(|error| base.map_or(Err(error), |base| base.join(raw_url)))
            else {
                result.skipped += 1;
                continue;
            };
            if !matches!(
                parsed.scheme(),
                "http" | "https" | "rtsp" | "rtmp" | "rtmps" | "udp" | "rtp"
            ) || parsed.host_str().is_none()
                || raw_url.contains('\0')
            {
                result.skipped += 1;
                continue;
            }
            if entry.name.is_empty() {
                entry.name = format!("Kanal {}", result.count + 1);
            }
            let kind = classify(&entry, &parsed);
            let group = if entry.group.trim().is_empty() {
                "Ohne Kategorie".to_owned()
            } else {
                entry.group.trim().to_owned()
            };
            if result.count >= limit {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "too_many_channels",
                ));
            }
            let search_key = format!("{} {} {}", entry.name, group, entry.id).to_lowercase();
            emit(
                Channel {
                    name: entry.name,
                    kind,
                    category: 0,
                    url: if base.is_some() {
                        parsed.to_string()
                    } else {
                        raw_url.to_owned()
                    },
                    tvg_id: entry.id,
                    logo: entry.logo,
                    headers: entry.headers,
                    is_live: kind == ContentKind::LiveTv,
                    search_key,
                    series_id: None,
                },
                group,
            )?;
            result.count += 1;
        }
    }
    if pending.is_some() {
        result.skipped += 1;
    }
    Ok(result)
}

fn kind_hint(value: &str) -> Option<ContentKind> {
    match value.trim().to_lowercase().as_str() {
        "live" | "live-tv" | "live tv" | "livetv" | "tv" => Some(ContentKind::LiveTv),
        "movie" | "movies" | "film" | "films" | "filme" | "vod" | "video" => {
            Some(ContentKind::Movie)
        }
        "series" | "serie" | "serien" | "tv shows" | "tvshow" | "tvshows" | "show" | "shows"
        | "episode" => Some(ContentKind::Series),
        _ => None,
    }
}

// M3U providers use different hints. Prefer explicit metadata and stream routes;
// only use group words, episode names and file extensions when those are absent.
fn classify(entry: &Entry, url: &url::Url) -> ContentKind {
    if let Some(kind) = entry.kind {
        return kind;
    }
    let path = url.path();
    // Xtream routes end in /{live|movie|series}/user/password/id.ext.
    let segments = path.trim_start_matches('/');
    let route = segments
        .rsplit('/')
        .nth(3)
        .unwrap_or_else(|| segments.split('/').next().unwrap_or(""));
    let route = route.to_ascii_lowercase();
    if let Some(kind) = match route.as_str() {
        "live" => Some(ContentKind::LiveTv),
        "movie" | "movies" => Some(ContentKind::Movie),
        "series" => Some(ContentKind::Series),
        _ => None,
    } {
        return kind;
    }
    for (key, value) in url.query_pairs() {
        if matches!(key.as_ref(), "type" | "stream_type" | "content_type")
            && let Some(kind) = kind_hint(&value)
        {
            return kind;
        }
    }
    let group = entry.group.to_lowercase();
    let words: Vec<_> = group.split(|c: char| !c.is_alphanumeric()).collect();
    if words.iter().any(|word| {
        matches!(
            *word,
            "series" | "serie" | "serien" | "shows" | "tvshows" | "staffel"
        )
    }) {
        return ContentKind::Series;
    }
    if words.iter().any(|word| {
        matches!(
            *word,
            "movie" | "movies" | "film" | "films" | "filme" | "vod" | "cinema" | "kino"
        )
    }) {
        return ContentKind::Movie;
    }
    if words.iter().any(|word| matches!(*word, "live" | "livetv")) {
        return ContentKind::LiveTv;
    }
    if episode_name(&entry.name) {
        return ContentKind::Series;
    }
    if entry.duration > 0.0
        || [
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".wmv", ".flv",
        ]
        .iter()
        .any(|ext| {
            path.get(path.len().saturating_sub(ext.len())..)
                .is_some_and(|tail| tail.eq_ignore_ascii_case(ext))
        })
    {
        ContentKind::Movie
    } else {
        ContentKind::LiveTv
    }
}

fn episode_name(name: &str) -> bool {
    let name = name.to_ascii_lowercase();
    let bytes = name.as_bytes();
    for start in 0..bytes.len() {
        if start > 0 && bytes[start - 1].is_ascii_alphanumeric() {
            continue;
        }
        let season_start = start + usize::from(bytes[start] == b's');
        let mut cursor = season_start;
        while cursor < bytes.len() && bytes[cursor].is_ascii_digit() {
            cursor += 1;
        }
        if !(1..=3).contains(&(cursor - season_start))
            || cursor >= bytes.len()
            || bytes[cursor] != if bytes[start] == b's' { b'e' } else { b'x' }
        {
            continue;
        }
        cursor += 1;
        let episode_start = cursor;
        while cursor < bytes.len() && bytes[cursor].is_ascii_digit() {
            cursor += 1;
        }
        if (1..=4).contains(&(cursor - episode_start))
            && (cursor == bytes.len() || !bytes[cursor].is_ascii_alphanumeric())
        {
            return true;
        }
    }
    false
}

fn apply_header(headers: &mut StreamHeaders, key: &str, value: &str) {
    // Only explicitly supported headers; no arbitrary mpv options from a playlist.
    if value.contains(['\r', '\n', '\0']) || value.len() > 4096 {
        return;
    }
    match key.to_ascii_lowercase().as_str() {
        "http-user-agent" | "user-agent" => headers.user_agent = Some(value.to_owned()),
        "http-referrer" | "http-referer" | "referer" | "referrer" => {
            headers.referrer = Some(value.to_owned())
        }
        _ => {}
    }
}

fn split_title(info: &str) -> (&str, &str) {
    let mut quote = None;
    for (i, c) in info.char_indices() {
        if Some(c) == quote {
            quote = None;
        } else if quote.is_none() && (c == '"' || c == '\'') {
            quote = Some(c);
        } else if c == ',' && quote.is_none() {
            return (&info[..i], &info[i + 1..]);
        }
    }
    (info, "")
}

const ATTRIBUTE_KEYS: [&str; 8] = [
    "tvg-name",
    "group-title",
    "tvg-id",
    "tvg-logo",
    "content-type",
    "tvg-type",
    "stream-type",
    "type",
];
struct Attributes<'a> {
    values: [Option<&'a str>; 8],
}
impl<'a> Attributes<'a> {
    fn get(&self, key: &str) -> Option<&'a str> {
        ATTRIBUTE_KEYS
            .iter()
            .position(|k| *k == key)
            .and_then(|i| self.values[i])
    }
}
fn attributes(metadata: &str) -> Attributes<'_> {
    let bytes = metadata.as_bytes();
    let mut i = 0;
    let mut attrs = Attributes { values: [None; 8] };
    while i < bytes.len() {
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let start = i;
        while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b'=' {
            i += 1;
        }
        let end = i;
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i == bytes.len() {
            break;
        }
        if bytes[i] != b'=' {
            continue;
        }
        i += 1;
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        let value;
        if i < bytes.len() && matches!(bytes[i], b'"' | b'\'') {
            let quote = bytes[i];
            i += 1;
            let begin = i;
            while i < bytes.len() && bytes[i] != quote {
                i += 1;
            }
            value = &metadata[begin..i];
            if i < bytes.len() {
                i += 1;
            }
        } else {
            let begin = i;
            while i < bytes.len() && !bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            value = &metadata[begin..i];
        }
        if start < end
            && let Some(index) = ATTRIBUTE_KEYS
                .iter()
                .position(|key| metadata[start..end].eq_ignore_ascii_case(key))
        {
            attrs.values[index] = Some(value);
        }
    }
    attrs
}

pub fn filter_channels(playlist: &Playlist, category: Option<usize>, query: &str) -> Vec<usize> {
    filter_content(playlist, None, category, query)
}

pub fn filter_content(
    playlist: &Playlist,
    kind: Option<ContentKind>,
    category: Option<usize>,
    query: &str,
) -> Vec<usize> {
    let terms: Vec<_> = query.split_whitespace().map(str::to_lowercase).collect();
    let matches = |index: &usize| {
        kind.is_none_or(|kind| playlist.channels[*index].kind == kind)
            && terms
                .iter()
                .all(|term| playlist.channels[*index].search_key.contains(term))
    };
    match category.and_then(|index| playlist.categories.get(index)) {
        Some(group) => group.channels.iter().copied().filter(matches).collect(),
        None => (0..playlist.channels.len()).filter(matches).collect(),
    }
}
