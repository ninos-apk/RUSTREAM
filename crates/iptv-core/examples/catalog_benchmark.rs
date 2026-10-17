//! Offline, aggregate-only benchmark of the production disk catalog path.
use aes_gcm::{Aes256Gcm, KeyInit, aead::OsRng};
use iptv_core::{catalog::Catalog, playlist::parse_stream, service::Engine};
use serde_json::{Value, json};
use std::{fs, io::BufReader, time::Instant};
fn call(engine: &mut Engine, v: Value) -> Result<Value, String> {
    engine.execute(serde_json::from_value(v).map_err(|_| "request_failed")?)
}
fn main() {
    if let Err(code) = run() {
        eprintln!("catalog_benchmark_failed: {code}");
        std::process::exit(1)
    }
}
fn run() -> Result<(), String> {
    let args: Vec<_> = std::env::args().collect();
    let path = args.get(1).ok_or("missing_path")?;
    let output = args.get(2).ok_or("missing_output")?;
    let db = std::path::Path::new(output).with_extension("temporary.db");
    if db.exists() {
        return Err("temporary_target_exists".into());
    }
    let dbpath = db.to_string_lossy().into_owned();
    let key = Aes256Gcm::generate_key(&mut OsRng)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect::<String>();
    let result = (|| {
        let mut engine = Engine::default();
        let started = Instant::now();
        let built = call(
            &mut engine,
            json!({"op":"build_catalog","path":path,"target":dbpath,"key":key}),
        )?;
        let build_ms = started.elapsed().as_secs_f64() * 1000.0;
        let bytes = fs::metadata(&db).map_err(|_| "metadata_failed")?.len();
        let mut restores = Vec::new();
        let mut revision = 0;
        for _ in 0..5 {
            let now = Instant::now();
            revision = call(
                &mut engine,
                json!({"op":"open_catalog","path":dbpath,"key":key}),
            )?["revision"]
                .as_u64()
                .ok_or("revision_failed")?;
            restores.push(now.elapsed().as_secs_f64() * 1000.0);
        }
        let categories = call(&mut engine, json!({"op":"categories"}))?;
        let mut searches = Vec::new();
        for kind in ["live", "movies", "series", "unclassified"] {
            for query in ["", "hd", "not-a-real-channel-982361"] {
                let now = Instant::now();
                let page = call(
                    &mut engine,
                    json!({"op":"browse","revision":revision,"kind":kind,"query":query,"offset":0,"limit":64}),
                )?;
                searches.push(json!({"kind":kind,"query":query,"ms":now.elapsed().as_secs_f64()*1000.0,"total":page["total"]}));
            }
        }
        let catalog = Catalog::open(&dbpath, &key)?;
        let mut index = 0;
        let groups = categories["categories"]
            .as_array()
            .ok_or("categories_failed")?;
        parse_stream(
            BufReader::with_capacity(256 * 1024, fs::File::open(path).map_err(|_| "open_failed")?),
            None,
            usize::MAX,
            |expected, group| {
                let actual = catalog
                    .channel(index)
                    .map_err(|_| std::io::Error::other("catalog_read_failed"))?;
                let matches = expected.name == actual.name
                    && expected.kind == actual.kind
                    && expected.url == actual.url
                    && expected.tvg_id == actual.tvg_id
                    && expected.logo == actual.logo
                    && expected.headers == actual.headers
                    && expected.is_live == actual.is_live
                    && expected.display_name == actual.display_name
                    && expected.tvg_name == actual.tvg_name
                    && expected.group_title == actual.group_title
                    && expected.attributes == actual.attributes
                    && expected.url_path == actual.url_path
                    && expected.extension == actual.extension
                    && expected.year == actual.year
                    && expected.quality == actual.quality
                    && expected.episode == actual.episode
                    && groups[actual.category]["name"] == group;
                if !matches {
                    return Err(std::io::Error::other("field_parity_failed"));
                }
                index += 1;
                Ok(())
            },
        )
        .map_err(|_| "field_parity_failed")?;
        let db_check =
            rusqlite::Connection::open_with_flags(&db, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)
                .map_err(|_| "verification_open_failed")?;
        let uncovered:usize=db_check.query_row("SELECT count(*) FROM channels c LEFT JOIN members m ON m.channel=c.id WHERE c.kind=2 AND c.series_id IS NULL AND c.parent IS NULL AND m.channel IS NULL",[],|r|r.get(0)).map_err(|_|"coverage_query_failed")?;
        if uncovered != 0 {
            return Err("series_coverage_failed".into());
        }
        let memberships: usize = db_check
            .query_row("SELECT count(*) FROM members", [], |r| r.get(0))
            .map_err(|_| "coverage_query_failed")?;
        let report = json!({"count":built["count"],"skipped":built["skipped"],"build_ms":build_ms,"database_bytes":bytes,"open_ms":restores,"kinds":categories["kinds"],"categories":groups.len(),"all_channel_fields_verified":index,"series_memberships":memberships,"uncovered_series_episodes":uncovered,"searches":searches});
        fs::write(
            output,
            serde_json::to_vec_pretty(&report).map_err(|_| "encode_failed")?,
        )
        .map_err(|_| "write_failed")?;
        println!(
            "count={}, build_ms={build_ms:.1}, fields_verified={index}",
            built["count"]
        );
        Ok(())
    })();
    let _ = fs::remove_file(db);
    result
}
