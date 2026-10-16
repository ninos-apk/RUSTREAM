use iptv_core::epg::Guide;
use std::io::Cursor;

#[test]
fn xmltv_timezone_entities_cdata_and_now_next_are_preserved() {
    let xml = r#"<tv><programme channel="a" start="20261015180000 +0200" stop="20261015190000 +0200"><title>News &amp; Sport &#x26; TV</title><desc><![CDATA[A < B]]></desc></programme><programme channel="a" start="20261015170000 +0000" stop="20261015180000 +0000"><title>Next</title></programme></tv>"#;
    let guide = Guide::parse(Cursor::new(xml)).unwrap();
    let rows = guide.now_next("a", guide.0["a"][0].start + 20);
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].title, "News & Sport & TV");
    assert_eq!(rows[0].description, "A < B");
    assert_eq!(rows[1].start, rows[0].stop);
    assert!(guide.now_next("unknown", 0).is_empty());
}
#[test]
fn truncated_xml_bad_times_and_external_entities_are_rejected() {
    for xml in [
        "",
        "<tv>",
        "<html/>",
        "<!DOCTYPE tv SYSTEM 'file:///private'><tv/>",
        r#"<tv><programme channel="a" start="invalid" stop="invalid"/></tv>"#,
        r#"<tv><programme channel="a" start="20261015190000 +0200" stop="20261015180000 +0200"><title>Invalid</title></programme></tv>"#,
    ] {
        assert!(Guide::parse(Cursor::new(xml)).is_err(), "accepted {xml}");
    }
}
