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
    Unclassified,
}

impl ContentKind {
    pub const ALL: [Self; 4] = [Self::LiveTv, Self::Movie, Self::Series, Self::Unclassified];

    pub fn label(self) -> &'static str {
        match self {
            Self::LiveTv => "Live-TV",
            Self::Movie => "Filme",
            Self::Series => "Serien",
            Self::Unclassified => "Unzugeordnet",
        }
    }
}

#[derive(Clone, Default)]
pub struct Channel {
    pub name: String,
    pub display_name: String,
    pub tvg_name: String,
    pub group_title: String,
    pub attributes: HashMap<String, String>,
    pub url_path: String,
    pub extension: Option<String>,
    pub year: Option<u16>,
    pub quality: Option<String>,
    pub episode: Option<EpisodeInfo>,
    pub series_id: Option<String>,
    pub series_index: Option<usize>,
    pub kind: ContentKind,
    pub category: usize,
    pub url: String,
    pub tvg_id: String,
    pub logo: Option<String>,
    pub headers: StreamHeaders,
    pub is_live: bool,
    pub search_key: String,
}

impl Channel {
    pub fn with_group(mut self, group: &str) -> Self {
        self.enrich(group);
        self
    }
    /// Fill derived display metadata for provider-created entries as well as M3U.
    pub fn enrich(&mut self, group: &str) {
        if self.display_name.is_empty() {
            self.display_name = self.name.clone();
        }
        if self.tvg_name.is_empty() {
            self.tvg_name = self.name.clone();
        }
        if self.group_title.is_empty() {
            self.group_title = group.into();
        }
        if let Ok(url) = url::Url::parse(&self.url) {
            self.url_path = url.path().into();
            self.extension = stream_extension(&url);
        }
        self.year = self.year.or_else(|| release_year(&self.name));
        self.quality = self
            .quality
            .take()
            .or_else(|| quality_label(&self.name))
            .or_else(|| quality_label(group));
    }
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
    pub series: Vec<Series>,
}

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct EpisodeInfo {
    pub series_title: String,
    pub season: u32,
    pub episode: u32,
}

#[derive(Debug)]
pub struct Season {
    // None retains episodes with no reliable season/episode marker.
    pub number: Option<u32>,
    pub episodes: Vec<usize>,
}

#[derive(Debug)]
pub struct Series {
    pub title: String,
    pub logo: Option<String>,
    pub categories: Vec<usize>,
    pub seasons: Vec<Season>,
}

pub fn stream_extension(url: &url::Url) -> Option<String> {
    url.path()
        .rsplit('/')
        .next()?
        .rsplit_once('.')
        .map(|(_, ext)| ext.to_ascii_lowercase())
        .filter(|ext| !ext.is_empty())
}

pub fn release_year(title: &str) -> Option<u16> {
    title
        .split(|c: char| !c.is_ascii_digit())
        .filter(|part| part.len() == 4)
        .filter_map(|part| part.parse().ok())
        .find(|year| (1888..=2100).contains(year))
}

pub fn quality_label(title: &str) -> Option<String> {
    let upper = title.to_uppercase();
    let words: Vec<_> = upper.split(|c: char| !c.is_alphanumeric()).collect();
    ["4K", "UHD", "2160P", "FHD", "1080P", "HD", "720P", "SD"]
        .into_iter()
        .find(|value| words.contains(value))
        .map(str::to_owned)
}

