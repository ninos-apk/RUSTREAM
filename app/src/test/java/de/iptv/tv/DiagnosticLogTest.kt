package de.iptv.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagnosticLogTest {
    @Test fun boundsHistoryAndKeepsSequenceAcrossWallClockChanges() {
        var wall = 1_000L
        var nanos = 0L
        val log = DiagnosticLog(3, { wall }, { nanos })
        repeat(5) {
            wall -= 100
            nanos += 1_000_000
            log.record(DiagnosticLevel.WARN,"Recovery",DiagnosticMessage.RETRY_SCHEDULED,"attempt" to it)
        }
        val snapshot = log.snapshot()
        assertEquals(listOf(4L,5L,6L),snapshot.events.map { it.sequence })
        assertEquals(listOf(3L,4L,5L),snapshot.events.map { it.elapsedMs })
        assertEquals(3L,snapshot.dropped)
        assertEquals(6L,snapshot.version)
        assertEquals(3,snapshot.json().getJSONArray("events").length())
        assertEquals(Instant.ofEpochMilli(500).toString(),snapshot.events.last().json().getString("time"))
    }

    @Test fun retainsDebugCodesButRejectsCredentialsUrlsAndExceptionMessages() {
        val log = DiagnosticLog()
        log.record(DiagnosticLevel.ERROR,"Playback",DiagnosticMessage.PLAYBACK_FAILED,
            "code" to "network_timeout","media3_code" to 2002,"media3_name" to "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT",
            "http_status" to 503,"errno" to 28,"attempt" to 7L,"received_bytes" to 184320L,
            "password" to "secret","url" to "https://user:secret@example.test/stream",
            "exception_message" to "secret","reason" to "https://user:secret@example.test/stream",
            "total_ms" to Double.NaN)
        val details = log.snapshot().events.last().json().getJSONObject("details")
        assertEquals("network_timeout",details.getString("code"))
        assertEquals(2002,details.getInt("media3_code"))
        assertEquals(503,details.getInt("http_status"))
        assertEquals(28,details.getInt("errno"))
        assertEquals(184320L,details.getLong("received_bytes"))
        assertFalse(details.has("total_ms"))
        assertFalse(details.has("reason"))
        assertFalse(log.snapshot().json().toString().contains("secret"))
    }

    @Test fun concurrentImportsAndPlaybackProduceUniqueOrderedEventsAndImmutableSnapshots() {
        val log = DiagnosticLog(500)
        val initial = log.snapshot()
        val threads = List(8) { Thread { repeat(125) {
            log.record(DiagnosticLevel.INFO,"Import",DiagnosticMessage.IMPORT_COMPLETED,"count" to it)
        } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val snapshot = log.snapshot()
        assertEquals(1001L,snapshot.version)
        assertEquals(501L,snapshot.dropped)
        assertEquals((502L..1001L).toList(),snapshot.events.map { it.sequence })
        assertEquals(1,initial.events.size)
        assertEquals(1L,initial.version)
        assertEquals(initial.sessionId,snapshot.sessionId)
        assertNotEquals(snapshot.sessionId,DiagnosticLog().snapshot().sessionId)
    }

    @Test fun copiedContextIncludesBuildTimezoneAndExactTechnicalFields() {
        val wall = Instant.parse("2026-10-24T12:32:12.483Z").toEpochMilli()
        val log = DiagnosticLog(clock = { wall })
        log.record(DiagnosticLevel.ERROR,"Playback",DiagnosticMessage.PLAYBACK_FAILED,"attempt" to 7,"media3_code" to 2002)
        val snapshot = log.snapshot()
        val report = JSONObject().put("app_version","0.5.9").put("version_code",20).put("build_id","abc123")
            .put("device",JSONObject().put("manufacturer","Test").put("model","TV").put("android","16").put("sdk",36))
        val zone = ZoneId.of("Europe/Berlin")
        val header = DiagnosticText.header(snapshot,report,zone)
        assertTrue(header.contains("build=abc123"))
        assertTrue(header.contains("+02:00 [Europe/Berlin]"))
        assertTrue(header.contains("Android 16 / API 36"))
        assertEquals("10-24 14:32:12.483",DiagnosticText.time(snapshot.events.last(),zone))
        assertTrue(DiagnosticText.details(snapshot.events.last()).contains("attempt=7  media3_code=2002"))
    }
}
