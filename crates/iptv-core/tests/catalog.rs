use iptv_core::service::Engine;
use serde_json::{Value, json};
use std::{
    fs,
    io::Write,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};
static NEXT: AtomicU64 = AtomicU64::new(1);
const KEY: &str = "9f3b5b613ed641a70328ae71bb79cdad078c915f519af44f62e3277cad927816";
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "tv-catalog-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn file(&self, name: &str, text: &str) -> String {
        let path = self.0.join(name);
        fs::write(&path, text).unwrap();
        path.to_string_lossy().into()
    }
    fn path(&self, name: &str) -> String {
        self.0.join(name).to_string_lossy().into()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}
fn call(e: &mut Engine, v: Value) -> Result<Value, String> {
    e.execute(serde_json::from_value(v).unwrap())
}
fn build(e: &mut Engine, input: &str, target: &str) -> Result<Value, String> {
    call(
        e,
        json!({"op":"build_catalog","path":input,"target":target,"key":KEY}),
    )
}
fn open(e: &mut Engine, target: &str) -> Value {
    call(e, json!({"op":"open_catalog","path":target,"key":KEY})).unwrap()
}

#[test]
fn disk_catalog_preserves_fields_categories_search_and_encrypted_sources() {
    let f = Fixture::new();
    let path=f.file("list.m3u","#EXTM3U\n#EXTINF:-1 tvg-id=a group-title='Dramä',News HD\nhttps://provider.test/live/private-user/private-password/1.ts|User-Agent=private-header\n#EXTINF:-1 group-title='Dramä',Film SD\nhttps://provider.test/movie/u/p/2.mp4\n#EXTINF:-1 group-title='Dramä',Show S01E01\nhttps://provider.test/series/u/p/3.mkv\n");
    let db = f.path("catalog.db");
    let mut e = Engine::default();
    assert_eq!(build(&mut e, &path, &db).unwrap()["count"], 3);
    let bytes = fs::read(&db).unwrap();
    let text = String::from_utf8_lossy(&bytes);
    for secret in [
        "private-user",
        "private-password",
        "private-header",
        "https://provider.test",
    ] {
        assert!(!text.contains(secret));
    }
    let rev = open(&mut e, &db)["revision"].as_u64().unwrap();
    let cats = call(&mut e, json!({"op":"categories"})).unwrap();
    assert_eq!(cats["categories"].as_array().unwrap().len(), 3);
    let page=call(&mut e,json!({"op":"search","revision":rev,"kind":"live","query":"DRAMÄ hd","offset":0,"limit":64})).unwrap();
    assert_eq!(page["total"], 1);
    assert!(!page.to_string().contains("private-password"));
    let selected = call(&mut e, json!({"op":"select","revision":rev,"id":0})).unwrap();
    assert!(
        selected["url"]
            .as_str()
            .unwrap()
            .contains("private-password")
    );
    assert_eq!(selected["user_agent"], "private-header");
    let mut cold = Engine::default();
    assert_eq!(open(&mut cold, &db)["count"], 3);
    assert_eq!(
        call(
            &mut e,
            json!({"op":"open_catalog","path":db,"key":"11".repeat(32)})
        )
        .unwrap_err(),
        "catalog_key_failed"
    );
    assert_eq!(
        call(&mut e, json!({"op":"categories"})).unwrap()["revision"],
        rev
    );
}

#[test]
fn rejects_html_json_binary_and_preserves_previous_catalog() {
    let f = Fixture::new();
    let good = f.file(
        "good",
        "#EXTM3U\n#EXTINF:-1,Good\nhttps://example.test/1.ts\n",
    );
    let db = f.path("good.db");
    let mut e = Engine::default();
    build(&mut e, &good, &db).unwrap();
    let rev = open(&mut e, &db)["revision"].as_u64().unwrap();
    for (i, text) in [
        "<html>\n<body>Login</body>\n</html>",
        "{\"error\":\"login\"}",
        "\0bad",
        "not a playlist",
    ]
    .into_iter()
    .enumerate()
    {
        let path = f.file(&format!("bad{i}"), text);
        let target = f.path(&format!("bad{i}.db"));
        let result = call(
            &mut e,
            json!({"op":"build_catalog","path":path,"base":"https://example.test/list","target":target,"key":KEY}),
        );
        assert_eq!(result.unwrap_err(), "invalid_playlist_format");
        assert!(!std::path::Path::new(&target).exists());
        assert_eq!(
            call(&mut e, json!({"op":"categories"})).unwrap()["revision"],
            rev
        );
    }
    assert_eq!(
        build(&mut e, &good, &db).unwrap_err(),
        "catalog_target_exists"
    );
    assert!(std::path::Path::new(&db).exists());
}

