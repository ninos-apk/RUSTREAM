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
pub const SCHEMA: i64 = 2;

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
        ContentKind::Unclassified => 3,
    }
}
fn kind_from(id: i64) -> ContentKind {
    match id {
        1 => ContentKind::Movie,
        2 => ContentKind::Series,
        3 => ContentKind::Unclassified,
        _ => ContentKind::LiveTv,
    }
}
pub fn kind_key(kind: ContentKind) -> &'static str {
    match kind {
        ContentKind::LiveTv => "live",
        ContentKind::Movie => "movies",
        ContentKind::Series => "series",
        ContentKind::Unclassified => "unclassified",
    }
}

pub struct Catalog {
    pub schema: i64,
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
    /// Episodes of one show are usually adjacent: remember (identity, show, has artwork).
    last_show: Option<(String, usize, bool)>,
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
          CREATE TABLE channels(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind INTEGER NOT NULL,category INTEGER NOT NULL,tvg_id TEXT NOT NULL,search_key TEXT NOT NULL,secret BLOB NOT NULL,series_id TEXT,parent INTEGER,season INTEGER,episode INTEGER,year INTEGER,quality TEXT);
          CREATE TABLE shows(id INTEGER PRIMARY KEY,identity TEXT NOT NULL UNIQUE,title TEXT NOT NULL,artwork INTEGER);
          CREATE TABLE members(channel INTEGER PRIMARY KEY,show INTEGER NOT NULL,season INTEGER,episode INTEGER);
          BEGIN IMMEDIATE;").map_err(sql_code)?;
        Ok(Self {
            catalog: Catalog {
                db,
                cipher,
                schema: SCHEMA,
                count: 0,
                skipped: 0,
            },
            groups: HashMap::new(),
            next: 0,
            counts: Vec::new(),
            last_show: None,
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
            .insert(self.next, &channel, category, &group, None, None, None)?;
        if channel.kind == ContentKind::Series {
            let title = channel
                .episode
                .as_ref()
                .map_or(channel.name.as_str(), |e| e.series_title.as_str())
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ");
            let identity = channel.series_id.as_ref().map_or_else(
                || format!("m3u:{}", title.to_lowercase()),
                |id| format!("xtream:{id}"),
            );
            // Same results as INSERT OR IGNORE + SELECT + UPDATE, without three statements
            // for every episode of the show that was just written.
            let cached = self
                .last_show
                .as_ref()
                .filter(|(last, _, _)| *last == identity)
                .map(|(_, show, artwork)| (*show, *artwork));
            let (show, mut artwork) = match cached {
                Some(found) => found,
                None => {
                    self.catalog
                        .db
                        .prepare_cached("INSERT OR IGNORE INTO shows VALUES(?1,?2,?3,NULL)")
                        .map_err(sql_code)?
                        .execute(params![self.next, identity, title])
                        .map_err(sql_code)?;
                    self.catalog
                        .db
                        .prepare_cached(
                            "SELECT id,artwork IS NOT NULL FROM shows WHERE identity=?1",
                        )
                        .map_err(sql_code)?
                        .query_row([&identity], |r| Ok((r.get(0)?, r.get(1)?)))
                        .map_err(sql_code)?
                }
            };
            if channel.logo.is_some() && !artwork {
                self.catalog
                    .db
                    .prepare_cached("UPDATE shows SET artwork=?2 WHERE id=?1 AND artwork IS NULL")
                    .map_err(sql_code)?
                    .execute(params![show, self.next])
                    .map_err(sql_code)?;
                artwork = true;
            }
            self.last_show = Some((identity, show, artwork));
            self.catalog
                .db
                .prepare_cached("INSERT INTO members VALUES(?1,?2,?3,?4)")
                .map_err(sql_code)?
                .execute(params![
                    self.next,
                    show,
                    channel.episode.as_ref().map(|e| e.season),
                    channel.episode.as_ref().map(|e| e.episode)
                ])
                .map_err(sql_code)?;
        }
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
        self.catalog.db.execute_batch("CREATE INDEX channels_kind ON channels(kind,parent,id); CREATE INDEX channels_category ON channels(category,parent,id); CREATE INDEX episodes_parent ON channels(parent,season,episode,id); CREATE INDEX members_show ON members(show,season,episode,channel);").map_err(sql_code)?;
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
        let schema = meta("schema")? as i64;
        if !matches!(schema, 1 | SCHEMA) {
            return Err("catalog_incompatible".into());
        }
        let result = Self {
            schema,
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
    #[allow(clippy::too_many_arguments)]
    fn insert(
        &self,
        id: usize,
        c: &Channel,
        category: usize,
        group: &str,
        parent: Option<usize>,
        season: Option<i64>,
        episode: Option<i64>,
    ) -> Result<(), String> {
        let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
        let plain =
            serde_json::to_vec(&compact_record(c, group)).map_err(|_| "catalog_encode_failed")?;
        let mut secret = nonce.to_vec();
        secret.extend(
            self.cipher
                .encrypt(&nonce, plain.as_slice())
                .map_err(|_| "catalog_encrypt_failed")?,
        );
        self.db
            .prepare_cached(if self.schema == 1 {
                "INSERT INTO channels VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)"
            } else {
                "INSERT INTO channels VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13)"
            })
            .map_err(sql_code)?
            .execute(rusqlite::params_from_iter({
                let mut values: Vec<SqlValue> = vec![
                    (id as i64).into(),
                    c.name.clone().into(),
                    kind_id(c.kind).into(),
                    (category as i64).into(),
                    c.tvg_id.clone().into(),
                    c.search_key.clone().into(),
                    secret.into(),
                    c.series_id.clone().into(),
                    parent.map(|v| v as i64).into(),
                    season.into(),
                    episode.into(),
                ];
                if self.schema >= 2 {
                    values.push(c.year.map(i64::from).into());
                    values.push(c.quality.clone().into());
                }
                values
            }))
            .map_err(sql_code)?;
        Ok(())
    }
    pub fn channel(&self, id: usize) -> Result<Channel, String> {
        let row = self
            .db
            .query_row(
                if self.schema >= 2 { "SELECT c.name,c.kind,c.category,c.tvg_id,c.secret,c.series_id,c.year,c.quality,g.name FROM channels c LEFT JOIN categories g ON g.id=c.category WHERE c.id=?1" } else { "SELECT c.name,c.kind,c.category,c.tvg_id,c.secret,c.series_id,NULL,NULL,g.name FROM channels c LEFT JOIN categories g ON g.id=c.category WHERE c.id=?1" },
                [id],
                |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, i64>(1)?,
                        r.get::<_, usize>(2)?,
                        r.get::<_, String>(3)?,
                        r.get::<_, Vec<u8>>(4)?,
                        r.get::<_, Option<String>>(5)?,
                        r.get::<_, Option<u16>>(6)?,
                        r.get::<_, Option<String>>(7)?,
                        r.get::<_, Option<String>>(8)?,
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
        if v["v"] == COMPACT_RECORD {
            let mut channel = Channel {
                name: row.0,
                kind: kind_from(row.1),
                category: row.2,
                tvg_id: row.3,
                series_id: row.5,
                year: row.6,
                quality: row.7,
                ..Default::default()
            };
            expand_record(&v, &mut channel, row.8.as_deref().unwrap_or(""))?;
            return Ok(channel);
        }
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
            series_index: None,
            year: row.6,
            quality: row.7,
            display_name: v["display_name"].as_str().unwrap_or("").into(),
            tvg_name: v["tvg_name"].as_str().unwrap_or("").into(),
            group_title: v["group_title"].as_str().unwrap_or("").into(),
            attributes: serde_json::from_value(v["attributes"].clone()).unwrap_or_default(),
            url_path: v["url_path"].as_str().unwrap_or("").into(),
            extension: v["extension"].as_str().map(str::to_owned),
            episode: serde_json::from_value(v["episode"].clone()).ok(),
        })
    }
    pub fn categories(&self, revision: u64, import_ms: f64) -> Result<Value, String> {
        let mut counts = [0usize; 4];
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
            let count = if kind == 2 && self.schema >= 2 {
                self.db.query_row("SELECT count(DISTINCT m.show) FROM members m JOIN channels c ON c.id=m.channel WHERE c.category=?1 AND c.parent IS NULL",[id],|r|r.get::<_,usize>(0)).map_err(sql_code)?
            } else {
                count
            };
            counts[kind as usize] += count;
            categories
                .push(json!({"id":id,"name":name,"kind":kind_key(kind_from(kind)),"count":count}));
        }
        if self.schema >= 2 {
            counts[2] = self
                .db
                .query_row("SELECT count(*) FROM shows", [], |r| r.get(0))
                .map_err(sql_code)?;
        }
        Ok(
            json!({"schema":self.schema,"revision":revision,"count":self.count,"import_ms":import_ms,"preferred_kind":kind_key(kind_from(counts.iter().position(|n|*n>0).unwrap_or(0) as i64)),"kinds":ContentKind::ALL.into_iter().enumerate().map(|(i,k)|json!({"id":kind_key(k),"name":k.label(),"count":counts[i]})).collect::<Vec<_>>(),"categories":categories}),
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
        self.entry_page(revision, kind, category, query, offset, limit, false)
    }
    /// Library pages read year, quality and series flags from columns instead of decrypting
    /// every record on the page.
    #[allow(clippy::too_many_arguments)]
    fn entry_page(
        &self,
        revision: u64,
        kind: Option<ContentKind>,
        category: Option<usize>,
        query: &str,
        offset: usize,
        limit: usize,
        library: bool,
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
        let columns = if self.schema >= 2 {
            "year,quality"
        } else {
            "NULL,NULL"
        };
        let mut stmt=self.db.prepare(&format!("SELECT id,name,category,kind,series_id,{columns} FROM channels WHERE {filter} ORDER BY id LIMIT ? OFFSET ?")).map_err(sql_code)?;
        let items=stmt.query_map(rusqlite::params_from_iter(values.iter()),|r|{
            let series_id=r.get::<_,Option<String>>(4)?;
            let mut item=json!({"id":r.get::<_,usize>(0)?,"name":r.get::<_,String>(1)?,"category":r.get::<_,usize>(2)?,"kind":kind_key(kind_from(r.get(3)?)),"live":r.get::<_,i64>(3)?==0,"series_id":series_id});
            if library {
                item["item_type"]=json!(if series_id.is_some() {"series"} else {"entry"});
                item["year"]=json!(r.get::<_,Option<u16>>(5)?);
                item["quality"]=json!(r.get::<_,Option<String>>(6)?);
            }
            Ok(item)
        }).map_err(sql_code)?.collect::<Result<Vec<_>,_>>().map_err(sql_code)?;
        Ok(json!({"revision":revision,"total":count,"items":items}))
    }
    /// Library cards are grouped in SQLite before pagination; raw search stays compatible.
    pub fn browse(
        &self,
        revision: u64,
        kind: Option<ContentKind>,
        category: Option<usize>,
        query: &str,
        offset: usize,
        limit: usize,
    ) -> Result<Value, String> {
        if self.schema == 1 || kind != Some(ContentKind::Series) {
            return self.entry_page(revision, kind, category, query, offset, limit, true);
        }
        if let Some(id) = category {
            let exists: bool = self
                .db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM categories WHERE id=?1 AND kind=2)",
                    [id],
                    |r| r.get(0),
                )
                .map_err(sql_code)?;
            if !exists {
                return Err("invalid_category".into());
            }
        }
        let mut filter = "c.kind=2".to_owned();
        let mut values = Vec::<SqlValue>::new();
        if let Some(id) = category {
            filter.push_str(" AND c.category=?");
            values.push((id as i64).into());
        }
        for term in query.split_whitespace() {
            filter.push_str(" AND instr(c.search_key,?)>0");
            values.push(term.to_lowercase().into());
        }
        let from = shows_matching(&filter);
        let total: usize = self
            .db
            .query_row(
                &format!("SELECT count(*) {from}"),
                rusqlite::params_from_iter(values.iter()),
                |r| r.get(0),
            )
            .map_err(sql_code)?;
        values.push((limit.clamp(1, 128) as i64).into());
        values.push((offset.min(i64::MAX as usize) as i64).into());
        let mut stmt = self
            .db
            .prepare(&format!(
                "SELECT s.id,s.title {from} ORDER BY s.id LIMIT ? OFFSET ?"
            ))
            .map_err(sql_code)?;
        let items=stmt.query_map(rusqlite::params_from_iter(values.iter()),|r|Ok(json!({"id":r.get::<_,usize>(0)?,"name":r.get::<_,String>(1)?,"item_type":"series","kind":"series","live":false}))).map_err(sql_code)?.collect::<Result<Vec<_>,_>>().map_err(sql_code)?;
        Ok(json!({"revision":revision,"total":total,"items":items}))
    }

