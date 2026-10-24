package de.iptv.tv

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native D-pad reader. Copy always includes the technical details visible in the selected view. */
class DiagnosticsDialog(context: Context, report: String, returnFocus: View?,
                        private val log: DiagnosticLog = AppDiagnostics.log) : Dialog(context) {
    private val baseReport = JSONObject(report)
    private val zone = ZoneId.systemDefault()
    private var snapshot = log.snapshot()
    private var jsonMode = false
    private val positions = intArrayOf(0, 0)
    private val handler = Handler(Looper.getMainLooper())
    private val largeText = context.resources.configuration.fontScale >= 1.5f
    private val logsButton = TvUi.button(context, context.getString(R.string.diagnostics_logs)) { switchView(false) }
        .apply { tag = "diagnostics_logs_button" }
    private val jsonButton = TvUi.button(context, context.getString(R.string.diagnostics_json)) { switchView(true) }
        .apply { tag = "diagnostics_json_button" }
    private val latestButton = TvUi.button(context, context.getString(R.string.diagnostics_new_entries)) { showLatest() }
        .apply { tag = "diagnostics_latest"; visibility = View.GONE }
    private val copyButton = TvUi.button(context, context.getString(R.string.diagnostics_copy_logs)) { copyDisplayed() }
        .apply { id = android.R.id.button1 }
    private val closeButton = TvUi.button(context, context.getString(R.string.close)) { dismiss() }
        .apply { id = android.R.id.button2 }
    private val reportText = TvUi.text(context, "", 14f).apply {
        tag = "diagnostics_text"
        setTextIsSelectable(false)
        isFocusable = false
        isFocusableInTouchMode = false
        setHorizontallyScrolling(false)
        setPadding(dp(12), dp(10), dp(20), dp(10))
        setLineSpacing(0f, 1.12f)
        fontFeatureSettings = "tnum"
    }
    private val reportScroll = ScrollView(context).apply {
        id = View.generateViewId()
        tag = "diagnostics_scroll"
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        isSmoothScrollingEnabled = false
        background = TvUi.focusBackground(context)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(reportText, FrameLayout.LayoutParams(-1, -2))
        setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) copyButton.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) (if (jsonMode) jsonButton else logsButton).requestFocus()
                    true
                }
                else -> false
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!isShowing) return
            if (!jsonMode && log.version() != snapshot.version) {
                // Freeze the snapshot while reading older entries, even if the ring drops events.
                if (!reportScroll.canScrollVertically(1) && latestButton.visibility != View.VISIBLE) {
                    snapshot = log.snapshot()
                    render()
                    reportText.doOnNextLayout { reportScroll.fullScroll(View.FOCUS_DOWN) }
                } else visibilityLatest(true)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setTitle(R.string.diagnostics_title)
        setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.panel)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(TvUi.text(context, context.getString(R.string.diagnostics_title), 20f).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                addView(logsButton, LinearLayout.LayoutParams(0, -2, 1f))
                addView(jsonButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
            addView(TvUi.text(context, context.getString(R.string.diagnostics_log_hint), 12f, TvUi.muted).apply {
                visibility = if (largeText) View.GONE else View.VISIBLE
            },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(latestButton, LinearLayout.LayoutParams(-1, -2))
            addView(reportScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                addView(closeButton, LinearLayout.LayoutParams(if(largeText) -1 else 0, -2, if(largeText) 0f else 1f))
                addView(copyButton, LinearLayout.LayoutParams(if(largeText) -1 else 0, -2, if(largeText) 0f else 1f).apply {
                    if(largeText) topMargin=dp(6) else marginStart=dp(12)
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        for (button in listOf(copyButton, closeButton)) button.nextFocusUpId = reportScroll.id
        if (largeText) {
            copyButton.nextFocusUpId = closeButton.id
            closeButton.nextFocusDownId = copyButton.id
        }
        for (button in listOf(logsButton, jsonButton, latestButton)) button.nextFocusDownId = reportScroll.id
        for (button in listOf(logsButton,jsonButton)) button.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (latestButton.visibility == View.VISIBLE) latestButton.requestFocus()
                    else {
                        val position = positions[if(jsonMode) 1 else 0]
                        reportScroll.requestFocus()
                        reportScroll.post { reportScroll.scrollTo(0,position) }
                    }
                }
                true
            } else false
        }
        setOnDismissListener {
            handler.removeCallbacks(poll)
            if (returnFocus?.isShown == true && returnFocus.isFocusable) returnFocus.requestFocus()
        }
        render()
    }
    private fun copyDisplayed() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostics_clip), reportText.text.toString()))
        Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }
    private fun showLatest() {
        snapshot = log.snapshot()
        positions[0] = Int.MAX_VALUE
        render()
        visibilityLatest(false)
        reportText.doOnNextLayout { reportScroll.requestFocus(); reportScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun visibilityLatest(visible: Boolean) {
        latestButton.visibility = if (visible && !jsonMode) View.VISIBLE else View.GONE
        val next = if (latestButton.visibility == View.VISIBLE) latestButton.id else reportScroll.id
        logsButton.nextFocusDownId = next
        jsonButton.nextFocusDownId = next
    }
    private fun switchView(json: Boolean) {
        if (jsonMode == json) return
        positions[if (jsonMode) 1 else 0] = reportScroll.scrollY
        jsonMode = json
        // Both views describe the same event snapshot until the reader goes to latest.
        render()
        visibilityLatest(!json && log.version() != snapshot.version)
        val position = positions[if (json) 1 else 0]
        reportText.doOnNextLayout { reportScroll.scrollTo(0, position) }
    }
    private fun render() {
        logsButton.isSelected = !jsonMode
        jsonButton.isSelected = jsonMode
        logsButton.setTextColor(if (!jsonMode) TvUi.accent else android.graphics.Color.WHITE)
        jsonButton.setTextColor(if (jsonMode) TvUi.accent else android.graphics.Color.WHITE)
        logsButton.typeface = if (!jsonMode) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        jsonButton.typeface = if (jsonMode) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        copyButton.setText(if (jsonMode) R.string.diagnostics_copy_json else R.string.diagnostics_copy_logs)
        reportScroll.contentDescription = context.getString(if (jsonMode) R.string.diagnostics_text else R.string.diagnostics_log_text)
        reportText.typeface = if (jsonMode) Typeface.MONOSPACE else Typeface.DEFAULT
        reportText.text = if (jsonMode) JSONObject(baseReport.toString()).put("diagnostic_log", snapshot.json()).toString(2) else logs()
    }
    private fun logs(): CharSequence = SpannableStringBuilder().apply {
        fun styled(value: String, vararg spans: Any) {
            val start = length
            append(value)
            spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        styled(DiagnosticText.header(snapshot, baseReport, zone) + "\n", ForegroundColorSpan(TvUi.muted), RelativeSizeSpan(.88f))
        styled(context.getString(R.string.diagnostics_session_note) + "\n", ForegroundColorSpan(TvUi.muted), RelativeSizeSpan(.88f))
        if (snapshot.dropped > 0) styled(context.getString(R.string.diagnostics_dropped,snapshot.dropped) + "\n", ForegroundColorSpan(TvUi.warning))
        val captured = DateTimeFormatter.ofPattern("HH:mm:ss XXX").withZone(zone)
            .format(Instant.ofEpochMilli(baseReport.optLong("snapshot_at",snapshot.startedMs)))
        append('\n').append(context.getString(R.string.diagnostics_current_state,captured)).append('\n')
        val health = baseReport.optJSONObject("playback_health")
        val imported = baseReport.optJSONObject("import")
        val library = baseReport.optJSONObject("user_library")
        val artwork = baseReport.optJSONObject("artwork")
        val state = buildString {
            append(context.getString(R.string.diagnostics_state_playback,health?.optString("phase") ?: "?",health?.optString("profile") ?: "?")).append('\n')
            append(context.getString(R.string.diagnostics_state_import,imported?.opt("count") ?: "?",imported?.opt("total_ms") ?: "?")).append('\n')
            if(library != null) append(context.getString(R.string.diagnostics_state_library,library.opt("writes"),library.opt("last_error"))).append('\n')
            if(artwork != null) append(context.getString(R.string.diagnostics_state_artwork,artwork.opt("network_requests"),artwork.opt("queued_loads"))).append('\n')
            baseReport.optJSONObject("previous_exit")?.let { exit ->
                val time=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(zone).format(Instant.ofEpochMilli(exit.optLong("time")))
                append(context.getString(R.string.diagnostics_state_exit,exit.optString("meaning"),exit.optInt("reason"),time)).append('\n')
            }
            append('\n')
        }
        styled(state,ForegroundColorSpan(TvUi.muted),RelativeSizeSpan(.88f))
        snapshot.events.forEach { event ->
            styled(DiagnosticText.time(event,zone) + "  ", ForegroundColorSpan(TvUi.muted), TypefaceSpan("monospace"), RelativeSizeSpan(.88f))
            val color = when(event.level) { DiagnosticLevel.INFO -> 0xFF8ECFFF.toInt(); DiagnosticLevel.WARN -> TvUi.warning; DiagnosticLevel.ERROR -> 0xFFFF9292.toInt() }
            styled(event.level.name, ForegroundColorSpan(color), StyleSpan(Typeface.BOLD))
            append("  ").append(event.component).append("  ").append(context.getString(event.message.resource))
            val code = event.details["code"] as? String
            if (code != null && event.level != DiagnosticLevel.INFO) {
                val detail = ImportErrors.describe(ImportFailure(code,event.details["stage"] as? String ?: "operation",
                    (event.details["http_status"] as? Number)?.toInt()))
                if (detail.messageRes != R.string.error_unknown) append(" · ").append(detail.message(context))
            }
            append('\n')
            styled(DiagnosticText.details(event), ForegroundColorSpan(TvUi.muted), RelativeSizeSpan(.85f))
            append("\n\n")
        }
    }
    override fun onStart() {
        super.onStart()
        val configuration = context.resources.configuration
        window?.setLayout(dp(minOf(configuration.screenWidthDp - 48, 1000)), dp(configuration.screenHeightDp - 48))
        reportScroll.requestFocus()
        handler.postDelayed(poll, 1_000)
    }
    override fun onStop() { handler.removeCallbacks(poll); super.onStop() }
    private fun dp(value: Int) = TvUi.dp(context, value)
}
