use iptv_core::{
    catalog::Catalog,
    playlist::{self, ContentKind},
    service::Engine,
};
use serde_json::{Value, json};
use std::{
    fs,
    io::Cursor,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(1);
const KEY: &str = "9f3b5b613ed641a70328ae71bb79cdad078c915f519af44f62e3277cad927816";
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "content-tv-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&p).unwrap();
        Self(p)
    }
    fn file(&self, name: &str, data: &str) -> String {
        let p = self.0.join(name);
        fs::write(&p, data).unwrap();
        p.to_string_lossy().into()
    }
    fn db(&self) -> String {
        self.0.join("catalog.db").to_string_lossy().into()
    }
    fn build(&self, source: &str) -> (Engine, u64) {
        let mut e = Engine::default();
        let p = self.file("source.m3u", source);
        call(
            &mut e,
            json!({"op":"build_catalog","path":p,"target":self.db(),"key":KEY}),
        );
        let r = call(
            &mut e,
            json!({"op":"open_catalog","path":self.db(),"key":KEY}),
        )["revision"]
            .as_u64()
            .unwrap();
        (e, r)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn call(e: &mut Engine, v: Value) -> Value {
    e.execute(serde_json::from_value(v).unwrap()).unwrap()
}
fn browse(e: &mut Engine, r: u64, kind: &str, query: &str, offset: usize, limit: usize) -> Value {
    call(
        e,
        json!({"op":"browse","revision":r,"kind":kind,"query":query,"offset":offset,"limit":limit}),
    )
}
fn series(
    e: &mut Engine,
    r: u64,
    id: usize,
    season: Option<i64>,
    unknown: bool,
    offset: usize,
) -> Value {
    call(
        e,
        json!({"op":"series_page","revision":r,"id":id,"season":season,"unknown":unknown,"offset":offset}),
    )
}

#[test]
fn windows_episode_patterns_unicode_labels_and_boundaries_are_preserved() {
    for marker in ["S01 E05", "S01E05", "S1 E5", "Season 1 Episode 5", "1x05"] {
        let e = playlist::parse_episode(&format!("أبواب القدر {marker} [SUB]")).unwrap();
        assert_eq!(e.series_title, "أبواب القدر [SUB]");
        assert_eq!((e.season, e.episode), (1, 5));
    }
    for title in [
        "AS01E01B",
        "Resolution 1920x1080",
        "Resolution 640x360",
        "S01 E05",
        "عنوان Sxx E05",
    ] {
        assert!(playlist::parse_episode(title).is_none(), "{title}");
    }
}

#[test]
fn route_priority_arabic_categories_and_unclassified_match_windows() {
    use ContentKind::*;
    for (attrs, name, path, expected) in [
        ("content-type=live", "Title", "/movie/u/p/1.mkv", Movie),
        ("content-type=movie", "Title", "/series/u/p/1.mp4", Series),
        ("group-title=Movies", "Title", "/live/u/p/1.mp4", LiveTv),
        (
            "group-title=Movies",
            "Title",
            "/user/password/12345",
            LiveTv,
        ),
        ("", "Title", "/ambiguous.mp4", Unclassified),
        ("", "Title", "/ambiguous.mkv", Unclassified),
        ("", "Title", "/unknown", Unclassified),
        ("group-title=أفلام", "Title", "/unknown", Movie),
        ("group-title=مسلسلات", "Title", "/unknown", Series),
        ("content-type=live", "Title", "/live/movie/series/1", Series),
        (
            "tvg-name='أبواب S1 E5'",
            "Display title",
            "/unknown",
            Series,
        ),
    ] {
        let p = playlist::parse_reader(Cursor::new(format!(
            "#EXTINF:-1 {attrs},{name}\nhttps://example.test{path}\n"
        )))
        .unwrap();
        assert_eq!(p.channels[0].kind, expected);
    }
}

#[test]
fn encrypted_catalog_roundtrip_keeps_original_attributes_and_absolute_url() {
    let f = Fixture::new();
    let original =
        "https://EXAMPLE.test/series/private-user/private-password/1.mkv?token=Case%2fSensitive";
    let text = format!(
        r#"#EXTM3U
#EXTINF:-1 tvg-name="قال \"مرحبا\", العالم S1 E5" tvg-id="" custom-secret="hidden-attribute" group-title="  أفلام, عربية  " tvg-logo="https://images.test/poster",Display, Title [2025-4K]
{original}|User-Agent=private-header
#EXTINF:-1,Incomplete S01 E10
"#
    );
    let (e, _) = f.build(&text);
    drop(e);
    let c = Catalog::open(&f.db(), KEY).unwrap();
    let channel = c.channel(0).unwrap();
    assert_eq!(channel.url, original);
    assert_eq!(channel.display_name, "Display, Title [2025-4K]");
    assert_eq!(channel.tvg_name, "قال \"مرحبا\", العالم S1 E5");
    assert_eq!(channel.group_title, "  أفلام, عربية  ");
    assert_eq!(channel.attributes["custom-secret"], "hidden-attribute");
    assert_eq!(channel.year, Some(2025));
    assert_eq!(channel.quality.as_deref(), Some("4K"));
    assert_eq!(channel.episode.unwrap().episode, 5);
    assert_eq!(c.skipped, 1);
    let bytes = fs::read(f.db()).unwrap();
    let plain = String::from_utf8_lossy(&bytes);
    for secret in [
        "private-user",
        "private-password",
        "private-header",
        "hidden-attribute",
        "Case%2fSensitive",
    ] {
        assert!(!plain.contains(secret));
    }
}

#[test]
fn grouped_series_keep_categories_versions_numeric_order_and_unknown_season() {
    let f = Fixture::new();
    let mut text = String::from("#EXTM3U\n");
    for (name, category, logo) in [
        ("أبواب القدر [SUB] S1 E10", "A", ""),
        (
            "أبواب  القدر [SUB] S1 E2 [HD]",
            "A",
            "https://images.test/first",
        ),
        (
            "أبواب القدر [SUB] S1 E2 [4K]",
            "A",
            "https://images.test/second",
        ),
        ("أبواب القدر [SUB] S2 E1", "B", ""),
        ("أبواب القدر [SUB]", "B", ""),
        ("أبواب القدر [DUB] S1 E1", "B", ""),
    ]
    .into_iter()
    {
        let id = text.lines().count();
        text.push_str(&format!("#EXTINF:-1 group-title='{category}' tvg-logo='{logo}',{name}\nhttps://provider.test/series/u/p/{id}.mkv\n"));
    }
    let (mut e, r) = f.build(&text);
    let cards = browse(&mut e, r, "series", "", 0, 128);
    assert_eq!(cards["total"], 2);
    assert_eq!(cards["items"][0]["name"], "أبواب القدر [SUB]");
    assert!(!cards.to_string().contains("https"));
    let page = series(&mut e, r, 0, Some(1), false, 0);
    assert_eq!(page["total"], 3);
    assert_eq!(
        page["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v["episode"].as_i64().unwrap())
            .collect::<Vec<_>>(),
        [2, 2, 10]
    );
    assert_eq!(page["items"][0]["quality"], "HD");
    assert_eq!(page["items"][1]["quality"], "4K");
    assert_eq!(page["seasons"][2]["season"], Value::Null);
    assert_eq!(series(&mut e, r, 0, None, true, 0)["total"], 1);
    assert_eq!(series(&mut e, r, 0, None, false, 0)["total"], 5);
    assert_eq!(
        call(
            &mut e,
            json!({"op":"artwork","revision":r,"id":0,"series":true})
        )["url"],
        "https://images.test/first"
    );
    let cats = call(&mut e, json!({"op":"categories"}));
    assert_eq!(cats["kinds"][2]["count"], 2);
    assert_eq!(cats["categories"][0]["count"], 1);
    assert_eq!(cats["categories"][1]["count"], 2);
    assert_eq!(browse(&mut e, r, "series", "E10", 0, 128)["total"], 1);
    assert_eq!(browse(&mut e, r, "series", "E10 E2", 0, 128)["total"], 0);
    let a = call(
        &mut e,
        json!({"op":"adjacent","revision":r,"id":1,"direction":1,"show":0,"season":1}),
    );
    assert_eq!(a["id"], 2);
    let play = call(&mut e, json!({"op":"select","revision":r,"id":2}));
    assert_eq!(play["live"], false);
    let attempt = play["attempt"].clone();
    browse(&mut e, r, "series", "DUB", 0, 1);
    assert_eq!(call(&mut e, json!({"op":"tick"}))["attempt"], attempt);
}

#[test]
fn series_deduplicate_before_paging_and_episodes_stay_bounded() {
    let f = Fixture::new();
    let mut text = String::from("#EXTM3U\n");
    for show in 0..140 {
        for episode in 1..=150 {
            text.push_str(&format!("#EXTINF:-1,Show {show} S01 E{episode:03}\nhttps://p.test/series/u/p/{show}-{episode}.mkv\n"));
        }
    }
    let (mut e, r) = f.build(&text);
    let a = browse(&mut e, r, "series", "", 0, 128);
    let b = browse(&mut e, r, "series", "", 128, 128);
    assert_eq!(a["total"], 140);
    assert_eq!(a["items"].as_array().unwrap().len(), 128);
    assert_eq!(b["items"].as_array().unwrap().len(), 12);
    assert_eq!(b["items"][0]["name"], "Show 128");
    assert_eq!(
        series(&mut e, r, 0, Some(1), false, 128)["items"]
            .as_array()
            .unwrap()
            .len(),
        22
    );
}

#[test]
fn schema_one_remains_readable_without_mutation_for_offline_upgrade() {
    let f = Fixture::new();
    let (e, _) = f.build("#EXTM3U\n#EXTINF:-1,Legacy\nhttps://example.test/live/u/p/1.ts\n");
    drop(e);
    let db = rusqlite::Connection::open(f.db()).unwrap();
    db.execute_batch("UPDATE meta SET value='1' WHERE key='schema';DROP TABLE shows;DROP TABLE members;ALTER TABLE channels DROP COLUMN year;ALTER TABLE channels DROP COLUMN quality;").unwrap();
    drop(db);
    let before = fs::read(f.db()).unwrap();
    let mut e = Engine::default();
    let r = call(&mut e, json!({"op":"open_catalog","path":f.db(),"key":KEY}));
    assert_eq!(r["schema"], 1);
    assert_eq!(
        browse(&mut e, r["revision"].as_u64().unwrap(), "live", "", 0, 64)["total"],
        1
    );
    drop(e);
    assert_eq!(fs::read(f.db()).unwrap(), before);
}

#[test]
fn film_versions_unclassified_and_navigation_keep_exact_entries() {
    let f = Fixture::new();
    let (mut e,r)=f.build("#EXTM3U\n#EXTINF:-1,Film [2025-HD]\nhttps://p.test/movie/u/p/1.mp4\n#EXTINF:-1,Film [2025-4K]\nhttps://p.test/movie/u/p/2.mp4\n#EXTINF:-1,Unknown\nhttps://p.test/unknown.mp4\n");
    let movies = browse(&mut e, r, "movies", "", 0, 64);
    assert_eq!(movies["total"], 2);
    assert_eq!(movies["items"][1]["quality"], "4K");
    assert_eq!(movies["items"][1]["year"], 2025);
    assert_eq!(browse(&mut e, r, "unclassified", "", 0, 64)["total"], 1);
    assert_eq!(
        call(
            &mut e,
            json!({"op":"adjacent","revision":r,"id":0,"direction":1,"kind":"movies"})
        )["id"],
        1
    );
    assert_eq!(
        call(&mut e, json!({"op":"select","revision":r,"id":1}))["url"],
        "https://p.test/movie/u/p/2.mp4"
    );
    assert!(
        e.execute(
            serde_json::from_value(json!({"op":"series_page","revision":r,"id":0,"offset":0}))
                .unwrap()
        )
        .is_err()
    );
    assert!(e.execute(serde_json::from_value(json!({"op":"browse","revision":r+1,"kind":"movies","query":"","offset":0,"limit":64})).unwrap()).is_err());
}

#[test]
fn xtream_same_titles_keep_provider_identity_and_skip_empty_cover_fields() {
    let f = Fixture::new();
    let groups = f.file(
        "categories.json",
        r#"[{"category_id":1,"category_name":"Drama"}]"#,
    );
    let shows=f.file("shows.json",r#"[{"series_id":7,"name":"Same title","category_id":1,"cover":"","stream_icon":"https://images.test/fallback"},{"series_id":8,"name":"Same title","category_id":1,"cover":"https://images.test/other"}]"#);
    let manifest=f.file("manifest.json",&json!({"server":"https://provider.test","username":"u","password":"p","account":{"user_info":{"auth":1,"status":"Active"}},"series":shows,"series_categories":groups}).to_string());
    let mut e = Engine::default();
    call(
        &mut e,
        json!({"op":"build_catalog","path":manifest,"target":f.db(),"key":KEY,"xtream":true}),
    );
    let r = call(&mut e, json!({"op":"open_catalog","path":f.db(),"key":KEY}))["revision"]
        .as_u64()
        .unwrap();
    assert_eq!(browse(&mut e, r, "series", "same", 0, 64)["total"], 2);
    assert_eq!(
        call(
            &mut e,
            json!({"op":"artwork","revision":r,"id":0,"series":true})
        )["url"],
        "https://images.test/fallback"
    );
    assert_eq!(
        call(
            &mut e,
            json!({"op":"artwork","revision":r,"id":1,"series":true})
        )["url"],
        "https://images.test/other"
    );
}