    pub fn artwork(&self, id: usize, series: bool) -> Result<Value, String> {
        let id = if series && self.schema >= 2 {
            self.db
                .query_row(
                    "SELECT coalesce(artwork,id) FROM shows WHERE id=?1",
                    [id],
                    |r| r.get(0),
                )
                .optional()
                .map_err(sql_code)?
                .ok_or("not_a_series")?
        } else {
            id
        };
        Ok(json!({"url":self.channel(id)?.logo}))
    }

    /// A separate unknown flag distinguishes all seasons from the NULL season bucket.
    pub fn series_page(
        &self,
        id: usize,
        season: Option<i64>,
        unknown: bool,
        offset: usize,
        season_offset: usize,
    ) -> Result<Value, String> {
        if self.schema == 1 {
            let mut data = self.episode_page(id, season, offset)?;
            data["name"] = json!(self.channel(id)?.name);
            data["series_id"] = json!(self.channel(id)?.series_id);
            let count: usize = self
                .db
                .query_row(
                    "SELECT count(*) FROM channels WHERE parent=?1 AND (?2 IS NULL OR season=?2)",
                    params![id, season],
                    |r| r.get(0),
                )
                .map_err(sql_code)?;
            data["total"] = json!(count);
            data["season_total"] = json!(data["seasons"].as_array().map_or(0, Vec::len));
            return Ok(data);
        }
        let title: String = self
            .db
            .query_row("SELECT title FROM shows WHERE id=?1", [id], |r| r.get(0))
            .optional()
            .map_err(sql_code)?
            .ok_or("not_a_series")?;
        let filter = "m.show=?1 AND c.series_id IS NULL AND (?2=0 OR m.season IS NULL) AND (?3 IS NULL OR m.season=?3)";
        let total:usize=self.db.query_row(&format!("SELECT count(*) FROM members m JOIN channels c ON c.id=m.channel WHERE {filter}"),params![id,unknown,season],|r|r.get(0)).map_err(sql_code)?;
        let mut stmt=self.db.prepare(&format!("SELECT c.id,c.name,m.season,m.episode,c.year,c.quality FROM members m JOIN channels c ON c.id=m.channel WHERE {filter} ORDER BY m.season IS NULL,m.season,m.episode IS NULL,m.episode,c.id LIMIT 128 OFFSET ?4")).map_err(sql_code)?;
        let items=stmt.query_map(params![id,unknown,season,offset.min(i64::MAX as usize) as i64],|r|Ok(json!({"id":r.get::<_,usize>(0)?,"name":r.get::<_,String>(1)?,"season":r.get::<_,Option<i64>>(2)?,"episode":r.get::<_,Option<i64>>(3)?,"year":r.get::<_,Option<i64>>(4)?,"quality":r.get::<_,Option<String>>(5)?,"item_type":"entry"}))).map_err(sql_code)?.collect::<Result<Vec<_>,_>>().map_err(sql_code)?;
        let seasons_from = "FROM members m JOIN channels c ON c.id=m.channel WHERE m.show=?1 AND c.series_id IS NULL GROUP BY m.season";
        let season_total: usize = self
            .db
            .query_row(
                &format!("SELECT count(*) FROM (SELECT m.season {seasons_from})"),
                [id],
                |r| r.get(0),
            )
            .map_err(sql_code)?;
        let mut stmt=self.db.prepare(&format!("SELECT m.season,count(*) {seasons_from} ORDER BY m.season IS NULL,m.season LIMIT 128 OFFSET ?2")).map_err(sql_code)?;
        let seasons = stmt
            .query_map(
                params![id, season_offset.min(i64::MAX as usize) as i64],
                |r| Ok(json!({"season":r.get::<_,Option<i64>>(0)?,"count":r.get::<_,usize>(1)?})),
            )
            .map_err(sql_code)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(sql_code)?;
        Ok(
            json!({"name":title,"series_id":self.channel(id)?.series_id,"total":total,"season_total":season_total,"items":items,"seasons":seasons}),
        )
    }

