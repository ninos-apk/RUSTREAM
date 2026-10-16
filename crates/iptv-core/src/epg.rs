//! XMLTV import. External entities/DTDs are never resolved; input and record counts are bounded.
use quick_xml::{Reader, events::Event};
use serde::Serialize;
use std::{collections::HashMap, io::BufRead};
use time::{OffsetDateTime, macros::format_description};

#[derive(Clone, Debug, Serialize)]
pub struct Programme {
    pub start: i64,
    pub stop: i64,
    pub title: String,
    pub description: String,
}
#[derive(Default)]
pub struct Guide(pub HashMap<String, Vec<Programme>>);

fn timestamp(value: &str) -> Result<i64, String> {
    let format = format_description!(
        "[year][month][day][hour][minute][second] [offset_hour sign:mandatory][offset_minute]"
    );
    OffsetDateTime::parse(value, format)
        .map(|t| t.unix_timestamp())
        .map_err(|_| "invalid_epg_time".into())
}

impl Guide {
    pub fn parse(input: impl BufRead) -> Result<Self, String> {
        let mut reader = Reader::from_reader(input);
        reader.config_mut().trim_text(false);
        reader.config_mut().expand_empty_elements = true;
        let mut guide = Self::default();
        let mut buffer = Vec::new();
        let mut current: Option<(String, Programme)> = None;
        let mut field = 0;
        let mut records = 0;
        let mut depth = 0usize;
        let mut root_seen = false;
        loop {
            match reader
                .read_event_into(&mut buffer)
                .map_err(|_| "invalid_epg")?
            {
                Event::DocType(_) => return Err("epg_doctype_not_supported".into()),
                Event::Start(start) if start.name().as_ref() == b"programme" => {
                    depth += 1;
                    if current.is_some() {
                        return Err("invalid_epg".into());
                    }
                    let mut attrs = HashMap::new();
                    for attr in start.attributes() {
                        let attr = attr.map_err(|_| "invalid_epg")?;
                        attrs.insert(
                            attr.key.as_ref().to_vec(),
                            attr.unescape_value()
                                .map_err(|_| "invalid_epg")?
                                .into_owned(),
                        );
                    }
                    let channel = attrs
                        .get(b"channel".as_slice())
                        .ok_or("missing_epg_channel")?
                        .clone();
                    let start =
                        timestamp(attrs.get(b"start".as_slice()).ok_or("missing_epg_time")?)?;
                    let stop = timestamp(attrs.get(b"stop".as_slice()).ok_or("missing_epg_time")?)?;
                    if stop <= start {
                        return Err("invalid_epg_interval".into());
                    }
                    current = Some((
                        channel,
                        Programme {
                            start,
                            stop,
                            title: String::new(),
                            description: String::new(),
                        },
                    ));
                }
                Event::Start(start) => {
                    if depth == 0 {
                        if root_seen || start.name().as_ref() != b"tv" {
                            return Err("invalid_epg".into());
                        }
                        root_seen = true;
                    }
                    depth += 1;
                    field = match start.name().as_ref() {
                        b"title" => 1,
                        b"desc" => 2,
                        _ => 0,
                    };
                }
                Event::Text(text) => {
                    if let Some((_, programme)) = current.as_mut() {
                        let decoded = text.decode().map_err(|_| "invalid_epg_text")?;
                        let value = quick_xml::escape::unescape(&decoded)
                            .map_err(|_| "invalid_epg_text")?;
                        let target = match field {
                            1 => &mut programme.title,
                            2 => &mut programme.description,
                            _ => {
                                buffer.clear();
                                continue;
                            }
                        };
                        if target.len() + value.len() > 16_384 {
                            return Err("epg_text_too_large".into());
                        }
                        target.push_str(&value);
                    }
                }
                Event::GeneralRef(reference) => {
                    if let Some((_, programme)) = current.as_mut() {
                        let value = if let Some(c) = reference
                            .resolve_char_ref()
                            .map_err(|_| "invalid_epg_text")?
                        {
                            c.to_string()
                        } else {
                            match reference.as_ref() {
                                b"amp" => "&",
                                b"lt" => "<",
                                b"gt" => ">",
                                b"quot" => "\"",
                                b"apos" => "'",
                                _ => return Err("invalid_epg_entity".into()),
                            }
                            .to_owned()
                        };
                        let target = match field {
                            1 => &mut programme.title,
                            2 => &mut programme.description,
                            _ => {
                                buffer.clear();
                                continue;
                            }
                        };
                        if target.len() + value.len() > 16_384 {
                            return Err("epg_text_too_large".into());
                        }
                        target.push_str(&value);
                    }
                }
                Event::CData(text) => {
                    if let Some((_, programme)) = current.as_mut() {
                        let value = text.decode().map_err(|_| "invalid_epg_text")?;
                        let target = match field {
                            1 => &mut programme.title,
                            2 => &mut programme.description,
                            _ => {
                                buffer.clear();
                                continue;
                            }
                        };
                        if target.len() + value.len() > 16_384 {
                            return Err("epg_text_too_large".into());
                        }
                        target.push_str(&value);
                    }
                }
                Event::End(end) if end.name().as_ref() == b"programme" => {
                    depth = depth.checked_sub(1).ok_or("invalid_epg")?;
                    let (channel, programme) = current.take().ok_or("invalid_epg")?;
                    guide.0.entry(channel).or_default().push(programme);
                    records += 1;
                    if records > 250_000 {
                        return Err("epg_too_large".into());
                    }
                }
                Event::End(_) => {
                    depth = depth.checked_sub(1).ok_or("invalid_epg")?;
                    field = 0;
                }
                Event::Eof => break,
                _ => {}
            }
            buffer.clear();
        }
        if current.is_some() || depth != 0 || !root_seen {
            return Err("invalid_epg".into());
        }
        for programmes in guide.0.values_mut() {
            programmes.sort_by_key(|p| p.start);
        }
        Ok(guide)
    }

    pub fn now_next(&self, id: &str, now: i64) -> Vec<&Programme> {
        self.0
            .get(id)
            .map(|rows| rows.iter().filter(|p| p.stop > now).take(2).collect())
            .unwrap_or_default()
    }
}
