//! Pure Xtream response mapping; Android owns transport and credential storage.
use crate::playlist::{Category, Channel, ContentKind, Playlist, StreamHeaders};
use serde_json::Value;
use std::collections::HashMap;
use url::Url;

pub fn parse(
    server: &str,
    username: &str,
    password: &str,
    account: &Value,
    live_categories: &Value,
    live: &Value,
    movies: Option<(&Value, &Value)>,
) -> Result<Playlist, String> {
    let mut base = Url::parse(server).map_err(|_| "invalid_server")?;
    if !matches!(base.scheme(), "http" | "https")
        || base.host_str().is_none()
        || base.query().is_some()
        || !base.username().is_empty()
        || base.password().is_some()
    {
        return Err("invalid_server".into());
    }
    if !base.path().ends_with('/') {
        base.set_path(&format!("{}/", base.path()));
    }
    let user = &account["user_info"];
    if !(user["auth"] == 1 || user["auth"] == "1" || user["auth"] == true) {
        return Err("xtream_auth_failed".into());
    }
    if user["status"]
        .as_str()
        .is_some_and(|s| !s.eq_ignore_ascii_case("active"))
    {
        return Err("xtream_account_inactive".into());
    }
    let live_ext = if user["allowed_output_formats"]
        .as_array()
        .is_some_and(|a| !a.iter().any(|v| v == "ts") && a.iter().any(|v| v == "m3u8"))
    {
        "m3u8"
    } else {
        "ts"
    };
    let mut playlist = Playlist::default();
    let mut group_ids = HashMap::new();
    let mut add = |categories: &Value, streams: &Value, kind: &str| -> Result<(), String> {
        let names: HashMap<_, _> = categories
            .as_array()
            .ok_or("invalid_categories")?
            .iter()
            .filter_map(|v| {
                Some((
                    id(&v["category_id"])?,
                    v["category_name"].as_str()?.to_owned(),
                ))
            })
            .collect();
        for stream in streams.as_array().ok_or("invalid_streams")? {
            let Some(stream_id) = id(&stream["stream_id"])
                .filter(|s| s.bytes().all(|b| b.is_ascii_digit()) && !s.is_empty())
            else {
                playlist.skipped += 1;
                continue;
            };
            let extension = if kind == "live" {
                live_ext
            } else {
                stream["container_extension"].as_str().unwrap_or("mp4")
            };
            if extension.is_empty()
                || extension.len() > 12
                || !extension.bytes().all(|b| b.is_ascii_alphanumeric())
            {
                playlist.skipped += 1;
                continue;
            }
            let content_kind = if kind == "live" {
                ContentKind::LiveTv
            } else {
                ContentKind::Movie
            };
            let group = id(&stream["category_id"])
                .and_then(|i| names.get(&i))
                .map(String::as_str)
                .unwrap_or("Ohne Kategorie")
                .to_owned();
            let category = *group_ids
                .entry((content_kind, group.clone()))
                .or_insert_with(|| {
                    let i = playlist.categories.len();
                    playlist.categories.push(Category {
                        name: group.clone(),
                        kind: content_kind,
                        channels: vec![],
                    });
                    i
                });
            let mut url = base.clone();
            url.path_segments_mut()
                .map_err(|_| "invalid_server")?
                .pop_if_empty()
                .push(kind)
                .push(username)
                .push(password)
                .push(&format!("{stream_id}.{extension}"));
            let name = stream["name"].as_str().unwrap_or("Stream").to_owned();
            let tvg_id = stream["epg_channel_id"].as_str().unwrap_or("").to_owned();
            playlist.categories[category]
                .channels
                .push(playlist.channels.len());
            playlist.channels.push(
                Channel {
                    search_key: format!("{name} {group} {tvg_id}").to_lowercase(),
                    name,
                    kind: content_kind,
                    category,
                    url: url.to_string(),
                    tvg_id,
                    logo: artwork(stream),
                    headers: StreamHeaders::default(),
                    is_live: kind == "live",
                    series_id: None,
                    ..Default::default()
                }
                .with_group(&group),
            );
        }
        Ok(())
    };
    add(live_categories, live, "live")?;
    if let Some((categories, streams)) = movies {
        add(categories, streams, "movie")?;
    }
    Ok(playlist)
}
fn id(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}
fn artwork(stream: &Value) -> Option<String> {
    ["cover", "stream_icon", "cover_big"]
        .into_iter()
        .find_map(|key| {
            stream[key]
                .as_str()
                .filter(|value| !value.trim().is_empty())
        })
        .map(str::to_owned)
}
pub fn value_id(value: &Value) -> Option<String> {
    id(value)
}