/// Match bounded S01 E05, S01E05, 1x05 and Season 1 Episode 5 markers.
/// Slice the original UTF-8 only at ASCII boundaries; never alter Unicode titles.
pub fn parse_episode(title: &str) -> Option<EpisodeInfo> {
    let lower = title.to_ascii_lowercase();
    let bytes = lower.as_bytes();
    fn number(bytes: &[u8], cursor: &mut usize) -> Option<u32> {
        let start = *cursor;
        while *cursor < bytes.len() && bytes[*cursor].is_ascii_digit() {
            *cursor += 1;
        }
        if *cursor == start || *cursor - start > 4 {
            return None;
        }
        std::str::from_utf8(&bytes[start..*cursor])
            .ok()?
            .parse()
            .ok()
    }
    fn spaces(bytes: &[u8], cursor: &mut usize) {
        while *cursor < bytes.len() && matches!(bytes[*cursor], b' ' | b'\t' | b'.' | b'_' | b'-') {
            *cursor += 1;
        }
    }
    for (start, _) in lower.char_indices() {
        if start > 0
            && title[..start]
                .chars()
                .next_back()
                .is_some_and(char::is_alphanumeric)
        {
            continue;
        }
        let mut cursor = start;
        let long = lower[start..].starts_with("season");
        let short = bytes[start] == b's';
        if long {
            cursor += 6;
            spaces(bytes, &mut cursor);
        } else if short {
            cursor += 1;
        }
        let Some(season) = number(bytes, &mut cursor) else {
            continue;
        };
        if season > 999 || (!long && !short && season > 99) {
            continue;
        }
        spaces(bytes, &mut cursor);
        let separator = if long {
            "episode"
        } else if short {
            "e"
        } else {
            "x"
        };
        if !lower[cursor..].starts_with(separator) {
            continue;
        }
        cursor += separator.len();
        spaces(bytes, &mut cursor);
        let Some(episode) = number(bytes, &mut cursor) else {
            continue;
        };
        if title[cursor..]
            .chars()
            .next()
            .is_some_and(char::is_alphanumeric)
        {
            continue;
        }
        let series_title = title[..start]
            .trim()
            .trim_end_matches(['-', '–', '—', ':', '|', '.'])
            .trim();
        if series_title.is_empty() {
            continue;
        }
        let mut series_title = series_title
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");
        for tail in title[cursor..].split('[').skip(1) {
            if let Some((label, _)) = tail.split_once(']')
                && matches!(
                    label.to_lowercase().as_str(),
                    "sub" | "dub" | "multi" | "subbed" | "dubbed" | "مترجم" | "مدبلج"
                )
            {
                series_title.push_str(&format!(" [{label}]"));
            }
        }
        return Some(EpisodeInfo {
            series_title,
            season,
            episode,
        });
    }
    None
}

impl Playlist {
    pub fn build_series(&mut self) {
        self.series.clear();
        let mut keys = HashMap::new();
        for (index, channel) in self.channels.iter_mut().enumerate() {
            channel.series_index = None;
            if channel.kind != ContentKind::Series {
                continue;
            }
            let title = channel
                .episode
                .as_ref()
                .map_or(channel.name.as_str(), |e| &e.series_title)
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ");
            let series_index = *keys.entry(title.to_lowercase()).or_insert_with(|| {
                self.series.push(Series {
                    title,
                    logo: channel.logo.clone(),
                    categories: Vec::new(),
                    seasons: Vec::new(),
                });
                self.series.len() - 1
            });
            channel.series_index = Some(series_index);
            let series = &mut self.series[series_index];
            if series.logo.is_none() {
                series.logo = channel.logo.clone();
            }
            if !series.categories.contains(&channel.category) {
                series.categories.push(channel.category);
            }
            let number = channel.episode.as_ref().map(|e| e.season);
            let season = if let Some(pos) = series.seasons.iter().position(|s| s.number == number) {
                &mut series.seasons[pos]
            } else {
                series.seasons.push(Season {
                    number,
                    episodes: Vec::new(),
                });
                series.seasons.last_mut().unwrap()
            };
            season.episodes.push(index);
        }
        for series in &mut self.series {
            series.seasons.sort_by_key(|s| s.number.unwrap_or(u32::MAX));
            for season in &mut series.seasons {
                season.episodes.sort_by_key(|i| {
                    self.channels[*i]
                        .episode
                        .as_ref()
                        .map_or(u32::MAX, |e| e.episode)
                });
            }
        }
    }

