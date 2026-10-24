package de.iptv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class DiagnosticLevel { INFO, WARN, ERROR }

/** Fixed messages and allowlisted fields: exception messages, URLs and provider names never enter the log. */
enum class DiagnosticMessage(val resource: Int) {
    SESSION_STARTED(R.string.log_session_started),
    IMPORT_STARTED(R.string.log_import_started),
    IMPORT_COMPLETED(R.string.log_import_completed),
    IMPORT_UNCHANGED(R.string.log_import_unchanged),
    IMPORT_CANCELLED(R.string.log_import_cancelled),
    IMPORT_WARNING(R.string.log_import_warning),
    IMPORT_FAILED(R.string.log_import_failed),
    CACHE_RESTORED(R.string.log_cache_restored),
    OPERATION_FAILED(R.string.log_operation_failed),
    PLAYBACK_STARTED(R.string.log_playback_started),
    PLAYBACK_STATE(R.string.log_playback_state),
    PLAYBACK_FAILED(R.string.log_playback_failed),
    RECOVERY(R.string.log_recovery),
    RECOVERED(R.string.log_recovered),
    RETRY_SCHEDULED(R.string.log_retry_scheduled),
    STORAGE_FAILED(R.string.log_storage_failed),
}

data class DiagnosticEvent(
    val sequence: Long, val timeMs: Long, val elapsedMs: Long,
    val level: DiagnosticLevel, val component: String, val message: DiagnosticMessage,
    val details: Map<String, Any>,
) {
    fun json() = JSONObject().put("sequence", sequence).put("time", Instant.ofEpochMilli(timeMs).toString())
        .put("time_ms", timeMs).put("elapsed_ms", elapsedMs).put("level", level.name)
        .put("component", component).put("event", message.name.lowercase()).put("details", JSONObject(details))
}

data class DiagnosticSnapshot(val sessionId: String, val startedMs: Long, val version: Long,
                              val dropped: Long, val events: List<DiagnosticEvent>) {
    fun json() = JSONObject().put("session_id", sessionId).put("started", Instant.ofEpochMilli(startedMs).toString())
        .put("scope", "current_process").put("dropped_events", dropped).put("events", JSONArray(events.map { it.json() }))
}

/** Process lifetime, thread safe and bounded. Never writes on the playback or import path. */
class DiagnosticLog(private val capacity: Int = 500,
                    private val clock: () -> Long = System::currentTimeMillis,
                    private val monotonic: () -> Long = System::nanoTime) {
    private val sessionId = UUID.randomUUID().toString()
    private val startedMs = clock()
    private val startedNanos = monotonic()
    private val events = ArrayDeque<DiagnosticEvent>()
    private var sequence = 0L
    init {
        require(capacity > 0)
        record(DiagnosticLevel.INFO, "App", DiagnosticMessage.SESSION_STARTED)
    }

    @Synchronized fun record(level: DiagnosticLevel, component: String, message: DiagnosticMessage,
                             vararg details: Pair<String, Any?>) {
        val safe = linkedMapOf<String, Any>()
        details.take(24).forEach { (key, value) ->
            if (key in numericFields && (value is Number || value is Boolean)) {
                if (value !is Number || value.toDouble().isFinite()) safe[key] = value
            } else if (key in codeFields && value is String && safeCode.matches(value)) safe[key] = value
        }
        events.addLast(DiagnosticEvent(++sequence, clock(), ((monotonic() - startedNanos) / 1_000_000).coerceAtLeast(0),
            level, component.takeIf { it in components } ?: "App", message, safe.toMap()))
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized fun snapshot() = DiagnosticSnapshot(sessionId, startedMs, sequence,
        (sequence - events.size).coerceAtLeast(0), events.toList())
    @Synchronized fun version() = sequence

    fun failure(component: String, message: DiagnosticMessage, detail: FailureDetails, error: Throwable? = null,
                vararg extra: Pair<String, Any?>) {
        val chain = error?.let { generateSequence(it) { cause -> cause.cause }.take(12).toList() }.orEmpty()
        record(DiagnosticLevel.ERROR, component, message,
            "code" to detail.code, "domain" to detail.domain, "stage" to detail.stage,
            "http_status" to detail.http, "retryable" to detail.retryable,
            "exception_type" to error?.javaClass?.simpleName,
            "cause_types" to chain.joinToString(":") { it.javaClass.simpleName },
            "errno" to chain.filterIsInstance<android.system.ErrnoException>().firstOrNull()?.errno, *extra)
    }

    companion object {
        private val components = setOf("App", "Import", "EPG", "Playback", "Recovery", "Storage")
        private val safeCode = Regex("[A-Za-z0-9_.$:-]{1,120}")
        private val codeFields = setOf("code", "domain", "stage", "source_kind", "section", "media3_name",
            "exception_type", "cause_types", "phase", "reason", "operation", "profile")
        private val numericFields = setOf("http_status", "media3_code", "errno", "retryable", "terminal", "attempt",
            "operation_id", "count", "skipped", "bytes", "total_ms", "restore_ms", "retry_in_ms", "retries",
            "buffered_ms", "player_state", "position_ms", "received_bytes", "ingress_bytes_per_second",
            "video_frames", "live", "activated", "cached")
    }
}

object AppDiagnostics { val log = DiagnosticLog() }

/** Kept separate from the view so copied logs contain exactly the readable on-screen information. */
object DiagnosticText {
    fun header(snapshot: DiagnosticSnapshot, report: JSONObject, zone: ZoneId): String = buildString {
        append("IPTV ").append(report.optString("app_version", "?"))
        append(" (").append(report.opt("version_code") ?: "?").append(") · build=")
        append(report.optString("build_id", "?"))
        append("\nsession=").append(snapshot.sessionId)
        append("\n").append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX '['VV']'")
            .withZone(zone).format(Instant.ofEpochMilli(snapshot.startedMs)))
        report.optJSONObject("device")?.let { device ->
            append("\n").append(device.optString("manufacturer")).append(' ').append(device.optString("model"))
            append(" · Android ").append(device.optString("android")).append(" / API ").append(device.optInt("sdk"))
        }
    }
    fun time(event: DiagnosticEvent, zone: ZoneId): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        .withZone(zone).format(Instant.ofEpochMilli(event.timeMs))
    fun details(event: DiagnosticEvent): String = buildString {
        append("#").append(event.sequence).append("  +").append(event.elapsedMs).append(" ms")
        event.details.forEach { (key, value) -> append("  ").append(key).append('=').append(value) }
    }
}
