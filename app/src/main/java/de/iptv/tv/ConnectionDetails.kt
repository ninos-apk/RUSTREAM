package de.iptv.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.util.Locale

class ConnectionDetails(context: Context, refresh: () -> Unit = {}) : LinearLayout(context) {
    private val health = TvUi.text(context, "●  Bereit", 12f, TvUi.accent)
    private val buffer = TvUi.text(context, "PUFFER   —", 12f, TvUi.muted)
    private val ingress = TvUi.text(context, "ZUFLUSS   —", 12f, TvUi.muted)
    private val memory = TvUi.text(context, "DATENPUFFER   —", 12f, TvUi.muted)
    private val format = TvUi.text(context, "", 12f, TvUi.muted)
    val refreshButton = TvUi.button(context, "Refresh", refresh).apply {
        contentDescription = "Refresh · Wiedergabe neu verbinden"
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
        footer.addView(TvUi.text(context, "developed by Ninos Oshana", 10f, TvUi.muted).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(TvUi.dp(context, 12), 0, 0, 0)
        }, LayoutParams(0, -2, 1f))
        addView(footer, LayoutParams(-1, -2))
    }
    fun update(stats: PlaybackStats) {
        val label = when(stats.phase) {
            "playing" -> if (stats.bufferedMs == null) "Wiedergabe" else if (stats.bufferedMs < minOf(2500, stats.bufferTargetMs / 2)) "Wenig Puffer" else "Stabil"
            "paused" -> "Pausiert"
            "connecting" -> "Verbinde"
            "buffering" -> "Puffert"
            "waiting" -> "Neu verbinden"
            "finished" -> "Beendet"
            "error" -> "Quellfehler"
            else -> "Bereit"
        }
        health.text = "●  $label"
        health.setTextColor(if (label in setOf("Stabil","Pausiert","Bereit","Beendet")) TvUi.accent else TvUi.warning)
        buffer.text = "PUFFER   " + (stats.bufferedMs?.let { String.format(Locale.GERMANY,"%.1f s",it/1000.0) } ?: "—")
        ingress.text = "ZUFLUSS   " + (stats.ingressBytesPerSecond?.let { String.format(Locale.GERMANY,"%.2f Mbit/s",it*8/1_000_000.0) } ?: "—")
        memory.text = "DATENPUFFER   " + (stats.allocatedBytes?.let { String.format(Locale.GERMANY,"%.1f MiB",it/1048576.0) } ?: "—")
        format.text = stats.format
        format.visibility = if (stats.format.isEmpty()) View.GONE else View.VISIBLE
    }
}