    pub fn library_count(&self, kind: ContentKind) -> usize {
        if kind == ContentKind::Series {
            self.series.len()
        } else {
            self.count(kind)
        }
    }

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
    display_name: String,
    tvg_name: String,
    attributes: HashMap<String, String>,
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
    result.build_series();
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
                attrs.get("tvg-name").cloned().unwrap_or_default()
            } else {
                title.trim().to_owned()
            };
            pending = Some(Entry {
                name,
                display_name: title.trim().to_owned(),
                tvg_name: attrs.get("tvg-name").cloned().unwrap_or_default(),
                attributes: attrs.clone(),
                group: attrs.get("group-title").cloned().unwrap_or_default(),
                id: attrs.get("tvg-id").cloned().unwrap_or_default(),
                logo: attrs.get("tvg-logo").filter(|v| !v.is_empty()).cloned(),
                duration: metadata
                    .split_whitespace()
                    .next()
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(-1.0),
                kind: ["content-type", "tvg-type", "stream-type", "type"]
                    .iter()
                    .filter_map(|key| attrs.get(*key))
                    .find_map(|value| kind_hint(value)),
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
                entry.group.clone()
            };
            if result.count >= limit {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "too_many_channels",
                ));
            }
            let search_key =
                format!("{} {} {} {}", entry.name, entry.tvg_name, group, entry.id).to_lowercase();
            emit(
                Channel {
                    display_name: entry.display_name,
                    tvg_name: entry.tvg_name.clone(),
                    group_title: entry.group,
                    attributes: entry.attributes,
                    url_path: parsed.path().to_owned(),
                    extension: stream_extension(&parsed),
                    year: release_year(&entry.name).or_else(|| release_year(&entry.tvg_name)),
                    quality: quality_label(&entry.name)
                        .or_else(|| quality_label(&entry.tvg_name))
                        .or_else(|| quality_label(&group)),
                    episode: (kind == ContentKind::Series)
                        .then(|| {
                            parse_episode(&entry.name).or_else(|| parse_episode(&entry.tvg_name))
                        })
                        .flatten(),
                    name: entry.name,
                    kind,
                    category: 0,
                    url: if url::Url::parse(raw_url).is_ok() {
                        raw_url.to_owned()
                    } else {
                        parsed.to_string()
                    },
                    tvg_id: entry.id,
                    logo: entry.logo,
                    headers: entry.headers,
                    is_live: kind == ContentKind::LiveTv,
                    search_key,
                    series_id: None,
                    series_index: None,
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

/// Priority: URL route > explicit metadata > query type > category > episode
/// marker/duration > live transport. Extensions alone never identify movies.
/// Unknown items remain accessible under Unclassified instead of becoming live.
fn classify(entry: &Entry, url: &url::Url) -> ContentKind {
    let path = url.path().to_ascii_lowercase();
    let segments: Vec<_> = path.split('/').collect();
    for (route, kind) in [
        ("series", ContentKind::Series),
        ("movie", ContentKind::Movie),
        ("movies", ContentKind::Movie),
        ("live", ContentKind::LiveTv),
    ] {
        if segments.contains(&route) {
            return kind;
        }
    }
    // Confirmed provider shorthand is stronger than words in channel categories.
    let parts: Vec<_> = path.trim_matches('/').split('/').collect();
    if parts.len() == 3 && !parts[2].is_empty() && parts[2].bytes().all(|b| b.is_ascii_digit()) {
        return ContentKind::LiveTv;
    }
    if let Some(kind) = entry.kind {
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
    for (hints, kind) in [
        (
            &[
                "series",
                "serie",
                "serien",
                "shows",
                "tvshows",
                "staffel",
                "مسلسلات",
                "مسلسل",
            ][..],
            ContentKind::Series,
        ),
        (
            &[
                "movie",
                "movies",
                "film",
                "films",
                "filme",
                "vod",
                "cinema",
                "kino",
                "أفلام",
                "افلام",
            ][..],
            ContentKind::Movie,
        ),
        (&["live", "livetv", "مباشر"][..], ContentKind::LiveTv),
    ] {
        if words.iter().any(|word| hints.contains(word)) {
            return kind;
        }
    }
    if parse_episode(&entry.name)
        .or_else(|| parse_episode(&entry.tvg_name))
        .is_some()
    {
        return ContentKind::Series;
    }
    if entry.duration > 0.0 {
        return ContentKind::Movie;
    }
    // Live TS/HLS and broadcast transports; MP4/MKV remain ambiguous.
    if matches!(url.scheme(), "udp" | "rtp" | "rtsp" | "rtmp" | "rtmps")
        || path.ends_with(".ts")
        || path.ends_with(".m3u8")
    {
        ContentKind::LiveTv
    } else {
        ContentKind::Unclassified
    }
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
    let mut escaped = false;
    for (i, c) in info.char_indices() {
        if escaped {
            escaped = false;
            continue;
        }
        if quote.is_some() && c == '\\' {
            escaped = true;
            continue;
        }
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

fn attributes(metadata: &str) -> HashMap<String, String> {
    let bytes = metadata.as_bytes();
    let mut i = 0;
    let mut attrs = HashMap::new();
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
                if bytes[i] == b'\\'
                    && i + 1 < bytes.len()
                    && (bytes[i + 1] == quote || bytes[i + 1] == b'\\')
                {
                    i += 2;
                    continue;
                }
                i += 1;
            }
            let mut decoded = String::new();
            let mut chars = metadata[begin..i].chars().peekable();
            while let Some(c) = chars.next() {
                if c == '\\'
                    && chars
                        .peek()
                        .is_some_and(|next| *next == quote as char || *next == '\\')
                {
                    decoded.push(chars.next().unwrap());
                } else {
                    decoded.push(c);
                }
            }
            value = decoded;
            if i < bytes.len() {
                i += 1;
            }
        } else {
            let begin = i;
            while i < bytes.len() && !bytes[i].is_ascii_whitespace() {
                i += 1;
            }
            value = metadata[begin..i].to_owned();
        }
        if start < end {
            attrs.insert(metadata[start..end].to_ascii_lowercase(), value);
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
