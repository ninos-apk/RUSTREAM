use iptv_core::service::{Engine, Request};
use serde_json::{Value, json};
use std::{
    fs,
    sync::atomic::{AtomicU64, Ordering},
    time::Duration,
};
static NEXT: AtomicU64 = AtomicU64::new(1);
struct Fixture(std::path::PathBuf);
impl Fixture {
    fn new(contents: &str) -> Self {
        let path = std::env::temp_dir().join(format!(
            "iptv-tv-test-{}-{}.tmp",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::write(&path, contents).unwrap();
        Self(path)
    }
    fn path(&self) -> String {
        self.0.to_string_lossy().into_owned()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}
fn run(engine: &mut Engine, now: u64, request: Value) -> Value {
    engine
        .execute_at(
            serde_json::from_value(request).unwrap(),
            Duration::from_secs(now),
        )
        .unwrap()
}
fn setup() -> (Engine, u64) {
    let fixture = Fixture::new(
        "#EXTM3U\n#EXTINF:-1 tvg-id=\"a\" group-title=\"News\",Live\nhttps://example.test/live.ts\n#EXTINF:100 group-title=\"Filme\",Movie\nhttps://example.test/movie.mp4\n",
    );
    let mut engine = Engine::default();
    let result = run(&mut engine, 0, json!({"op":"import","path":fixture.path()}));
    (engine, result["revision"].as_u64().unwrap())
}
#[test]
fn paging_never_transfers_credentials_and_invalid_ids_are_errors() {
    let (mut e, revision) = setup();
    let result = run(
        &mut e,
        0,
        json!({"op":"search","revision":revision,"query":"","offset":0,"limit":1}),
    );
    assert_eq!(result["total"], 2);
    assert_eq!(result["items"].as_array().unwrap().len(), 1);
    assert!(!result.to_string().contains("https"));
    let result = run(
        &mut e,
        0,
        json!({"op":"search","revision":revision,"query":"movie","offset":0,"limit":99999}),
    );
    assert_eq!(result["items"][0]["id"], 1);
    assert!(
        e.execute_at(
            Request::Select {
                revision,
                id: 999,
                resume: None
            },
            Duration::ZERO
        )
        .is_err()
    );
    assert!(
        e.execute_at(
            Request::Select {
                revision: revision + 1,
                id: 0,
                resume: None
            },
            Duration::ZERO
        )
        .is_err()
    );
}

#[test]
fn content_sections_keep_category_ids_search_cache_and_pages_separate() {
    let fixture = Fixture::new(
        "#EXTINF:-1 group-title=Drama,News HD\nhttps://example.test/live/u/p/1.ts\n\
         #EXTINF:-1 group-title=Drama,Movie HD\nhttps://example.test/movie/u/p/2.mp4\n\
         #EXTINF:-1 group-title=Drama,Show S01E01\nhttps://example.test/series/u/p/3.mp4\n\
         #EXTINF:-1 group-title=Drama,Movie SD\nhttps://example.test/movie/u/p/4.mp4\n",
    );
    let mut engine = Engine::default();
    let imported = run(&mut engine, 0, json!({"op":"import","path":fixture.path()}));
    let revision = imported["revision"].as_u64().unwrap();
    let categories = run(&mut engine, 0, json!({"op":"categories"}));
    assert_eq!(categories["preferred_kind"], "live");
    assert_eq!(categories["kinds"][1]["count"], 2);
    assert_eq!(categories["categories"].as_array().unwrap().len(), 3);
    for (index, kind) in ["live", "movies", "series"].into_iter().enumerate() {
        assert_eq!(categories["categories"][index]["name"], "Drama");
        assert_eq!(categories["categories"][index]["kind"], kind);
        let result = run(
            &mut engine,
            0,
            json!({"op":"search","revision":revision,"kind":kind,"query":"drama","offset":0,"limit":1}),
        );
        assert_eq!(result["items"][0]["id"], index);
        assert_eq!(result["items"][0]["kind"], kind);
        assert_eq!(result["total"], if kind == "movies" { 2 } else { 1 });
    }
    let page = run(
        &mut engine,
        0,
        json!({"op":"search","revision":revision,"kind":"movies","query":"drama","offset":1,"limit":1}),
    );
    assert_eq!(page["items"][0]["id"], 3);
    let crossed = run(
        &mut engine,
        0,
        json!({"op":"search","revision":revision,"kind":"series","category":1,"query":"","offset":0,"limit":64}),
    );
    assert_eq!(crossed["total"], 0);
    let filtered = run(
        &mut engine,
        0,
        json!({"op":"search","revision":revision,"kind":"movies","category":1,"query":"HD","offset":0,"limit":64}),
    );
    assert_eq!(filtered["total"], 1);
    assert_eq!(filtered["items"][0]["name"], "Movie HD");
    assert!(engine.execute(serde_json::from_value(json!({"op":"search","revision":revision,"kind":"unknown","query":"","offset":0,"limit":64})).unwrap()).is_err());
    let vod_only = Fixture::new("#EXTINF:-1,Only Movie\nhttps://example.test/movie/u/p/9.mp4");
    run(
        &mut engine,
        0,
        json!({"op":"import","path":vod_only.path()}),
    );
    assert_eq!(
        run(&mut engine, 0, json!({"op":"categories"}))["preferred_kind"],
        "movies"
    );
}
#[test]
fn replacement_rejects_old_revision_and_failed_import_preserves_current_playlist() {
    let (mut e, revision) = setup();
    let bad = Fixture::new("<html>error</html>");
    assert!(
        e.execute(Request::Import {
            path: bad.path(),
            base: None
        })
        .is_err()
    );
    assert_eq!(run(&mut e, 0, json!({"op":"categories"}))["count"], 2);
    let smaller = Fixture::new("#EXTINF:-1,One\nhttps://example.test/one.ts");
    run(&mut e, 1, json!({"op":"import","path":smaller.path()}));
    assert!(
        e.execute(Request::Search {
            revision,
            kind: None,
            category: None,
            query: String::new(),
            offset: 0,
            limit: 1
        })
        .is_err()
    );
}
#[test]
fn reconnect_ignores_callbacks_from_both_old_channels_and_old_attempts() {
    let (mut e, revision) = setup();
    let a = run(&mut e, 0, json!({"op":"select","revision":revision,"id":0}))["attempt"]
        .as_u64()
        .unwrap();
    run(&mut e, 1, json!({"op":"ended","attempt":a,"error":true}));
    let b = run(&mut e, 4, json!({"op":"tick"}));
    assert_eq!(b["action"], "load");
    assert_ne!(b["attempt"], a);
    assert_eq!(
        run(&mut e, 5, json!({"op":"ended","attempt":a,"error":true}))["phase"],
        "connecting"
    );
    let c = run(&mut e, 6, json!({"op":"select","revision":revision,"id":1}));
    assert_eq!(
        run(
            &mut e,
            7,
            json!({"op":"ended","attempt":b["attempt"],"error":true})
        )["phase"],
        "connecting"
    );
    run(
        &mut e,
        8,
        json!({"op":"sample","attempt":c["attempt"],"position":34,"buffered":10,"buffering":false}),
    );
    run(
        &mut e,
        9,
        json!({"op":"ended","attempt":c["attempt"],"error":true}),
    );
    let reload = run(&mut e, 12, json!({"op":"tick"}));
    assert_eq!(reload["resume"], 34.0);
}
#[test]
fn lifecycle_pause_stop_and_vod_completion_never_reconnect() {
    let (mut e, revision) = setup();
    let a = run(&mut e, 0, json!({"op":"select","revision":revision,"id":1}));
    run(&mut e, 1, json!({"op":"pause","paused":true}));
    assert_eq!(run(&mut e, 900, json!({"op":"tick"}))["phase"], "paused");
    run(&mut e, 901, json!({"op":"pause","paused":false}));
    run(
        &mut e,
        902,
        json!({"op":"ended","attempt":a["attempt"],"error":false}),
    );
    assert_eq!(run(&mut e, 1800, json!({"op":"tick"}))["phase"], "finished");
    run(&mut e, 1801, json!({"op":"stop"}));
    run(
        &mut e,
        1802,
        json!({"op":"ended","attempt":a["attempt"],"error":true}),
    );
    assert_eq!(run(&mut e, 9999, json!({"op":"tick"}))["phase"], "idle");
}
#[test]
fn missing_telemetry_still_runs_startup_watchdog() {
    let (mut e, revision) = setup();
    run(&mut e, 0, json!({"op":"select","revision":revision,"id":0}));
    assert_eq!(run(&mut e, 26, json!({"op":"tick"}))["phase"], "waiting");
    assert_eq!(run(&mut e, 28, json!({"op":"tick"}))["action"], "load");
}

#[test]
fn large_provider_catalog_exceeds_the_old_limits_without_truncation() {
    // Synthetic data reproduces the size/count problem without shipping a provider's credentials.
    let mut input = String::from("#EXTM3U\n");
    for id in 0..257_468 {
        use std::fmt::Write;
        writeln!(input,"#EXTINF:-1 group-title=\"Catalog\",Channel {id}\nhttps://example.test/stream/{id}?padding={}","x".repeat(180)).unwrap();
    }
    assert!(input.len() > 32 * 1024 * 1024);
    let fixture = Fixture::new(&input);
    drop(input);
    let mut engine = Engine::default();
    let result = run(&mut engine, 0, json!({"op":"import","path":fixture.path()}));
    assert_eq!(result["count"], 257_468);
    assert_eq!(result["skipped"], 0);
    let end = run(
        &mut engine,
        0,
        json!({"op":"search","revision":result["revision"],"query":"","offset":257_458,"limit":64}),
    );
    assert_eq!(end["items"].as_array().unwrap().len(), 10);
    assert_eq!(end["items"][9]["id"], 257_467);
}

#[test]
fn oversized_import_is_rejected_without_replacing_the_previous_library() {
    let fixture = Fixture::new("");
    fs::OpenOptions::new()
        .write(true)
        .open(&fixture.0)
        .unwrap()
        .set_len(iptv_core::service::IMPORT_LIMIT + 1)
        .unwrap();
    let (mut engine, revision) = setup();
    assert_eq!(
        engine
            .execute(Request::Import {
                path: fixture.path(),
                base: None
            })
            .unwrap_err(),
        "import_too_large"
    );
    let result = run(
        &mut engine,
        0,
        json!({"op":"search","revision":revision,"query":"","offset":0,"limit":64}),
    );
    assert_eq!(result["total"], 2);
}

#[test]
fn repeated_unpaused_notifications_do_not_disable_the_watchdog() {
    let (mut e, revision) = setup();
    run(&mut e, 0, json!({"op":"select","revision":revision,"id":0}));
    for now in 1..=26 {
        run(&mut e, now, json!({"op":"pause","paused":false}));
    }
    assert_eq!(run(&mut e, 26, json!({"op":"tick"}))["phase"], "waiting");
}

#[test]
fn poll_observes_before_ticking_and_rejects_stale_pause_and_watchdog_updates() {
    let (mut e, revision) = setup();
    let a = run(&mut e, 0, json!({"op":"select","revision":revision,"id":0}))["attempt"].clone();
    assert_eq!(
        run(
            &mut e,
            1,
            json!({"op":"poll","attempt":a,"position":0,"buffering":false,"paused":false})
        )["phase"],
        "connecting"
    );
    // A fresh position must be observed before the same poll evaluates the stall timeout.
    assert_eq!(
        run(
            &mut e,
            15,
            json!({"op":"poll","attempt":a,"position":14,"buffering":false,"paused":false})
        )["phase"],
        "playing"
    );
    let b = run(
        &mut e,
        16,
        json!({"op":"select","revision":revision,"id":1}),
    )["attempt"]
        .clone();
    assert_eq!(
        run(
            &mut e,
            100,
            json!({"op":"poll","attempt":a,"position":999,"buffering":false,"paused":true})
        )["phase"],
        "connecting"
    );
    // The stale poll neither pauses the new session nor runs its overdue watchdog.
    assert_eq!(
        run(
            &mut e,
            100,
            json!({"op":"poll","attempt":b,"position":0,"buffering":false,"paused":false})
        )["phase"],
        "waiting"
    );
}

#[test]
fn polls_keep_pause_idempotent_and_expose_precise_live_eof_deadline() {
    let (mut e, revision) = setup();
    let a = run(&mut e, 0, json!({"op":"select","revision":revision,"id":0}))["attempt"].clone();
    for second in 1..=3 {
        run(
            &mut e,
            second,
            json!({"op":"poll","attempt":a,"position":second,"buffering":false,"paused":false}),
        );
    }
    let ended = run(&mut e, 4, json!({"op":"ended","attempt":a,"error":false}));
    let delay = ended["retry_in_ms"].as_u64().unwrap();
    assert!(delay <= 250);
    let before = e
        .execute_at(Request::Tick, Duration::from_millis(4_000 + delay - 1))
        .unwrap();
    assert_eq!(before["phase"], "waiting");
    let reload = e
        .execute_at(Request::Tick, Duration::from_millis(4_000 + delay))
        .unwrap();
    assert_eq!(reload["action"], "load");
    for second in 5..=31 {
        run(
            &mut e,
            second,
            json!({"op":"poll","attempt":reload["attempt"],"position":0,"buffering":false,"paused":false}),
        );
    }
    assert_eq!(run(&mut e, 31, json!({"op":"tick"}))["phase"], "waiting");
    run(&mut e, 32, json!({"op":"stop"}));
    assert_eq!(run(&mut e, 100, json!({"op":"tick"}))["phase"], "idle");
}

#[test]
fn hls_manifests_represent_a_single_channel_and_keep_the_final_url() {
    let fixture = Fixture::new(
        "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n",
    );
    let mut e = Engine::default();
    let result = run(
        &mut e,
        0,
        json!({"op":"import","path":fixture.path(),"base":"https://example.test/final/index.m3u8"}),
    );
    assert_eq!(result["count"], 1);
    let selected = run(
        &mut e,
        0,
        json!({"op":"select","revision":result["revision"],"id":0}),
    );
    assert_eq!(selected["url"], "https://example.test/final/index.m3u8");
    assert_eq!(selected["live"], false);
    assert_eq!(
        run(&mut e, 0, json!({"op":"categories"}))["preferred_kind"],
        "movies"
    );
    assert!(
        e.execute(Request::Import {
            path: fixture.path(),
            base: None
        })
        .is_err()
    );
}
