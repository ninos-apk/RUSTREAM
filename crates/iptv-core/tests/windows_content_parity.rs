use iptv_core::playlist::{self, ContentKind, parse_episode, parse_reader};
use std::io::Cursor;

#[test]
fn actual_samples_keep_unicode_metadata_versions_and_episode_urls() {
    let series_title = "Zamanin Kapilari - أبواب القدر [SUB]";
    let category = "★ TR Series [تركي يعرض الان]";
    let poster = "https://image.tmdb.org/t/p/original/qVKJG12gVTiauhlNmirj0Mg4Hej.jpg";
    let mut source = String::from("#EXTM3U\n");
    for (season, episode) in [(1, 10), (1, 5), (2, 2), (1, 2)] {
        source.push_str(&format!("#EXTINF:-1 tvg-id=\"\" tvg-name=\"{series_title} S{season:02} E{episode:02}\" tvg-logo=\"{poster}\" group-title=\"{category}\",{series_title} S{season:02} E{episode:02}\nhttps://example.test/series/u/p/{season}{episode}.mkv\n"));
    }
    for (quality, id) in [("4K", 737), ("HD", 724)] {
        source.push_str(&format!("#EXTINF:-1 tvg-name=\"Sisu: Road to Revenge [2025-{quality}]\" tvg-logo=\"https://example.test/poster.webp\" group-title=\"★ En Movies 2025\",Sisu: Road to Revenge [2025-{quality}]\nhttps://example.test/movie/u/p/{id}.mp4\n"));
    }
    source.push_str("#EXTINF:-1,Missing episode S01 E10\n");
    let p = parse_reader(Cursor::new(source)).unwrap();
    assert_eq!(p.skipped, 1);
    assert_eq!(p.channels.len(), 6);
    assert_eq!(p.series.len(), 1);
    let show = &p.series[0];
    assert_eq!(show.title, series_title);
    assert_eq!(show.logo.as_deref(), Some(poster));
    assert_eq!(p.categories[show.categories[0]].name, category);
    assert_eq!(
        show.seasons.iter().map(|s| s.number).collect::<Vec<_>>(),
        [Some(1), Some(2)]
    );
    let episodes = &show.seasons[0].episodes;
    assert_eq!(
        episodes
            .iter()
            .map(|i| p.channels[*i].episode.as_ref().unwrap().episode)
            .collect::<Vec<_>>(),
        [2, 5, 10]
    );
    assert!(p.channels[episodes[0]].url.ends_with("/12.mkv"));
    assert!(p.channels[episodes[2]].url.ends_with("/110.mkv"));
    assert_eq!(p.channels[0].tvg_id, "");
    assert_eq!(p.channels[0].attributes.get("tvg-id").unwrap(), "");
    assert_eq!(p.channels[0].extension.as_deref(), Some("mkv"));
    assert_eq!(p.channels[4].year, Some(2025));
    assert_eq!(p.channels[4].quality.as_deref(), Some("4K"));
    assert_eq!(p.channels[5].quality.as_deref(), Some("HD"));
    assert_ne!(p.channels[4].url, p.channels[5].url);
}

#[test]
fn display_and_tvg_names_are_independent_and_quoted_punctuation_is_preserved() {
    let p = parse_reader(Cursor::new("#EXTINF:-1 tvg-id=\"\" tvg-name=\"أبواب, القدر [SUB] S1E5\" group-title=\"★ عربي, تركي\" tvg-logo='https://example.test/a.webp',Display: Episode 5 [HD]\nhttps://example.test/series/u/p/5.mkv?token=abc\n")).unwrap();
    let c = &p.channels[0];
    assert_eq!(c.display_name, "Display: Episode 5 [HD]");
    assert_eq!(c.tvg_name, "أبواب, القدر [SUB] S1E5");
    assert_eq!(c.group_title, "★ عربي, تركي");
    assert_eq!(c.url_path, "/series/u/p/5.mkv");
    assert!(c.url.ends_with("?token=abc"));
    assert_eq!(p.series[0].title, "أبواب, القدر [SUB]");
    assert_eq!(playlist::filter_channels(&p, None, "أبواب"), [0]);
}

#[test]
fn episode_patterns_sort_numerically_and_keep_meaningful_title_labels() {
    for suffix in ["S01 E05", "S01E05", "S1 E5", "Season 1 Episode 5", "1x05"] {
        let parsed = parse_episode(&format!("أبواب القدر [SUB] {suffix}")).unwrap();
        assert_eq!(parsed.series_title, "أبواب القدر [SUB]");
        assert_eq!((parsed.season, parsed.episode), (1, 5));
    }
    for invalid in [
        "Resolution 1920x1080",
        "Resolution 640x360",
        "AS01E01B",
        "Arabic أبواب Sxx E05",
        "S01 E05",
    ] {
        assert!(parse_episode(invalid).is_none(), "{invalid}");
    }
    assert_eq!(
        parse_episode("أبواب القدر S1E5 [SUB]")
            .unwrap()
            .series_title,
        "أبواب القدر [SUB]"
    );
    let p = parse_reader(Cursor::new("#EXTINF:-1,Unknown episode عنوان\nhttps://example.test/series/u/p/1.mkv\n#EXTINF:-1,Other show S1 E2\nhttps://example.test/series/u/p/2.mkv\n")).unwrap();
    assert_eq!(p.series.len(), 2);
    assert_eq!(p.series[0].seasons[0].number, None);
    assert_eq!(p.series[0].seasons[0].episodes, [0]);
}

#[test]
fn routes_win_conflicts_and_extensions_alone_are_not_movies_or_live() {
    for (metadata, path, expected) in [
        (
            "content-type=live group-title=News",
            "/movie/u/p/1.mkv",
            ContentKind::Movie,
        ),
        (
            "content-type=movie group-title=Movies",
            "/series/u/p/1.mp4",
            ContentKind::Series,
        ),
        ("group-title=Movies", "/live/u/p/1.mp4", ContentKind::LiveTv),
        ("", "/user/password/12345", ContentKind::LiveTv),
        (
            "group-title=Movies",
            "/user/password/12345",
            ContentKind::LiveTv,
        ),
        ("", "/ambiguous.mkv", ContentKind::Unclassified),
        ("", "/ambiguous.mp4", ContentKind::Unclassified),
        ("", "/unknown", ContentKind::Unclassified),
        ("group-title=أفلام", "/unknown", ContentKind::Movie),
        ("group-title=مسلسلات", "/unknown", ContentKind::Series),
    ] {
        let p = parse_reader(Cursor::new(format!(
            "#EXTINF:-1 {metadata},Title\nhttps://example.test{path}\n"
        )))
        .unwrap();
        assert_eq!(p.channels[0].kind, expected);
    }
}

#[test]
fn escaped_quotes_and_absolute_urls_keep_their_original_values() {
    let source = r#"#EXTINF:-1 tvg-name="قال \"مرحبا\", العالم" group-title="أفلام, عربية",Display, Title
https://EXAMPLE.test/movie/u/p/1.mkv?token=Case%2fSensitive
"#;
    let base = url::Url::parse("https://example.test/list.m3u").unwrap();
    let p = playlist::parse_reader_with_base(Cursor::new(source), Some(&base)).unwrap();
    assert_eq!(p.channels[0].tvg_name, "قال \"مرحبا\", العالم");
    assert_eq!(p.channels[0].display_name, "Display, Title");
    assert_eq!(p.channels[0].group_title, "أفلام, عربية");
    assert_eq!(
        p.channels[0].url,
        "https://EXAMPLE.test/movie/u/p/1.mkv?token=Case%2fSensitive"
    );
}