/// Each provider array is consumed independently, one object at a time.
pub fn build_manifest(
    path: &str,
    writer: &mut crate::catalog::Writer,
) -> Result<crate::playlist::ParseSummary, String> {
    use std::{fs::File, io::BufReader};
    let file = File::open(path).map_err(|e| crate::catalog::io_code(&e))?;
    if file
        .metadata()
        .map_err(|e| crate::catalog::io_code(&e))?
        .len()
        > 65536
    {
        return Err("invalid_xtream".into());
    }
    let manifest: Value = serde_json::from_reader(file).map_err(|_| "invalid_xtream")?;
    let server = manifest["server"].as_str().ok_or("invalid_server")?;
    let mut base = Url::parse(server).map_err(|_| "invalid_server")?;
    if !matches!(base.scheme(), "http" | "https")
        || base.host_str().is_none()
        || base.query().is_some()
        || base.fragment().is_some()
        || !base.username().is_empty()
        || base.password().is_some()
    {
        return Err("invalid_server".into());
    }
    if !base.path().ends_with('/') {
        base.set_path(&format!("{}/", base.path()));
    }
    let username = manifest["username"].as_str().ok_or("missing_username")?;
    let password = manifest["password"].as_str().ok_or("missing_password")?;
    let account = &manifest["account"]["user_info"];
    if !(account["auth"] == 1 || account["auth"] == "1" || account["auth"] == true) {
        return Err("xtream_auth_failed".into());
    }
    if account["status"]
        .as_str()
        .is_some_and(|s| !s.eq_ignore_ascii_case("active"))
    {
        return Err("xtream_account_inactive".into());
    }
    let live_ext = if account["allowed_output_formats"]
        .as_array()
        .is_some_and(|a| !a.iter().any(|v| v == "ts") && a.iter().any(|v| v == "m3u8"))
    {
        "m3u8"
    } else {
        "ts"
    };
    let mut summary = crate::playlist::ParseSummary {
        count: 0,
        skipped: 0,
    };
    for (field, route, kind) in [
        ("live", "live", ContentKind::LiveTv),
        ("movies", "movie", ContentKind::Movie),
        ("series", "series", ContentKind::Series),
    ] {
        let Some(stream_path) = manifest[field].as_str() else {
            continue;
        };
        let category_path = manifest[format!("{field}_categories")]
            .as_str()
            .ok_or("invalid_categories")?;
        let categories_file = File::open(category_path).map_err(|e| crate::catalog::io_code(&e))?;
        if categories_file
            .metadata()
            .map_err(|e| crate::catalog::io_code(&e))?
            .len()
            > 8 * 1024 * 1024
        {
            return Err("categories_too_large".into());
        }
        let categories: Value = serde_json::from_reader(BufReader::new(categories_file))
            .map_err(|_| "invalid_categories")?;
        let names: HashMap<String, String> = categories
            .as_array()
            .ok_or("invalid_categories")?
            .iter()
            .filter_map(|c| Some((id(&c["category_id"])?, c["category_name"].as_str()?.into())))
            .collect();
        stream_array(stream_path, |stream| {
            let Some(stream_id) = id(&stream[if kind == ContentKind::Series {
                "series_id"
            } else {
                "stream_id"
            }])
            .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())) else {
                summary.skipped += 1;
                return Ok(());
            };
            let ext = if kind == ContentKind::LiveTv {
                live_ext
            } else {
                stream["container_extension"].as_str().unwrap_or("mp4")
            };
            if ext.is_empty() || ext.len() > 12 || !ext.bytes().all(|b| b.is_ascii_alphanumeric()) {
                summary.skipped += 1;
                return Ok(());
            }
            let group = id(&stream["category_id"])
                .and_then(|id| names.get(&id))
                .cloned()
                .unwrap_or_else(|| "Ohne Kategorie".into());
            let mut url = base.clone();
            url.path_segments_mut()
                .map_err(|_| "invalid_server")?
                .pop_if_empty()
                .push(route)
                .push(username)
                .push(password)
                .push(&if kind == ContentKind::Series {
                    stream_id.clone()
                } else {
                    format!("{stream_id}.{ext}")
                });
            let name = stream["name"].as_str().unwrap_or("Stream").to_owned();
            let tvg_id = stream["epg_channel_id"].as_str().unwrap_or("").to_owned();
            writer.add(
                Channel {
                    search_key: format!("{name} {group} {tvg_id}").to_lowercase(),
                    name,
                    kind,
                    category: 0,
                    url: url.to_string(),
                    tvg_id,
                    logo: artwork(&stream),
                    headers: StreamHeaders::default(),
                    is_live: kind == ContentKind::LiveTv,
                    series_id: (kind == ContentKind::Series).then_some(stream_id),
                    ..Default::default()
                }
                .with_group(&group),
                group,
            )?;
            summary.count += 1;
            Ok(())
        })?;
    }
    Ok(summary)
}
fn stream_array(
    path: &str,
    mut apply: impl FnMut(Value) -> Result<(), String>,
) -> Result<(), String> {
    use serde::de::{DeserializeSeed, Error, SeqAccess, Visitor};
    use std::{cell::Cell, io::Read, rc::Rc};
    struct RowBound<R> {
        reader: R,
        bytes: Rc<Cell<usize>>,
    }
    impl<R: Read> Read for RowBound<R> {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.bytes.get() > 1024 * 1024 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "xtream_entry_too_large",
                ));
            }
            let n = self.reader.read(buf)?;
            self.bytes.set(self.bytes.get() + n);
            Ok(n)
        }
    }
    struct Array<'a, F>(&'a mut F);
    impl<'de, F: FnMut(Value) -> Result<(), String>> Visitor<'de> for Array<'_, F> {
        type Value = ();
        fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
            f.write_str("provider array")
        }
        fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
            while let Some(v) = seq.next_element::<Value>()? {
                (self.0)(v).map_err(A::Error::custom)?;
            }
            Ok(())
        }
    }
    impl<'de, F: FnMut(Value) -> Result<(), String>> DeserializeSeed<'de> for Array<'_, F> {
        type Value = ();
        fn deserialize<D: serde::Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
            d.deserialize_seq(self)
        }
    }
    let file = std::fs::File::open(path).map_err(|e| crate::catalog::io_code(&e))?;
    if file
        .metadata()
        .map_err(|e| crate::catalog::io_code(&e))?
        .len()
        > crate::catalog::BYTE_LIMIT
    {
        return Err("import_too_large".into());
    }
    let mut error = None;
    let bytes = Rc::new(Cell::new(0));
    let mut callback = |v| {
        bytes.set(0);
        apply(v).inspect_err(|e| {
            error = Some(e.clone());
        })
    };
    let mut deserializer = serde_json::Deserializer::from_reader(RowBound {
        reader: std::io::BufReader::with_capacity(256 * 1024, file),
        bytes: bytes.clone(),
    });
    let parsed = Array(&mut callback)
        .deserialize(&mut deserializer)
        .and_then(|_| deserializer.end());
    parsed.map_err(|e| {
        error.unwrap_or_else(|| {
            if bytes.get() > 1024 * 1024 {
                return "xtream_entry_too_large".into();
            }
            if e.is_io() {
                "import_read_failed".into()
            } else {
                "invalid_streams".into()
            }
        })
    })
}
