package de.iptv.tv

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout

class ConnectionDetails(context: Context, refresh: () -> Unit = {}) : LinearLayout(context) {
    // Resolved once: update() runs with every playback sample.
    private val locale = AppLanguage.locale(context)
    private val bufferLabel = context.getString(R.string.stat_buffer)
    private val ingressLabel = context.getString(R.string.stat_ingress)
    private val memoryLabel = context.getString(R.string.stat_memory)
    // A plain readout: no remote stops, while screen readers can still read every value.
    val statistics = TvUi.text(context, "", 12f, TvUi.muted).apply {
        fontFeatureSettings = "tnum"; setLineSpacing(0f, 1.4f)
        setPadding(0, TvUi.dp(context, 4), 0, TvUi.dp(context, 4))
        isFocusable = false; isClickable = false
    }
    val refreshButton = TvUi.button(context, context.getString(R.string.refresh), refresh).apply {
        contentDescription = context.getString(R.string.refresh_description)
        textSize = 11f; isSingleLine = true
        minHeight = TvUi.dp(context, 28); minimumHeight = minHeight
        setPadding(TvUi.dp(context, 10), TvUi.dp(context, 3), TvUi.dp(context, 10), TvUi.dp(context, 3))
        background = TvUi.focusBackground(context, 16)
        isEnabled = false
    }
    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(statistics, LayoutParams(0, -2, 1f))
        row.addView(refreshButton, LayoutParams(-2, -2).apply { marginStart = TvUi.dp(context, 12) })
        addView(row, LayoutParams(-1, -2))
        addView(TvUi.text(context, context.getString(R.string.app_developer), 10f, TvUi.muted).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, TvUi.dp(context, 2), 0, 0)
        }, LayoutParams(-1, -2))
        refreshButton.nextFocusDownId = refreshButton.id
        update(PlaybackStats())
    }
    fun setFocusAbove(view: View) {
        refreshButton.nextFocusUpId = view.id
        refreshButton.nextFocusLeftId = view.id
        view.nextFocusDownId = refreshButton.id
        view.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && refreshButton.isEnabled) refreshButton.requestFocus()
                true
            } else false
        }
    }
    fun update(stats: PlaybackStats) {
        val label = when(stats.phase) {
            "playing" -> if (stats.bufferedMs == null) R.string.health_playing else if (stats.bufferedMs < minOf(2500, stats.bufferTargetMs / 2)) R.string.health_low_buffer else R.string.health_stable
            "paused" -> R.string.health_paused
            "connecting" -> R.string.health_connecting
            "buffering" -> R.string.health_buffering
            "waiting" -> R.string.health_reconnecting
            "finished" -> R.string.health_finished
            "error" -> R.string.health_error
            else -> R.string.health_ready
        }
        val health = "●  " + context.getString(label)
        val color = if (label in setOf(R.string.health_stable, R.string.health_paused, R.string.health_ready, R.string.health_finished)) TvUi.accent else TvUi.warning
        val values = listOf(
            "$bufferLabel  " + (stats.bufferedMs?.let { String.format(locale,"%.1f s",it/1000.0) } ?: "—"),
            "$ingressLabel  " + (stats.ingressBytesPerSecond?.let { String.format(locale,"%.2f Mbit/s",it*8/1_000_000.0) } ?: "—"),
            "$memoryLabel  " + (stats.allocatedBytes?.let { String.format(locale,"%.1f MiB",it/1048576.0) } ?: "—"),
            stats.format
        ).filter { it.isNotBlank() }
        // Keep each metric together; wrap between metrics when larger system text needs room.
        statistics.text = SpannableStringBuilder(health).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (value in values) append("    ").append(value.replace(' ', '\u00a0'))
        }
    }
}
