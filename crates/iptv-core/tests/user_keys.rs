//! Stable content keys for history and favourites: they must survive re-imports, reordering and
//! repeated Xtream episode resolution, while numeric ids stay revision-bound.
use iptv_core::service::Engine;
use serde_json::{Value, json};
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(1);
const KEY: &str = "9f3b5b613ed641a70328ae71bb79cdad078c915f519af44f62e3277cad927816";
const SALT: &str = "4c1f0e7a9b2d38c65e0a1f7b3c9d2e48a6b0c1d2e3f405162738495a6b7c8d9e";
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let p = std::env::temp_dir().join(format!(
            "user-keys-{}-{}",
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
    /// Every build is a new generation in a new file, as on Android.
    fn build(&self, name: &str, source: &str, salt: &str) -> (Engine, u64) {
        let mut e = Engine::default();
        let p = self.file(&format!("{name}.m3u"), source);
        let db = self
            .0
            .join(format!("{name}.db"))
            .to_string_lossy()
            .into_owned();
        call(
            &mut e,
            json!({"op":"build_catalog","path":p,"target":db,"key":KEY,"salt":salt}),
        );
        let r = call(&mut e, json!({"op":"open_catalog","path":db,"key":KEY}))["revision"]
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
fn browse(e: &mut Engine, r: u64, kind: &str) -> Vec<Value> {
    call(
        e,
        json!({"op":"browse","revision":r,"kind":kind,"query":"","offset":0,"limit":128}),
    )["items"]
        .as_array()
        .unwrap()
        .clone()
}
fn key_of(items: &[Value], name: &str) -> String {
    items
        .iter()
        .find(|i| i["name"] == name)
        .unwrap_or_else(|| panic!("{name} missing"))["key"]
        .as_str()
        .unwrap()
        .to_owned()
}

const PLAYLIST: &str = "#EXTM3U
#EXTINF:-1 group-title=\"News\",Channel One
https://p.test/live/1.ts
#EXTINF:-1 group-title=\"Films\",Film
https://p.test/movie/film.mp4
#EXTINF:-1 group-title=\"Films\",Film
https://p.test/movie/film-4k.mp4
#EXTINF:-1 group-title=\"Shows\",Show S01 E01
https://p.test/series/show-1-1.mp4
#EXTINF:-1 group-title=\"Shows\",Show S01 E02
https://p.test/series/show-1-2.mp4
";
const REORDERED: &str = "#EXTM3U
#EXTINF:-1 group-title=\"Shows\",Show S01 E02
https://p.test/series/show-1-2.mp4
#EXTINF:-1 group-title=\"Films\",Film
https://p.test/movie/film-4k.mp4
#EXTINF:-1 group-title=\"Extra\",Another
https://p.test/movie/another.mp4
#EXTINF:-1 group-title=\"Shows\",Show S01 E01
https://p.test/series/show-1-1.mp4
#EXTINF:-1 group-title=\"Films\",Film
https://p.test/movie/film.mp4
#EXTINF:-1 group-title=\"News\",Channel One
https://p.test/live/1.ts
";

#[test]
fn keys_survive_reimport_and_reordering_while_ids_change() {
    let f = Fixture::new();
    let (mut a, ra) = f.build("first", PLAYLIST, SALT);
    let (mut b, rb) = f.build("second", REORDERED, SALT);

    let live_a = key_of(&browse(&mut a, ra, "live"), "Channel One");
    let live_b = key_of(&browse(&mut b, rb, "live"), "Channel One");
    assert_eq!(live_a, live_b);
    assert!(live_a.starts_with("c:") && live_a.len() == 34);

    // Same title, different streams (SD/4K) stay two separate keys.
    let movies_a = browse(&mut a, ra, "movies");
    let film_keys: Vec<_> = movies_a
        .iter()
        .filter(|i| i["name"] == "Film")
        .map(|i| i["key"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(film_keys.len(), 2);
    assert_ne!(film_keys[0], film_keys[1]);

    // Series are one key per show, never per episode.
    let show_a = key_of(&browse(&mut a, ra, "series"), "Show");
    let show_b = key_of(&browse(&mut b, rb, "series"), "Show");
    assert_eq!(show_a, "s:m3u:show");
    assert_eq!(show_a, show_b);

    // Resolution maps the old keys to the new generation's ids.
    let mut keys = film_keys.clone();
    keys.push(live_a.clone());
    keys.push(show_a.clone());
    keys.push("c:00000000000000000000000000000000".into());
    keys.push("c:not-a-key".into());
    keys.push("c:zz000000000000000000000000000000".into());
    let resolved = call(&mut b, json!({"op":"resolve","revision":rb,"keys":keys}));
    let items = resolved["items"].as_array().unwrap();
    assert_eq!(resolved["keyed"], true);
    assert_eq!(items.len(), 4, "unknown keys are omitted");
    for item in items {
        let entry = call(&mut b, json!({"op":"entry","revision":rb,"id":item["id"]}));
        if item["item_type"] == "series" {
            continue;
        }
        assert_eq!(entry["key"], item["key"]);
    }
    let live = items.iter().find(|i| i["key"] == live_a.as_str()).unwrap();
    assert_eq!(live["live"], true);
    assert_eq!(live["kind"], "live");
    assert_ne!(
        live["id"],
        browse(&mut a, ra, "live")[0]["id"],
        "ids are revision-bound, keys are not"
    );
}

#[test]
fn episodes_resolve_to_their_show_and_season() {
    let f = Fixture::new();
    let (mut e, r) = f.build("episodes", PLAYLIST, SALT);
    let show = browse(&mut e, r, "series")[0].clone();
    let page = call(
        &mut e,
        json!({"op":"series_page","revision":r,"id":show["id"],"offset":0}),
    );
    assert_eq!(page["key"], "s:m3u:show");
    let second = page["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|i| i["episode"] == 2)
        .unwrap()
        .clone();
    let resolved = call(
        &mut e,
        json!({"op":"resolve","revision":r,"keys":[second["key"]]}),
    );
    let item = &resolved["items"][0];
    assert_eq!(item["id"], second["id"]);
    assert_eq!(item["show_id"], show["id"]);
    assert_eq!(item["show_key"], "s:m3u:show");
    assert_eq!(item["show_name"], "Show");
    assert_eq!(item["season"], 1);
    assert_eq!(item["episode"], 2);
}

#[test]
fn salt_changes_every_key_so_hashes_are_not_bare_url_digests() {
    let f = Fixture::new();
    let (mut a, ra) = f.build("salted", PLAYLIST, SALT);
    let (mut b, rb) = f.build("other", PLAYLIST, &"0".repeat(64));
    assert_ne!(
        key_of(&browse(&mut a, ra, "live"), "Channel One"),
        key_of(&browse(&mut b, rb, "live"), "Channel One")
    );
}

#[test]
fn resolve_is_bounded_and_rejects_stale_revisions() {
    let f = Fixture::new();
    let (mut e, r) = f.build("bounded", PLAYLIST, SALT);
    let keys: Vec<String> = (0..129).map(|i| format!("c:{i:032x}")).collect();
    let too_many = e
        .execute(serde_json::from_value(json!({"op":"resolve","revision":r,"keys":keys})).unwrap());
    assert_eq!(too_many.unwrap_err(), "too_many_keys");
    let stale = e
        .execute(serde_json::from_value(json!({"op":"resolve","revision":r+1,"keys":[]})).unwrap());
    assert_eq!(stale.unwrap_err(), "stale_revision");
}

#[test]
fn xtream_episode_keys_survive_repeated_resolution() {
    let f = Fixture::new();
    let categories = f.file(
        "categories.json",
        r#"[{"category_id":1,"category_name":"Drama"}]"#,
    );
    let series = f.file(
        "series.json",
        r#"[{"series_id":123,"name":"Show","category_id":1}]"#,
    );
    let manifest=f.file("manifest.json",&json!({"server":"https://provider.test/base/","username":"user","password":"secret","account":{"user_info":{"auth":1,"status":"Active"}},"series":series,"series_categories":categories}).to_string());
    let db = f.0.join("xtream.db").to_string_lossy().into_owned();
    let mut e = Engine::default();
    call(
        &mut e,
        json!({"op":"build_catalog","path":manifest,"target":db,"key":KEY,"xtream":true,"salt":SALT}),
    );
    let r = call(&mut e, json!({"op":"open_catalog","path":db,"key":KEY}))["revision"]
        .as_u64()
        .unwrap();
    assert_eq!(browse(&mut e, r, "series")[0]["key"], "s:xtream:123");
    let episodes=f.file("episodes.json",r#"{"episodes":{"1":[{"id":111,"title":"First","episode_num":1},{"id":112,"title":"Second","episode_num":2}]}}"#);
    let page = |e: &mut Engine| {
        call(
            e,
            json!({"op":"resolve_series","revision":r,"id":0,"path":episodes}),
        );
        call(
            e,
            json!({"op":"series_page","revision":r,"id":0,"offset":0}),
        )["items"]
            .as_array()
            .unwrap()
            .clone()
    };
    let first = page(&mut e);
    let second = page(&mut e);
    // Resolution deletes and re-inserts the episode rows; only the key is a stable identity.
    assert_eq!(first[0]["key"], second[0]["key"]);
    assert_eq!(first[1]["key"], second[1]["key"]);
    assert_ne!(first[0]["key"], first[1]["key"]);
}

#[test]
fn select_resumes_vod_at_saved_position_but_never_live() {
    let f = Fixture::new();
    let (mut e, r) = f.build("resume", PLAYLIST, SALT);
    let movie = browse(&mut e, r, "movies")[0]["id"].clone();
    let load = call(
        &mut e,
        json!({"op":"select","revision":r,"id":movie,"resume":754.5}),
    );
    assert_eq!(load["resume"], 754.5);
    // A retry before the first sample keeps the saved position.
    assert_eq!(call(&mut e, json!({"op":"reconnect"}))["resume"], 754.5);
    // Once the player reported a position, that one wins.
    let attempt = call(&mut e, json!({"op":"reconnect"}))["attempt"].clone();
    call(
        &mut e,
        json!({"op":"poll","attempt":attempt,"position":800.0,"buffered":5.0,"buffering":false,"cache_end":805.0,"ingress":null,"paused":false}),
    );
    assert_eq!(call(&mut e, json!({"op":"reconnect"}))["resume"], 800.0);
    // A plain selection starts at the beginning; invalid values are ignored.
    assert!(call(&mut e, json!({"op":"select","revision":r,"id":movie}))["resume"].is_null());
    assert!(
        call(
            &mut e,
            json!({"op":"select","revision":r,"id":movie,"resume":-3.0})
        )["resume"]
            .is_null()
    );
    let live = browse(&mut e, r, "live")[0]["id"].clone();
    assert!(
        call(
            &mut e,
            json!({"op":"select","revision":r,"id":live,"resume":120.0})
        )["resume"]
            .is_null()
    );
    assert_eq!(
        call(&mut e, json!({"op":"entry","revision":r,"id":live}))["live"],
        true
    );
}

#[test]
fn older_schema_catalogs_report_unkeyed_resolution() {
    let f = Fixture::new();
    let (mut e, r) = f.build("unsalted", PLAYLIST, "");
    // An unsalted build is still schema 3 and keyed; the in-memory path is not.
    assert_eq!(
        call(&mut e, json!({"op":"resolve","revision":r,"keys":[]}))["keyed"],
        true
    );
    let mut memory = Engine::default();
    let p = f.file("memory.m3u", PLAYLIST);
    let rev = call(&mut memory, json!({"op":"import","path":p}))["revision"].clone();
    assert_eq!(
        call(
            &mut memory,
            json!({"op":"resolve","revision":rev,"keys":["c:00"]})
        )["keyed"],
        false
    );
}
