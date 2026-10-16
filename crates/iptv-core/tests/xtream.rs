use iptv_core::xtream;
use serde_json::json;

#[test]
fn xtream_auth_headers_categories_and_encoded_credentials() {
    let account =
        json!({"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8"]}});
    let categories = json!([{"category_id":"5","category_name":"Sport"}]);
    let streams = json!([{"stream_id":15,"category_id":5,"name":"Match","epg_channel_id":"sport"},{"stream_id":16,"name":"Film","container_extension":"mp4"}]);
    let playlist = xtream::parse(
        "https://example.test/prefix",
        "u/name",
        "p word",
        &account,
        &categories,
        &streams,
        Some((&categories, &streams)),
    )
    .unwrap();
    assert_eq!(playlist.channels.len(), 4);
    assert!(
        playlist.channels[0]
            .url
            .contains("/prefix/live/u%2Fname/p%20word/15.m3u8")
    );
    assert!(!playlist.channels[2].is_live);
    assert_eq!(playlist.categories[0].name, "Sport");
    assert_eq!(
        playlist.channels[0].kind,
        iptv_core::playlist::ContentKind::LiveTv
    );
    assert_eq!(
        playlist.channels[2].kind,
        iptv_core::playlist::ContentKind::Movie
    );
    assert_ne!(playlist.channels[0].category, playlist.channels[2].category);
    assert!(!format!("{:?}", playlist.channels[0]).contains("p%20word"));
}
#[test]
fn denied_accounts_and_malformed_protocols_are_rejected() {
    assert!(
        xtream::parse(
            "https://example.test",
            "u",
            "p",
            &json!({"user_info":{"auth":0}}),
            &json!([]),
            &json!([]),
            None
        )
        .is_err()
    );
    assert!(
        xtream::parse(
            "file:///private",
            "u",
            "p",
            &json!({"user_info":{"auth":1}}),
            &json!([]),
            &json!([]),
            None
        )
        .is_err()
    );
}