    /// Resolve neighbours in the playback context, independently of the current browser view.
    #[allow(clippy::too_many_arguments)]
    pub fn adjacent(
        &self,
        id: usize,
        direction: i64,
        kind: Option<ContentKind>,
        category: Option<usize>,
        query: &str,
        show: Option<usize>,
        season: Option<i64>,
        unknown: bool,
    ) -> Result<Value, String> {
        let mut values = Vec::<SqlValue>::new();
        let (from, mut filter, order) = if let Some(show) = show {
            values.push((show as i64).into());
            values.push(unknown.into());
            values.push(season.into());
            if self.schema >= 2 {
                ("members m JOIN channels c ON c.id=m.channel","m.show=? AND c.series_id IS NULL AND (?=0 OR m.season IS NULL) AND (? IS NULL OR m.season=?)".to_owned(),"m.season IS NULL,m.season,m.episode IS NULL,m.episode,c.id")
            } else {
                ("channels c","c.parent=? AND c.series_id IS NULL AND (?=0 OR c.season IS NULL) AND (? IS NULL OR c.season=?)".to_owned(),"c.season IS NULL,c.season,c.episode IS NULL,c.episode,c.id")
            }
        } else {
            (
                "channels c",
                "c.parent IS NULL AND c.series_id IS NULL".to_owned(),
                "c.id",
            )
        };
        if show.is_some() {
            values.push(season.into());
        } else {
            if let Some(kind) = kind {
                filter.push_str(" AND c.kind=?");
                values.push(kind_id(kind).into());
            }
            if let Some(category) = category {
                filter.push_str(" AND c.category=?");
                values.push((category as i64).into());
            }
            for term in query.split_whitespace() {
                filter.push_str(" AND instr(c.search_key,?)>0");
                values.push(term.to_lowercase().into());
            }
        }
        values.push((id as i64).into());
        let function = if direction < 0 { "lag" } else { "lead" };
        let sql = format!(
            "SELECT neighbour FROM (SELECT c.id,{function}(c.id) OVER (ORDER BY {order}) AS neighbour FROM {from} WHERE {filter}) WHERE id=?"
        );
        let next: Option<usize> = self
            .db
            .query_row(&sql, rusqlite::params_from_iter(values.iter()), |r| {
                r.get(0)
            })
            .optional()
            .map_err(sql_code)?
            .flatten();
        Ok(json!({"id":next}))
    }

