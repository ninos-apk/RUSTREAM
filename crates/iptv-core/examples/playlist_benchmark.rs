//! Local import benchmark; reports aggregates only and never contacts playlist URLs.
use iptv_core::{
    playlist::{ContentKind, Playlist, parse_file},
    service::{Engine, Request},
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, hash_map::DefaultHasher},
    hash::{Hash, Hasher},
    path::Path,
    time::Instant,
};

fn stats(playlist: &Playlist) -> Value {
    let mut routes = BTreeMap::<String, usize>::new();
    let mut hash = DefaultHasher::new();
    let mut route_series_misclassified = 0;
    let mut ungrouped = 0;
    for c in &playlist.channels {
        c.name.hash(&mut hash);
        c.kind.hash(&mut hash);
        c.category.hash(&mut hash);
        c.url.hash(&mut hash);
        c.tvg_id.hash(&mut hash);
        c.logo.hash(&mut hash);
        c.headers.user_agent.hash(&mut hash);
        c.headers.referrer.hash(&mut hash);
        let url = url::Url::parse(&c.url).expect("parser returned invalid URL");
        let parts: Vec<_> = url.path().trim_start_matches('/').split('/').collect();
        let route = if parts.len() >= 4 {
            parts[parts.len() - 4]
        } else {
            parts[0]
        };
        let bucket = match route {
            "live" => "live",
            "movie" | "movies" => "movie",
            "series" => "series",
            _ => "other",
        };
        *routes.entry(bucket.into()).or_default() += 1;
        route_series_misclassified +=
            usize::from(bucket == "series" && c.kind != ContentKind::Series);
        ungrouped += usize::from(playlist.categories[c.category].name == "Ohne Kategorie");
    }
    let mut groups = [0; 3];
    for c in &playlist.categories {
        c.name.hash(&mut hash);
        c.kind.hash(&mut hash);
        c.channels.hash(&mut hash);
        groups[match c.kind {
            ContentKind::LiveTv => 0,
            ContentKind::Movie => 1,
            ContentKind::Series => 2,
        }] += 1;
    }
    json!({"channels":playlist.channels.len(),"categories":playlist.categories.len(),
        "live":playlist.count(ContentKind::LiveTv),"movies":playlist.count(ContentKind::Movie),
        "series":playlist.count(ContentKind::Series),"categories_by_kind":groups,
        "skipped":playlist.skipped,"ungrouped_channels":ungrouped,"routes":routes,
        "series_route_misclassified":route_series_misclassified,"aggregate_fingerprint":format!("{:016x}", hash.finish())})
}

fn run(path: &str) -> Result<Value, &'static str> {
    let bytes = std::fs::metadata(path)
        .map_err(|_| "metadata_failed")?
        .len();
    let mut parse_runs = Vec::new();
    let mut statistics = Value::Null;
    for i in 0..5 {
        let started = Instant::now();
        let playlist = parse_file(Path::new(path)).map_err(|_| "parse_failed")?;
        parse_runs.push(started.elapsed().as_secs_f64() * 1000.0);
        if i == 0 {
            statistics = stats(&playlist);
        }
    }
    let mut imports = Vec::new();
    for remote in [false, true] {
        let mut engine = Engine::default();
        for reload in [false, true] {
            let started = Instant::now();
            let result = engine
                .execute(Request::Import {
                    path: path.into(),
                    base: remote.then(|| "https://benchmark.invalid/list.m3u".into()),
                })
                .map_err(|_| "service_import_failed")?;
            let elapsed = started.elapsed().as_secs_f64() * 1000.0;
            let revision = result["revision"].as_u64().ok_or("missing_revision")?;
            let started = Instant::now();
            let categories = engine
                .execute(Request::Categories)
                .map_err(|_| "categories_failed")?;
            let categories_json =
                serde_json::to_string(&categories).map_err(|_| "encode_failed")?;
            let category_ms = started.elapsed().as_secs_f64() * 1000.0;
            let mut searches = Vec::new();
            for kind in ["live", "movies", "series"] {
                for query in ["", "hd", "not-a-real-channel-982361"] {
                    let started = Instant::now();
                    let page = engine
                        .execute(Request::Search {
                            revision,
                            kind: Some(kind.into()),
                            category: None,
                            query: query.into(),
                            offset: 0,
                            limit: 64,
                        })
                        .map_err(|_| "search_failed")?;
                    searches.push(json!({"kind":kind,"query_id":query,"ms":started.elapsed().as_secs_f64()*1000.0,"total":page["total"],"page_size":page["items"].as_array().ok_or("missing_items")?.len()}));
                }
            }
            let total = statistics["channels"].as_u64().ok_or("missing_count")? as usize;
            let last = engine
                .execute(Request::Search {
                    revision,
                    kind: None,
                    category: None,
                    query: String::new(),
                    offset: total - 10,
                    limit: 64,
                })
                .map_err(|_| "last_page_failed")?;
            imports.push(json!({"url_mode":remote,"reload":reload,"total_import_ms":elapsed,"core_import_ms":result["import_ms"],"category_json_ms":category_ms,"category_json_bytes":categories_json.len(),"kinds":categories["kinds"],"last_page_size":last["items"].as_array().ok_or("missing_items")?.len(),"searches":searches}));
        }
    }
    Ok(
        json!({"bytes":bytes,"parse_ms":parse_runs,"statistics":statistics,"service_imports":imports,"network_requests":0}),
    )
}

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let outcome = (|| {
        let report = run(args.get(1).ok_or("missing_path")?)?;
        std::fs::write(
            args.get(2).ok_or("missing_output")?,
            serde_json::to_vec_pretty(&report).map_err(|_| "encode_failed")?,
        )
        .map_err(|_| "write_failed")?;
        println!("{}", report["statistics"]);
        Ok::<_, &str>(())
    })();
    if let Err(code) = outcome {
        eprintln!("benchmark_failed: {code}");
        std::process::exit(1);
    }
}
