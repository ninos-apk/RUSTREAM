//! Bounded SQLite catalog. Stream addresses/headers are authenticated encrypted records.
use crate::playlist::{Channel, ContentKind, ParseSummary, StreamHeaders, parse_stream};
use aes_gcm::{
    Aes256Gcm, KeyInit, Nonce,
    aead::{Aead, AeadCore, OsRng},
};
use rusqlite::{Connection, OptionalExtension, params, types::Value as SqlValue};
use serde_json::{Value, json};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::{
    collections::HashMap,
    fs::File,
    io::{self, BufRead, BufReader, Read},
    path::Path,
};

pub const BYTE_LIMIT: u64 = 512 * 1024 * 1024;
pub const ENTRY_LIMIT: usize = 2_000_000;
pub const CATEGORY_LIMIT: usize = 20_000;
const SCHEMA: i64 = 1;

pub fn io_code(error: &io::Error) -> String {
    match error.kind() {
        io::ErrorKind::NotFound => "file_not_found",
        io::ErrorKind::PermissionDenied => "file_access_denied",
        io::ErrorKind::StorageFull => "disk_full",
        io::ErrorKind::InvalidData => match error.to_string().as_str() {
            "too_many_channels" => "too_many_channels",
            "import_too_large" => "import_too_large",
            _ => "invalid_playlist_format",
        },
        _ => "import_read_failed",
    }
    .into()
}
pub fn sql_code(error: rusqlite::Error) -> String {
    match error {
        rusqlite::Error::SqliteFailure(e, _) if e.code == rusqlite::ErrorCode::DiskFull => {
            "disk_full"
        }
        rusqlite::Error::SqliteFailure(e, _) if e.code == rusqlite::ErrorCode::OutOfMemory => {
            "memory_exhausted"
        }
        _ => "catalog_io_failed",
    }
    .into()
}
fn cipher(key: &str) -> Result<Aes256Gcm, String> {
    if key.len() != 64 || !key.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("invalid_catalog_key".into());
    }
    let bytes: Result<Vec<_>, _> = (0..64)
        .step_by(2)
        .map(|i| u8::from_str_radix(&key[i..i + 2], 16))
        .collect();
    Aes256Gcm::new_from_slice(&bytes.map_err(|_| "invalid_catalog_key")?)
        .map_err(|_| "invalid_catalog_key".into())
}
fn kind_id(kind: ContentKind) -> i64 {
    match kind {
        ContentKind::LiveTv => 0,
        ContentKind::Movie => 1,
        ContentKind::Series => 2,
    }
}
fn kind_from(id: i64) -> ContentKind {
    match id {
        1 => ContentKind::Movie,
        2 => ContentKind::Series,
        _ => ContentKind::LiveTv,
    }
}
pub fn kind_key(kind: ContentKind) -> &'static str {
    match kind {
        ContentKind::LiveTv => "live",
        ContentKind::Movie => "movies",
        ContentKind::Series => "series",
    }
}