    pub fn episode_page(
        &self,
        parent: usize,
        season: Option<i64>,
        offset: usize,
    ) -> Result<Value, String> {
        let mut stmt=self.db.prepare("SELECT id,name,season FROM channels WHERE parent=?1 AND (?2 IS NULL OR season=?2) ORDER BY season,episode,id LIMIT 128 OFFSET ?3").map_err(sql_code)?;
        let items=stmt.query_map(params![parent,season,offset],|r|Ok(json!({"id":r.get::<_,usize>(0)?,"name":r.get::<_,String>(1)?,"season":r.get::<_,Option<i64>>(2)?}))).map_err(sql_code)?.collect::<Result<Vec<_>,_>>().map_err(sql_code)?;
        let mut seasons=self.db.prepare("SELECT season,count(*) FROM channels WHERE parent=?1 GROUP BY season ORDER BY season IS NULL,season LIMIT 128").map_err(sql_code)?;
        let groups = seasons
            .query_map([parent], |r| {
                Ok(json!({"season":r.get::<_,Option<i64>>(0)?,"count":r.get::<_,usize>(1)?}))
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
        let group: String = self
            .db
            .query_row(
                "SELECT name FROM categories WHERE id=?1",
                [root.category],
                |r| r.get(0),
            )
            .optional()
            .map_err(sql_code)?
            .unwrap_or_default();
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
            if self.schema >= 2 {
                self.db.execute("DELETE FROM members WHERE channel IN (SELECT id FROM channels WHERE parent=?1)",[parent]).map_err(sql_code)?;
            }
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
                let season = season.parse::<i64>().ok().filter(|n| *n >= 0);
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
                    let mut channel = Channel {
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
                        ..Default::default()
                    };
                    channel.enrich(&root.group_title);
                    self.insert(
                        next,
                        &channel,
                        root.category,
                        &group,
                        Some(parent),
                        season,
                        episode["episode_num"]
                            .as_i64()
                            .or_else(|| episode["episode_num"].as_str()?.parse().ok())
                            .filter(|n| *n >= 0),
                    )?;
                    if self.schema >= 2 {
                        self.db
                            .execute(
                                "INSERT INTO members VALUES(?1,?2,?3,?4)",
                                params![
                                    next,
                                    parent,
                                    season,
                                    episode["episode_num"]
                                        .as_i64()
                                        .or_else(|| episode["episode_num"].as_str()?.parse().ok())
                                        .filter(|n| *n >= 0)
                                ],
                            )
                            .map_err(sql_code)?;
                    }
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

/// Marks records that omit values the reader can recover. Older records stay readable as written.
const COMPACT_RECORD: i64 = 2;

fn url_parts(address: &str) -> (String, Option<String>) {
    url::Url::parse(address)
        .map(|url| {
            (
                url.path().to_owned(),
                crate::playlist::stream_extension(&url),
            )
        })
        .unwrap_or_default()
}

/// The M3U parser's episode marker, recomputed from stored names (needs name, tvg_name, kind).
fn derived_episode(c: &Channel) -> Option<crate::playlist::EpisodeInfo> {
    if c.kind != ContentKind::Series {
        return None;
    }
    crate::playlist::parse_episode(&c.name).or_else(|| crate::playlist::parse_episode(&c.tvg_name))
}

/// Original metadata stays lossless, but copies of the name, the category, the URL path or
/// attribute values that are already stored as fields are not encrypted and written twice.
fn compact_record(c: &Channel, group: &str) -> Value {
    let mut record = json!({"v":COMPACT_RECORD,"url":c.url,"ua":c.headers.user_agent,"ref":c.headers.referrer,"live":c.is_live,"logo":c.logo});
    if c.episode != derived_episode(c) {
        record["episode"] = json!(c.episode);
    }
    if c.display_name != c.name {
        record["display_name"] = json!(c.display_name);
    }
    if c.tvg_name != c.name {
        record["tvg_name"] = json!(c.tvg_name);
    }
    if c.group_title != group {
        record["group_title"] = json!(c.group_title);
    }
    if (c.url_path.clone(), c.extension.clone()) != url_parts(&c.url) {
        record["url_path"] = json!(c.url_path);
        record["extension"] = json!(c.extension);
    }
    if !c.attributes.is_empty() {
        let attributes = c
            .attributes
            .iter()
            .map(|(key, value)| {
                let field = match key.as_str() {
                    "tvg-logo" => c.logo.as_deref(),
                    "tvg-name" => Some(c.tvg_name.as_str()),
                    "group-title" => Some(c.group_title.as_str()),
                    "tvg-id" => Some(c.tvg_id.as_str()),
                    _ => None,
                };
                let value = if field == Some(value.as_str()) {
                    Value::Null
                } else {
                    json!(value)
                };
                (key.clone(), value)
            })
            .collect();
        record["attributes"] = Value::Object(attributes);
    }
    record
}

fn expand_record(v: &Value, c: &mut Channel, group: &str) -> Result<(), String> {
    c.url = v["url"].as_str().ok_or("catalog_invalid")?.into();
    c.headers = StreamHeaders {
        user_agent: v["ua"].as_str().map(str::to_owned),
        referrer: v["ref"].as_str().map(str::to_owned),
    };
    c.logo = v["logo"].as_str().map(str::to_owned);
    c.is_live = v["live"].as_bool().unwrap_or(false);
    c.display_name = v["display_name"].as_str().unwrap_or(&c.name).into();
    c.tvg_name = v["tvg_name"].as_str().unwrap_or(&c.name).into();
    c.episode = match v.get("episode") {
        Some(episode) => serde_json::from_value(episode.clone()).ok(),
        None => derived_episode(c),
    };
    c.group_title = v["group_title"].as_str().unwrap_or(group).into();
    (c.url_path, c.extension) = if v.get("url_path").is_some() {
        (
            v["url_path"].as_str().unwrap_or("").into(),
            v["extension"].as_str().map(str::to_owned),
        )
    } else {
        url_parts(&c.url)
    };
    if let Some(attributes) = v["attributes"].as_object() {
        for (key, value) in attributes {
            let value = match value.as_str() {
                Some(text) => text.to_owned(),
                None => match key.as_str() {
                    "tvg-logo" => c.logo.clone().ok_or("catalog_invalid")?,
                    "tvg-name" => c.tvg_name.clone(),
                    "group-title" => c.group_title.clone(),
                    "tvg-id" => c.tvg_id.clone(),
                    _ => return Err("catalog_invalid".into()),
                },
            };
            c.attributes.insert(key.clone(), value);
        }
    }
    Ok(())
}

/// Collect matching shows once from the filtered channels. A correlated EXISTS per show
/// rescanned the whole category for every show and blocked the serial core for minutes.
fn shows_matching(filter: &str) -> String {
    format!(
        "FROM shows s WHERE s.id IN (SELECT m.show FROM channels c JOIN members m ON m.channel=c.id WHERE {filter})"
    )
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
                ..Default::default()
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

#[cfg(test)]
mod tests {
    use super::*;

    fn writer(name: &str) -> (std::path::PathBuf, Writer) {
        let path = std::env::temp_dir().join(format!("catalog-{name}-{}.db", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let writer = Writer::create(&path.to_string_lossy(), &"11".repeat(32)).unwrap();
        (path, writer)
    }
    fn assert_same(expected: &Channel, actual: &Channel) {
        assert_eq!(expected.name, actual.name);
        assert!(expected.url == actual.url, "url differs");
        assert_eq!(expected.logo, actual.logo);
        assert_eq!(expected.tvg_id, actual.tvg_id);
        assert!(expected.headers == actual.headers, "headers differ");
        assert_eq!(expected.is_live, actual.is_live);
        assert_eq!(expected.display_name, actual.display_name);
        assert_eq!(expected.tvg_name, actual.tvg_name);
        assert_eq!(expected.group_title, actual.group_title);
        assert_eq!(expected.attributes, actual.attributes);
        assert_eq!(expected.url_path, actual.url_path);
        assert_eq!(expected.extension, actual.extension);
        assert_eq!(expected.episode, actual.episode);
    }

    #[test]
    fn compact_records_restore_derived_and_differing_fields() {
        let (path, mut writer) = writer("compact");
        let name = "Show S01 E02".to_owned();
        let url = "https://p.test/series/u/p/2.mkv".to_owned();
        let derived = Channel {
            kind: ContentKind::Series,
            display_name: name.clone(),
            tvg_name: name.clone(),
            group_title: "Group".into(),
            logo: Some("https://img.test/a.png".into()),
            tvg_id: "id.1".into(),
            attributes: [
                ("tvg-logo", "https://img.test/a.png"),
                ("tvg-name", name.as_str()),
                ("group-title", "Group"),
                ("tvg-id", "id.1"),
                ("custom", "kept"),
            ]
            .into_iter()
            .map(|(k, v)| (k.to_owned(), v.to_owned()))
            .collect(),
            url_path: "/series/u/p/2.mkv".into(),
            extension: Some("mkv".into()),
            episode: crate::playlist::parse_episode(&name),
            name: name.clone(),
            url: url.clone(),
            ..Default::default()
        };
        // Every value differs from what the reader could derive.
        let differing = Channel {
            display_name: "Display".into(),
            tvg_name: "Other".into(),
            group_title: " Group ".into(),
            logo: None,
            attributes: [("tvg-logo", ""), ("tvg-name", "x"), ("group-title", "")]
                .into_iter()
                .map(|(k, v)| (k.to_owned(), v.to_owned()))
                .collect(),
            url_path: String::new(),
            extension: None,
            episode: None,
            headers: StreamHeaders {
                user_agent: Some("UA".into()),
                referrer: Some("https://ref.test/".into()),
            },
            ..derived.clone()
        };
        let record = compact_record(&derived, "Group");
        for omitted in [
            "display_name",
            "tvg_name",
            "group_title",
            "url_path",
            "episode",
        ] {
            assert!(record.get(omitted).is_none(), "{omitted} stored twice");
        }
        assert_eq!(record["attributes"]["tvg-logo"], Value::Null);
        assert_eq!(record["attributes"]["custom"], "kept");
        writer.add(derived.clone(), "Group".into()).unwrap();
        writer.add(differing.clone(), "Group".into()).unwrap();
        let catalog = writer.finish(0).unwrap();
        assert_same(&derived, &catalog.channel(0).unwrap());
        assert_same(&differing, &catalog.channel(1).unwrap());
        drop(catalog);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn full_records_written_before_compaction_stay_readable() {
        let (path, mut writer) = writer("legacy");
        writer
            .add(
                Channel {
                    name: "Legacy".into(),
                    url: "https://p.test/live/u/p/1.ts".into(),
                    ..Default::default()
                },
                "Group".into(),
            )
            .unwrap();
        let catalog = writer.finish(0).unwrap();
        let plain = json!({"url":"https://p.test/live/u/p/1.ts","ua":null,"ref":null,"live":true,"logo":"https://img.test/l.png","display_name":"","tvg_name":"T","group_title":"G","attributes":{"tvg-logo":"https://img.test/l.png"},"url_path":"/stored","extension":null,"episode":null});
        let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
        let mut secret = nonce.to_vec();
        secret.extend(
            catalog
                .cipher
                .encrypt(&nonce, serde_json::to_vec(&plain).unwrap().as_slice())
                .unwrap(),
        );
        catalog
            .db
            .execute("UPDATE channels SET secret=?1 WHERE id=0", [secret])
            .unwrap();
        let channel = catalog.channel(0).unwrap();
        assert_eq!(channel.display_name, "");
        assert_eq!(channel.tvg_name, "T");
        assert_eq!(channel.group_title, "G");
        assert_eq!(channel.url_path, "/stored");
        assert_eq!(channel.extension, None);
        assert_eq!(channel.attributes["tvg-logo"], "https://img.test/l.png");
        assert!(channel.is_live);
        drop(catalog);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn series_library_query_has_no_per_show_subquery() {
        let path = std::env::temp_dir().join(format!("plan-{}.db", std::process::id()));
        let _ = std::fs::remove_file(&path);
        let mut writer = Writer::create(&path.to_string_lossy(), &"11".repeat(32)).unwrap();
        for (index, group) in ["Alpha", "Beta"].into_iter().enumerate() {
            for episode in 1..=3 {
                let name = format!("{group} S01 E{episode}");
                let channel = Channel {
                    url: format!("https://p.test/series/u/p/{index}{episode}.mkv"),
                    kind: ContentKind::Series,
                    episode: crate::playlist::parse_episode(&name),
                    search_key: name.to_lowercase(),
                    name,
                    ..Default::default()
                };
                writer.add(channel, group.into()).unwrap();
            }
        }
        let catalog = writer.finish(0).unwrap();
        let filter = "c.kind=2 AND c.category=? AND instr(c.search_key,?)>0";
        let plan: Vec<String> = catalog
            .db
            .prepare(&format!(
                "EXPLAIN QUERY PLAN SELECT s.id {}",
                shows_matching(filter)
            ))
            .unwrap()
            .query_map(params![0, "alpha"], |r| r.get(3))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert!(
            !plan.iter().any(|step| step.contains("CORRELATED")),
            "{plan:?}"
        );
        drop(catalog);
        let _ = std::fs::remove_file(&path);
    }
}
