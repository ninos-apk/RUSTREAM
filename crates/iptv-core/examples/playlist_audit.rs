//! Local, URL-free statistics for a private playlist. Never prints names, URLs or credentials.
use iptv_core::{
    playlist::{filter_channels, parse_file},
    service::{Engine, Request},
};
use serde_json::json;
use std::{collections::BTreeMap, path::Path, time::Instant};

fn main() {
    if let Err(code) = audit() {
        eprintln!("audit_failed: {code}");
        std::process::exit(1);
    }
}
fn audit() -> Result<(), &'static str> {
    let path = std::env::args().nth(1).ok_or("missing_path")?;
    let output = std::env::args().nth(2).ok_or("missing_output")?;
    let bytes = std::fs::metadata(&path).map_err(|_| "open_failed")?.len();
    let before = Instant::now();
    let baseline = Engine::default().execute(Request::Import {
        path: path.clone(),
        base: None,
    });
    let service_result = match baseline {
        Ok(v) => json!({"accepted":true,"count":v["count"]}),
        Err(code) => json!({"accepted":false,"code":code}),
    };
    let start = Instant::now();
    let playlist = parse_file(Path::new(&path)).map_err(|_| "parse_failed")?;
    let parse_ms = start.elapsed().as_secs_f64() * 1000.0;
    let mut protocols = BTreeMap::<String, usize>::new();
    let mut formats = BTreeMap::<String, usize>::new();
    let mut live = 0;
    let mut ids = 0;
    let mut headers = 0;
    let mut sample = BTreeMap::<String, Vec<usize>>::new();
    for (id, c) in playlist.channels.iter().enumerate() {
        let url = url::Url::parse(&c.url).map_err(|_| "invalid_url")?;
        *protocols.entry(url.scheme().to_owned()).or_default() += 1;
        let extension = url
            .path()
            .rsplit_once('.')
            .map(|(_, ext)| ext.to_ascii_lowercase())
            .filter(|ext| ext.len() <= 8 && ext.bytes().all(|b| b.is_ascii_alphanumeric()))
            .unwrap_or_else(|| "unspecified".into());
        *formats.entry(extension).or_default() += 1;
        live += usize::from(c.is_live);
        ids += usize::from(!c.tvg_id.is_empty());
        headers += usize::from(c.headers.user_agent.is_some() || c.headers.referrer.is_some());
        let text = format!("{} {}", playlist.categories[c.category].name, c.name).to_lowercase();
        let german = text.contains("german")
            || text.contains("deutsch")
            || text.contains("[de]")
            || text.contains("de|")
            || text.starts_with("de ")
            || text.starts_with("de:");
        let bucket = if c.is_live {
            if german {
                if text.contains("uhd") || text.contains("4k") {
                    "de_uhd"
                } else if text.contains("fhd") {
                    "de_fhd"
                } else if text.contains(" hd") {
                    "de_hd"
                } else {
                    "de_live"
                }
            } else {
                "other_live"
            }
        } else if url.path().contains("/series/") {
            "series"
        } else {
            "vod"
        };
        let rows = sample.entry(bucket.to_owned()).or_default();
        if rows.len() < 8 {
            rows.push(id);
        }
        if c.is_live
            && german
            && ["das erste", "zdf", "3sat", "arte", "servustv"]
                .iter()
                .any(|s| text.contains(s))
        {
            let rows = sample.entry("de_free".into()).or_default();
            if rows.len() < 12 {
                rows.push(id);
            }
        }
        if c.is_live
            && german
            && (c.name.to_lowercase().contains("uhd") || c.name.to_lowercase().contains("4k"))
        {
            let rows = sample.entry("de_explicit_uhd".into()).or_default();
            if rows.len() < 4 {
                rows.push(id);
            }
        }
        if !c.is_live && german {
            let label = if url.path().contains("/series/") {
                "de_series"
            } else {
                "de_vod"
            };
            let rows = sample.entry(label.into()).or_default();
            if rows.len() < 8 {
                rows.push(id);
            }
        }
    }
    let search = Instant::now();
    let search_count = filter_channels(&playlist, None, "hd").len();
    let report = json!({"bytes":bytes,"channels":playlist.channels.len(),"categories":playlist.categories.len(),"skipped":playlist.skipped,"live":live,"on_demand":playlist.channels.len()-live,"with_tvg_id":ids,"with_headers":headers,"protocols":protocols,"formats":formats,"parse_ms":parse_ms,"search_ms":search.elapsed().as_secs_f64()*1000.0,"search_matches":search_count,"service_import":service_result,"sample_ids":sample,"total_ms":before.elapsed().as_secs_f64()*1000.0});
    std::fs::write(
        output,
        serde_json::to_vec_pretty(&report).map_err(|_| "encode_failed")?,
    )
    .map_err(|_| "write_failed")?;
    println!("{}", report);
    Ok(())
}