pub struct Catalog {
    db: Connection,
    cipher: Aes256Gcm,
    pub count: usize,
    pub skipped: usize,
}
pub struct Writer {
    catalog: Catalog,
    groups: HashMap<(i64, String), usize>,
    next: usize,
    counts: Vec<usize>,
    pub cancelled: Option<Arc<AtomicBool>>,
}
impl Writer {
    pub fn create(path: &str, key: &str) -> Result<Self, String> {
        if Path::new(path).exists() {
            return Err("catalog_target_exists".into());
        }
        let cipher = cipher(key)?;
        let db = Connection::open(path).map_err(sql_code)?;
        db.execute_batch("PRAGMA cache_size=-2048; PRAGMA temp_store=FILE; PRAGMA journal_mode=DELETE; PRAGMA synchronous=NORMAL;
          CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
          CREATE TABLE categories(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind INTEGER NOT NULL,count INTEGER NOT NULL);
          CREATE TABLE channels(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind INTEGER NOT NULL,category INTEGER NOT NULL,tvg_id TEXT NOT NULL,search_key TEXT NOT NULL,secret BLOB NOT NULL,series_id TEXT,parent INTEGER,season INTEGER,episode INTEGER);
          BEGIN IMMEDIATE;").map_err(sql_code)?;
        Ok(Self {
            catalog: Catalog {
                db,
                cipher,
                count: 0,
                skipped: 0,
            },
            groups: HashMap::new(),
            next: 0,
            counts: Vec::new(),
            cancelled: None,
        })
    }
    pub fn add(&mut self, channel: Channel, group: String) -> Result<(), String> {
        if self
            .cancelled
            .as_ref()
            .is_some_and(|c| c.load(Ordering::Relaxed))
        {
            return Err("import_cancelled".into());
        }
        if self.next >= ENTRY_LIMIT {
            return Err("too_many_channels".into());
        }
        let kind = kind_id(channel.kind);
        let index = self.groups.len();
        if index >= CATEGORY_LIMIT && !self.groups.contains_key(&(kind, group.clone())) {
            return Err("too_many_categories".into());
        }
        let category = *self.groups.entry((kind, group.clone())).or_insert(index);
        if category == index {
            self.counts.push(0);
            self.catalog
                .db
                .execute(
                    "INSERT INTO categories VALUES(?1,?2,?3,0)",
                    params![category, group, kind],
                )
                .map_err(sql_code)?;
        }
        self.catalog
            .insert(self.next, &channel, category, None, None, None)?;
        self.counts[category] += 1;
        self.next += 1;
        Ok(())
    }
    pub fn finish(mut self, skipped: usize) -> Result<Catalog, String> {
        if self.next == 0 {
            return Err("empty_playlist".into());
        }
        self.catalog.count = self.next;
        self.catalog.skipped = skipped;
        for (id, count) in self.counts.iter().enumerate() {
            self.catalog
                .db
                .prepare_cached("UPDATE categories SET count=?2 WHERE id=?1")
                .map_err(sql_code)?
                .execute(params![id, count])
                .map_err(sql_code)?;
        }
        self.catalog.db.execute_batch("CREATE INDEX channels_kind ON channels(kind,parent,id); CREATE INDEX channels_category ON channels(category,parent,id); CREATE INDEX episodes_parent ON channels(parent,season,episode,id);").map_err(sql_code)?;
        for (key, value) in [
            ("schema", SCHEMA as usize),
            ("count", self.next),
            ("skipped", skipped),
        ] {
            self.catalog
                .db
                .execute(
                    "INSERT INTO meta VALUES(?1,?2)",
                    params![key, value.to_string()],
                )
                .map_err(sql_code)?;
        }
        self.catalog
            .db
            .execute_batch("COMMIT; PRAGMA optimize;")
            .map_err(sql_code)?;
        Ok(self.catalog)
    }
}
impl Catalog {
    pub fn open(path: &str, key: &str) -> Result<Self, String> {
        let db = Connection::open_with_flags(path, rusqlite::OpenFlags::SQLITE_OPEN_READ_WRITE)
            .map_err(sql_code)?;
        db.execute_batch("PRAGMA cache_size=-2048; PRAGMA temp_store=FILE;")
            .map_err(sql_code)?;
        let meta = |name: &str| -> Result<usize, String> {
            db.query_row("SELECT value FROM meta WHERE key=?1", [name], |r| {
                r.get::<_, String>(0)
            })
            .map_err(sql_code)?
            .parse()
            .map_err(|_| "catalog_invalid".into())
        };
        if meta("schema")? != SCHEMA as usize {
            return Err("catalog_incompatible".into());
        }
        let result = Self {
            count: meta("count")?,
            skipped: meta("skipped")?,
            cipher: cipher(key)?,
            db,
        };
        if result.count == 0 {
            return Err("catalog_invalid".into());
        }
        // Verify the key and authenticated record before replacing a working library.
        result.channel(0)?;
        Ok(result)
    }
    fn insert(
        &self,
        id: usize,
        c: &Channel,
        category: usize,
        parent: Option<usize>,
        season: Option<i64>,
        episode: Option<i64>,
    ) -> Result<(), String> {
        let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
        let plain = serde_json::to_vec(&json!({"url":c.url,"ua":c.headers.user_agent,"ref":c.headers.referrer,"live":c.is_live,"logo":c.logo})).map_err(|_| "catalog_encode_failed")?;
        let mut secret = nonce.to_vec();
        secret.extend(
            self.cipher
                .encrypt(&nonce, plain.as_slice())
                .map_err(|_| "catalog_encrypt_failed")?,
        );
        self.db
            .prepare_cached("INSERT INTO channels VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)")
            .map_err(sql_code)?
            .execute(params![
                id,
                c.name,
                kind_id(c.kind),
                category,
                c.tvg_id,
                c.search_key,
                secret,
                c.series_id,
                parent,
                season,
                episode
            ])
            .map_err(sql_code)?;
        Ok(())
    }
    pub fn channel(&self, id: usize) -> Result<Channel, String> {
        let row = self
            .db
            .query_row(
                "SELECT name,kind,category,tvg_id,secret,series_id FROM channels WHERE id=?1",
                [id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, usize>(2)?,
                        r.get::<_, String>(3)?,
                        r.get::<_, Vec<u8>>(4)?,
                        r.get::<_, Option<String>>(5)?,
                    ))
                },
            )
            .optional()
            .map_err(sql_code)?
            .ok_or("invalid_channel")?;
        if row.4.len() < 28 {
            return Err("catalog_invalid".into());
        }
        let plain = self
            .cipher
            .decrypt(Nonce::from_slice(&row.4[..12]), &row.4[12..])
            .map_err(|_| "catalog_key_failed")?;
        let v: Value = serde_json::from_slice(&plain).map_err(|_| "catalog_invalid")?;
        Ok(Channel {
            name: row.0,
            kind: kind_from(row.1),
            category: row.2,
            tvg_id: row.3,
            url: v["url"].as_str().ok_or("catalog_invalid")?.into(),
            headers: StreamHeaders {
                user_agent: v["ua"].as_str().map(str::to_owned),
                referrer: v["ref"].as_str().map(str::to_owned),
            },
            logo: v["logo"].as_str().map(str::to_owned),
            is_live: v["live"].as_bool().unwrap_or(false),
            search_key: String::new(),
            series_id: row.5,
        })
    }
    pub fn categories(&self, revision: u64, import_ms: f64) -> Result<Value, String> {
        let mut counts = [0usize; 3];
        let mut stmt = self
            .db
            .prepare("SELECT id,name,kind,count FROM categories ORDER BY id")
            .map_err(sql_code)?;
        let rows = stmt
            .query_map([], |r| {
                Ok((
                    r.get::<_, usize>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, usize>(3)?,
                ))
            })
            .map_err(sql_code)?;
        let mut categories = Vec::new();
        for row in rows {
            let (id, name, kind, count) = row.map_err(sql_code)?;
            counts[kind as usize] += count;
            categories
                .push(json!({"id":id,"name":name,"kind":kind_key(kind_from(kind)),"count":count}));
        }
        Ok(
            json!({"revision":revision,"count":self.count,"import_ms":import_ms,"preferred_kind":kind_key(kind_from(counts.iter().position(|n|*n>0).unwrap_or(0) as i64)),"kinds":ContentKind::ALL.into_iter().enumerate().map(|(i,k)|json!({"id":kind_key(k),"name":k.label(),"count":counts[i]})).collect::<Vec<_>>(),"categories":categories}),
        )
    }
    pub fn search(
        &self,
        revision: u64,
        kind: Option<ContentKind>,
        category: Option<usize>,
        query: &str,
        offset: usize,
        limit: usize,
    ) -> Result<Value, String> {
        if let Some(id) = category
            && !self
                .db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM categories WHERE id=?1)",
                    [id],
                    |r| r.get::<_, bool>(0),
                )
                .map_err(sql_code)?
        {
            return Err("invalid_category".into());
        }
        let mut filter = "parent IS NULL".to_owned();
        let mut values = Vec::<SqlValue>::new();
        if let Some(k) = kind {
            filter.push_str(" AND kind=?");
            values.push(kind_id(k).into());
        }
        if let Some(c) = category {
            filter.push_str(" AND category=?");
            values.push((c as i64).into());
        }
        for term in query.split_whitespace() {
            filter.push_str(" AND instr(search_key,?)>0");
            values.push(term.to_lowercase().into());
        }
        let count: usize = self
            .db
            .query_row(
                &format!("SELECT count(*) FROM channels WHERE {filter}"),
                rusqlite::params_from_iter(values.iter()),
                |r| r.get(0),
            )
            .map_err(sql_code)?;
        values.push((limit.clamp(1, 128) as i64).into());
        values.push((offset.min(i64::MAX as usize) as i64).into());
        let mut stmt=self.db.prepare(&format!("SELECT id,name,category,kind,series_id FROM channels WHERE {filter} ORDER BY id LIMIT ? OFFSET ?")).map_err(sql_code)?;
        let items=stmt.query_map(rusqlite::params_from_iter(values.iter()),|r|Ok(json!({"id":r.get::<_,usize>(0)?,"name":r.get::<_,String>(1)?,"category":r.get::<_,usize>(2)?,"kind":kind_key(kind_from(r.get(3)?)),"live":r.get::<_,i64>(3)?==0,"series_id":r.get::<_,Option<String>>(4)?}))).map_err(sql_code)?.collect::<Result<Vec<_>,_>>().map_err(sql_code)?;
        Ok(json!({"revision":revision,"total":count,"items":items}))
    }
    pub fn episode_page(
        &self,
        parent: usize,
        season: Option<i64>,
        offset: usize,
    ) -> Result<Value, String> {
        let mut stmt=self.db.prepare("SELECT id,name,season FROM channels WHERE parent=?1 AND (?2 IS NULL OR season=?2) ORDER BY season,episode,id LIMIT 128 OFFSET ?3").map_err(sql_code)?;
        let items=stmt.query_map(params![parent,season,offset],|r|Ok(json!({"id":r.get::<_,usize>(0)?,"name":r.get::<_,String>(1)?,"season":r.get::<_,i64>(2)?}))).map_err(sql_code)?.collect::<Result<Vec<_>,_>>().map_err(sql_code)?;
        let mut seasons=self.db.prepare("SELECT season,count(*) FROM channels WHERE parent=?1 GROUP BY season ORDER BY season").map_err(sql_code)?;
        let groups = seasons
            .query_map([parent], |r| {
                Ok(json!({"season":r.get::<_,i64>(0)?,"count":r.get::<_,usize>(1)?}))
            })
            .map_err(sql_code)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(sql_code)?;
        Ok(json!({"items":items,"seasons":groups}))
    }
    pub fn resolve_series(&mut self, parent: usize, path: &str) -> Result<Value, String> {
        let root = self.channel(parent)?;
        if root.series_id.is_none() {
            return Err("not_a_series".into());
        }
        let file = File::open(path).map_err(|e| io_code(&e))?;
        if file.metadata().map_err(|e| io_code(&e))?.len() > 8 * 1024 * 1024 {
            return Err("series_too_large".into());
        }
        let v: Value =
            serde_json::from_reader(BufReader::new(file)).map_err(|_| "invalid_series_response")?;
        let seasons = v["episodes"].as_object().ok_or("invalid_series_response")?;
        // Insert into a transaction so a malformed response never exposes a partial episode list.
        self.db.execute_batch("BEGIN IMMEDIATE").map_err(sql_code)?;
        let result = (|| {
            self.db
                .execute("DELETE FROM channels WHERE parent=?1", [parent])
                .map_err(sql_code)?;
            let mut next: usize = self
                .db
                .query_row("SELECT coalesce(max(id),-1)+1 FROM channels", [], |r| {
                    r.get(0)
                })
                .map_err(sql_code)?;
            let mut count = 0;
            for (season, episodes) in seasons {
                let season = season.parse::<i64>().unwrap_or(0);
                for episode in episodes.as_array().ok_or("invalid_series_response")? {
                    count += 1;
                    if count > 10000 {
                        return Err("series_too_large".into());
                    }
                    let id =
                        crate::xtream::value_id(&episode["id"]).ok_or("invalid_series_response")?;
                    if !id.bytes().all(|b| b.is_ascii_digit()) || id.is_empty() {
                        return Err("invalid_series_response".into());
                    }
                    let ext = episode["container_extension"].as_str().unwrap_or("mp4");
                    if ext.is_empty()
                        || ext.len() > 12
                        || !ext.bytes().all(|b| b.is_ascii_alphanumeric())
                    {
                        return Err("invalid_series_response".into());
                    }
                    let mut url = url::Url::parse(&root.url).map_err(|_| "invalid_stream")?;
                    url.path_segments_mut()
                        .map_err(|_| "invalid_stream")?
                        .pop()
                        .push(&format!("{id}.{ext}"));
                    let title = episode["title"].as_str().unwrap_or("Episode").to_owned();
                    let channel = Channel {
                        name: title.clone(),
                        url: url.to_string(),
                        kind: ContentKind::Series,
                        category: root.category,
                        tvg_id: String::new(),
                        logo: None,
                        headers: StreamHeaders::default(),
                        is_live: false,
                        search_key: title.to_lowercase(),
                        series_id: None,
                    };
                    self.insert(
                        next,
                        &channel,
                        root.category,
                        Some(parent),
                        Some(season),
                        Some(episode["episode_num"].as_i64().unwrap_or(count)),
                    )?;
                    next += 1;
                }
            }
            if count == 0 {
                return Err("empty_series".into());
            }
            Ok(())
        })();
        if let Err(error) = result {
            let _ = self.db.execute_batch("ROLLBACK");
            return Err(error);
        }
        self.db.execute_batch("COMMIT").map_err(sql_code)?;
        self.episode_page(parent, None, 0)
    }
}

