package de.iptv.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

class ConnectionDetails(context: Context, refresh: () -> Unit = {}) : LinearLayout(context) {
    // Resolved once: update() runs with every playback sample.
    private val locale = AppLanguage.locale(context)
    private val bufferLabel = context.getString(R.string.stat_buffer) + "   "
    private val ingressLabel = context.getString(R.string.stat_ingress) + "   "
    private val memoryLabel = context.getString(R.string.stat_memory) + "   "
    private val health = TvUi.text(context, "●  " + context.getString(R.string.health_ready), 12f, TvUi.accent)
    private val buffer = TvUi.text(context, "$bufferLabel—", 12f, TvUi.muted)
    private val ingress = TvUi.text(context, "$ingressLabel—", 12f, TvUi.muted)
    private val memory = TvUi.text(context, "$memoryLabel—", 12f, TvUi.muted)
    private val format = TvUi.text(context, "", 12f, TvUi.muted)
    val refreshButton = TvUi.button(context, context.getString(R.string.refresh), refresh).apply {
        contentDescription = context.getString(R.string.refresh_description)
        isEnabled = false
    }
    init {
        orientation = VERTICAL
        val values = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        for (view in listOf(health,buffer,ingress,memory,format)) {
            view.fontFeatureSettings = "tnum"
            view.setPadding(0, TvUi.dp(context, 4), TvUi.dp(context, 24), TvUi.dp(context, 4))
            TvUi.focus(view, 1f)
            values.addView(view, LayoutParams(-2, -2))
        }
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = true; addView(values) }, LayoutParams(-1, -2))
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(refreshButton, LayoutParams(-2, -2))
        footer.addView(TvUi.text(context, context.getString(R.string.app_developer), 10f, TvUi.muted).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(TvUi.dp(context, 12), 0, 0, 0)
        }, LayoutParams(0, -2, 1f))
        addView(footer, LayoutParams(-1, -2))
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
        health.text = "●  " + context.getString(label)
        health.setTextColor(if (label in setOf(R.string.health_stable, R.string.health_paused, R.string.health_ready, R.string.health_finished)) TvUi.accent else TvUi.warning)
        buffer.text = bufferLabel + (stats.bufferedMs?.let { String.format(locale,"%.1f s",it/1000.0) } ?: "—")
        ingress.text = ingressLabel + (stats.ingressBytesPerSecond?.let { String.format(locale,"%.2f Mbit/s",it*8/1_000_000.0) } ?: "—")
        memory.text = memoryLabel + (stats.allocatedBytes?.let { String.format(locale,"%.1f MiB",it/1048576.0) } ?: "—")
        format.text = stats.format
        format.visibility = if (stats.format.isEmpty()) View.GONE else View.VISIBLE
    }
}
