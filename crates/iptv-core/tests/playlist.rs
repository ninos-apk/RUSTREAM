use iptv_core::playlist::{ContentKind, filter_channels, filter_content, parse_reader};
use std::io::Cursor;

#[test]
fn categories_preserve_order_unicode_and_commas_in_attributes() {
    let input = "\u{feff}#EXTM3U\r\n#EXTINF:-1 tvg-id=\"a\" tvg-name=\"Fallback\" group-title=\"Sport, International\",Fußball ⚽\r\nhttps://example.test/live/1.ts\r\n#EXTINF:-1 group-title=\"أخبار\",أخبار\nhttps://example.test/live/2.ts\n#EXTINF:-1 group-title=\"Sport, International\",News, Extra\nhttps://example.test/live/3.ts\n";
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.categories.len(), 2);
    assert_eq!(playlist.categories[0].name, "Sport, International");
    assert_eq!(playlist.categories[0].channels, vec![0, 2]);
    assert_eq!(playlist.channels[0].name, "Fußball ⚽");
    assert_eq!(playlist.channels[1].name, "أخبار");
    assert_eq!(playlist.channels[2].name, "News, Extra");
}

#[test]
fn supports_unquoted_attributes_extgrp_fallback_and_headers() {
    let input = "#EXTM3U\n#EXTVLCOPT:http-user-agent=Default\n#EXTINF:-1 tvg-name='Fallback' group-title = Unsorted,\n#EXTGRP:Movies\n#EXTVLCOPT:http-referrer=https://example.test/page\nhttps://example.test/movie/1.mp4|User-Agent=Custom%20Agent\n#EXTINF:-1,Next\nhttps://example.test/2.ts\n";
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.channels[0].name, "Fallback");
    assert_eq!(
        playlist.channels[0].headers.user_agent.as_deref(),
        Some("Custom Agent")
    );
    assert_eq!(
        playlist.channels[0].headers.referrer.as_deref(),
        Some("https://example.test/page")
    );
    assert!(!playlist.channels[0].is_live);
    assert_eq!(
        playlist.channels[1].headers.user_agent.as_deref(),
        Some("Default")
    );
    assert_eq!(playlist.channels[1].headers.referrer, None);
}

#[test]
fn malicious_directives_protocols_and_header_injection_are_not_executed() {
    let input = "#EXTINF:-1,Unsafe\nfile:///C:/private.txt\n#EXTINF:-1,Unsafe\nav://lavfi:testsrc\n#EXTINF:-1,Safe\n#EXTVLCOPT:script=C:/evil.lua\nhttps://example.test/ok.ts|User-Agent=bad%0D%0AInjected%3Ayes\n";
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.channels.len(), 1);
    assert_eq!(playlist.skipped, 2);
    assert_eq!(playlist.channels[0].headers.user_agent, None);
}

#[test]
fn malformed_lines_do_not_poison_the_next_entry() {
    let input = "#EXTM3U\n#EXTINF:-1,Missing URL\n#EXTINF:-1,Valid\nhttps://example.test/a\n#EXTINF:-1,Dangling";
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.channels.len(), 1);
    assert_eq!(playlist.channels[0].name, "Valid");
    assert_eq!(playlist.skipped, 2);
}

#[test]
fn oversized_lines_are_bounded_and_parser_recovers() {
    let input = format!(
        "{}\n#EXTINF:-1,Good\nhttps://example.test/a\n",
        "X".repeat(1024 * 1024 + 1)
    );
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.channels.len(), 1);
    assert_eq!(playlist.skipped, 1);
}

#[test]
fn filtering_is_case_insensitive_and_intersects_query_terms_and_category() {
    let input = "#EXTINF:-1 group-title=\"Sports\",BeIN HD\nhttps://example.test/a\n#EXTINF:-1 group-title=\"News\",World HD\nhttps://example.test/b\n";
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(filter_channels(&playlist, None, "bein hd"), vec![0]);
    assert_eq!(filter_channels(&playlist, Some(1), "hd"), vec![1]);
    assert_eq!(filter_channels(&playlist, None, "SPORTS"), vec![0]);
    assert!(filter_channels(&playlist, None, "missing").is_empty());
}

#[test]
fn debug_output_redacts_provider_credentials() {
    let playlist = parse_reader(Cursor::new(
        "#EXTINF:-1,Safe\nhttps://example.test/username/secret/1.ts\n",
    ))
    .unwrap();
    assert!(!format!("{:?}", playlist.channels[0]).contains("secret"));
}

#[test]
fn scales_to_large_lists_without_losing_entries() {
    let mut input = String::with_capacity(8_000_000);
    for index in 0..100_000 {
        use std::fmt::Write;
        writeln!(
            input,
            "#EXTINF:-1 group-title=\"Group {}\",Channel {index}\nhttps://example.test/{index}.ts",
            index % 100
        )
        .unwrap();
    }
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.channels.len(), 100_000);
    assert_eq!(playlist.categories.len(), 100);
    assert!(
        playlist
            .categories
            .iter()
            .all(|group| group.channels.len() == 1000)
    );
}