pub fn build_m3u(
    path: &str,
    base: Option<&url::Url>,
    writer: &mut Writer,
) -> Result<ParseSummary, String> {
    let file = File::open(path).map_err(|e| io_code(&e))?;
    if file.metadata().map_err(|e| io_code(&e))?.len() > BYTE_LIMIT {
        return Err("import_too_large".into());
    }
    let mut input = BufReader::with_capacity(256 * 1024, file.take(BYTE_LIMIT + 1));
    let prefix = String::from_utf8_lossy(input.fill_buf().map_err(|e| io_code(&e))?).into_owned();
    if prefix
        .lines()
        .any(|l| l.starts_with("#EXT-X-TARGETDURATION:") || l.starts_with("#EXT-X-STREAM-INF:"))
    {
        let address = base.ok_or("hls_requires_remote_url")?;
        if !matches!(address.scheme(), "http" | "https") {
            return Err("invalid_base".into());
        }
        let mut finite = false;
        let mut buf = [0u8; 65536];
        let mut tail = Vec::new();
        loop {
            let n = input.read(&mut buf).map_err(|e| io_code(&e))?;
            if n == 0 {
                break;
            }
            tail.extend_from_slice(&buf[..n]);
            finite |= tail.windows(14).any(|w| w == b"#EXT-X-ENDLIST");
            let keep = tail.len().saturating_sub(32);
            tail.drain(..keep);
        }
        let kind = if finite {
            ContentKind::Movie
        } else {
            ContentKind::LiveTv
        };
        writer.add(
            Channel {
                name: "HLS-Stream".into(),
                kind,
                category: 0,
                url: address.to_string(),
                tvg_id: String::new(),
                logo: None,
                headers: StreamHeaders::default(),
                is_live: !finite,
                search_key: "hls-stream ohne kategorie".into(),
                series_id: None,
            },
            "Ohne Kategorie".into(),
        )?;
        return Ok(ParseSummary {
            count: 1,
            skipped: 0,
        });
    }
    let mut sink_error = None;
    let result = parse_stream(input, base, ENTRY_LIMIT, |c, g| {
        writer.add(c, g).map_err(|e| {
            sink_error = Some(e);
            io::Error::other("catalog_sink_failed")
        })
    });
    match result {
        Ok(v) => Ok(v),
        Err(e) => Err(sink_error.unwrap_or_else(|| io_code(&e))),
    }
}