#[test]
fn accepts_relative_extended_m3u_plain_urls_and_finite_hls() {
    let f = Fixture::new();
    for (i, text, count, movie) in [
        (0, "#EXTM3U\n#EXTINF:-1,Relative\nfile.ts\n", 1, false),
        (
            1,
            "https://example.test/1.ts\nhttps://example.test/2.ts",
            2,
            false,
        ),
        (
            2,
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n",
            1,
            true,
        ),
    ] {
        let path = f.file(&format!("list{i}"), text);
        let db = f.path(&format!("cat{i}.db"));
        let mut e = Engine::default();
        assert_eq!(call(&mut e,json!({"op":"build_catalog","path":path,"base":"https://example.test/list.m3u8","target":db,"key":KEY})).unwrap()["count"],count);
        let rev = open(&mut e, &db)["revision"].as_u64().unwrap();
        let c = call(&mut e, json!({"op":"select","revision":rev,"id":0})).unwrap();
        assert_eq!(c["live"], !movie);
    }
}

#[test]
fn cancelled_build_is_removed_before_commit() {
    let f = Fixture::new();
    let path = f.file("list", "#EXTM3U\n#EXTINF:-1,A\nhttps://example.test/1\n");
    let db = f.path("cancelled.db");
    let mut e = Engine::default();
    e.cancelled.store(true, Ordering::Relaxed);
    assert_eq!(build(&mut e, &path, &db).unwrap_err(), "import_cancelled");
    assert!(!std::path::Path::new(&db).exists());
}

#[test]
fn xtream_series_are_lazy_ordered_and_failed_refresh_preserves_episodes() {
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
    let db = f.path("xtream.db");
    let mut e = Engine::default();
    assert_eq!(
        call(
            &mut e,
            json!({"op":"build_catalog","path":manifest,"target":db,"key":KEY,"xtream":true})
        )
        .unwrap()["count"],
        1
    );
    let rev = open(&mut e, &db)["revision"].as_u64().unwrap();
    assert_eq!(
        call(&mut e, json!({"op":"entry","revision":rev,"id":0})).unwrap()["series_id"],
        "123"
    );
    assert_eq!(
        call(&mut e, json!({"op":"select","revision":rev,"id":0})).unwrap_err(),
        "series_requires_episode"
    );
    assert!(
        call(
            &mut e,
            json!({"op":"episodes","revision":rev,"id":0,"offset":0})
        )
        .unwrap()["items"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    let episodes=f.file("episodes.json",r#"{"episodes":{"2":[{"id":"222","title":"Second season","episode_num":1,"container_extension":"mkv"}],"1":[{"id":112,"title":"Second","episode_num":2},{"id":111,"title":"First","episode_num":1}]}}"#);
    let result = call(
        &mut e,
        json!({"op":"resolve_series","revision":rev,"id":0,"path":episodes}),
    )
    .unwrap();
    assert_eq!(result["items"][0]["name"], "First");
    assert_eq!(result["seasons"].as_array().unwrap().len(), 2);
    let id = result["items"][0]["id"].as_u64().unwrap();
    assert!(
        call(&mut e, json!({"op":"select","revision":rev,"id":id})).unwrap()["url"]
            .as_str()
            .unwrap()
            .ends_with("/series/user/secret/111.mp4")
    );
    let bad = f.file("bad.json", r#"{"episodes":{"1":[{"id":"bad-id"}]}}"#);
    assert!(
        call(
            &mut e,
            json!({"op":"resolve_series","revision":rev,"id":0,"path":bad})
        )
        .is_err()
    );
    assert_eq!(
        call(
            &mut e,
            json!({"op":"episodes","revision":rev,"id":0,"offset":0})
        )
        .unwrap()["items"]
            .as_array()
            .unwrap()
            .len(),
        3
    );
    assert_eq!(
        call(&mut e, json!({"op":"categories"})).unwrap()["count"],
        1
    );
}

#[test]
#[ignore = "explicit release stress run: creates a temporary >128 MiB million-entry playlist"]
fn million_entries_over_128_mib_remain_paged_and_reopenable() {
    let f = Fixture::new();
    let path = f.path("million.m3u");
    let mut out = std::io::BufWriter::new(fs::File::create(&path).unwrap());
    writeln!(out, "#EXTM3U").unwrap();
    for i in 0..1_000_001 {
        writeln!(out,"#EXTINF:-1 group-title=\"Group {}\" tvg-logo=\"https://images.example.test/very-long-synthetic-logo-name.png\",Entry {i}\nhttps://provider.test/series/synthetic-user/synthetic-password/{i}.mkv",i%100).unwrap();
    }
    out.flush().unwrap();
    drop(out);
    assert!(fs::metadata(&path).unwrap().len() > 128 * 1024 * 1024);
    let db = f.path("million.db");
    let mut e = Engine::default();
    assert_eq!(build(&mut e, &path, &db).unwrap()["count"], 1_000_001);
    let rev = open(&mut e, &db)["revision"].as_u64().unwrap();
    let page=call(&mut e,json!({"op":"search","revision":rev,"kind":"series","query":"","offset":1_000_000,"limit":128})).unwrap();
    assert_eq!(page["items"].as_array().unwrap().len(), 1);
    assert_eq!(page["items"][0]["id"], 1_000_000);
    drop(e);
    let mut cold = Engine::default();
    assert_eq!(open(&mut cold, &db)["count"], 1_000_001);
}