#[test]
fn content_detection_uses_metadata_routes_groups_and_episode_names_in_order() {
    use ContentKind::{LiveTv, Movie, Series};
    for (metadata, title, address, expected) in [
        (
            "content-type=series",
            "Episode",
            "https://example.test/movie/u/p/1.mp4",
            Series,
        ),
        (
            "tvg-type=movie",
            "Title S01E02",
            "https://example.test/series/1.mkv",
            Movie,
        ),
        (
            "stream-type=live",
            "Station",
            "https://example.test/1.mp4",
            LiveTv,
        ),
        ("type=SERIES", "Episode", "https://example.test/1", Series),
        (
            "group-title=Serien",
            "Film",
            "https://example.test/movie/u/p/1.mkv",
            Movie,
        ),
        (
            "group-title=Movies",
            "Episode",
            "https://example.test/base/series/u/p/1.ts",
            Series,
        ),
        (
            "group-title=Movies",
            "Station S01E01",
            "https://example.test/LIVE/u/p/1.mp4",
            LiveTv,
        ),
        ("", "Episode", "https://example.test/series/1", Series),
        (
            "",
            "Film",
            "https://example.test/stream?stream_type=movie",
            Movie,
        ),
        (
            "group-title='DE | SERIEN'",
            "Episode",
            "https://example.test/1.mp4",
            Series,
        ),
        (
            "group-title='EN - TV Shows'",
            "Episode",
            "https://example.test/1",
            Series,
        ),
        (
            "group-title='DE: Filme HD'",
            "Film",
            "https://example.test/1.ts",
            Movie,
        ),
        (
            "group-title='FR VOD'",
            "Film",
            "https://example.test/1",
            Movie,
        ),
        (
            "group-title='Live TV'",
            "Station S01E02",
            "https://example.test/1.mp4",
            LiveTv,
        ),
        (
            "group-title=Drama",
            "Show S02E012 HD",
            "https://example.test/1.mkv",
            Series,
        ),
        (
            "group-title=Drama",
            "Show 2x03",
            "https://example.test/1",
            Series,
        ),
        (
            "group-title=Drama",
            "Film",
            "https://example.test/1.MP4?token=test",
            Movie,
        ),
        (
            "group-title=Sports",
            "Station",
            "https://example.test/live-stream.m3u8",
            LiveTv,
        ),
        (
            "group-title='MovieStar News'",
            "Live Station",
            "https://example.test/1.ts",
            LiveTv,
        ),
        ("", "Resolution 1920x1080", "https://example.test/1", LiveTv),
        ("", "AS01E01B", "https://example.test/1", LiveTv),
        (
            "",
            "Station",
            "https://example.test/path/movie/password/1.ts",
            LiveTv,
        ),
    ] {
        let input = format!("#EXTINF:-1 {metadata},{title}\n{address}\n");
        let playlist = parse_reader(Cursor::new(input)).unwrap();
        assert_eq!(playlist.channels[0].kind, expected, "{metadata}, {title}");
        assert_eq!(playlist.channels[0].is_live, expected == LiveTv);
        assert_eq!(playlist.categories[0].kind, expected);
    }
    let playlist = parse_reader(Cursor::new(
        "#EXTINF:7200,Film\nhttps://example.test/1\n#EXTINF:-1,Episode\n#EXTGRP:DE | Serien\nhttps://example.test/2\n",
    )).unwrap();
    assert_eq!(playlist.channels[0].kind, Movie);
    assert_eq!(playlist.channels[1].kind, Series);
}

#[test]
fn same_category_names_are_separate_under_each_content_kind_and_search_is_scoped() {
    let input = "#EXTINF:-1 group-title=Drama,Live HD\nhttps://example.test/live/u/p/1.ts\n\
        #EXTINF:-1 group-title=Drama,Movie HD\nhttps://example.test/movie/u/p/2.mp4\n\
        #EXTINF:-1 group-title=Drama,Series S01E01\nhttps://example.test/series/u/p/3.mkv\n\
        #EXTINF:-1 group-title=Drama,Movie SD\nhttps://example.test/movie/u/p/4.mp4\n\
        #EXTINF:-1,Series S01E02\nhttps://example.test/series/u/p/5.mkv\n";
    let playlist = parse_reader(Cursor::new(input)).unwrap();
    assert_eq!(playlist.categories.len(), 4);
    assert_eq!(playlist.categories[0].channels, vec![0]);
    assert_eq!(playlist.categories[1].channels, vec![1, 3]);
    assert_eq!(playlist.categories[2].channels, vec![2]);
    assert_eq!(playlist.categories[3].name, "Ohne Kategorie");
    assert_eq!(playlist.count(ContentKind::LiveTv), 1);
    assert_eq!(playlist.count(ContentKind::Movie), 2);
    assert_eq!(playlist.count(ContentKind::Series), 2);
    assert_eq!(
        filter_content(&playlist, Some(ContentKind::Movie), None, "drama hd"),
        vec![1]
    );
    assert_eq!(
        filter_content(&playlist, Some(ContentKind::Series), None, ""),
        vec![2, 4]
    );
    assert_eq!(
        filter_content(&playlist, Some(ContentKind::Series), Some(2), ""),
        vec![2]
    );
    assert!(filter_content(&playlist, Some(ContentKind::Series), Some(1), "").is_empty());
    assert_eq!(filter_channels(&playlist, None, "HD"), vec![0, 1]);
}
