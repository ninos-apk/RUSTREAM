@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One surface survives preview/fullscreen changes; D-pad commands depend on the focused region. */
class PlayerPane(
    context: Context,
    private val scope: CoroutineScope,
    private val action: (String) -> Unit,
    private val seekTo: suspend (Long) -> Unit = {},
) : FrameLayout(context) {
    val video = PlayerView(context).apply {
        id = View.generateViewId(); useController = false; isFocusable = true; isFocusableInTouchMode = true
        contentDescription = context.getString(R.string.player_video); setBackgroundColor(Color.BLACK)
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    private val empty = TvUi.text(context, context.getString(R.string.player_empty), 17f, TvUi.muted).apply {
        gravity = Gravity.CENTER; setPadding(dp(20), dp(16), dp(20), dp(16)); setLineSpacing(0f, 1.4f)
    }
    private val overlay = LinearLayout(context).apply {
        id = View.generateViewId(); contentDescription = context.getString(R.string.player_controls); orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = TvUi.surface(0xF2091018.toInt())
        visibility = View.GONE
    }
    private val info = TvUi.text(context, "", 13f).apply {
        setPadding(dp(12), dp(8), dp(12), dp(8)); background = TvUi.surface(0xF2091018.toInt())
        visibility = View.GONE; setLineSpacing(0f, 1.3f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val time = TvUi.text(context, "", 12f, Color.LTGRAY).apply { fontFeatureSettings = "tnum" }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(TvUi.accent)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF36424E.toInt()); isFocusable = false
    }
    private val caption = TvUi.text(context, "", 12f, TvUi.muted).apply { gravity = Gravity.CENTER }
    private val feedback = TvUi.text(context, "", 14f).apply {
        visibility = View.GONE; gravity = Gravity.CENTER; fontFeatureSettings = "tnum"
        background = TvUi.surface(0xFA131923.toInt(), dp(8).toFloat())
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    private val focusHint = TvUi.text(context, context.getString(R.string.player_focus_hint), 12f).apply {
        gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6))
        background = TvUi.surface(TvUi.panel, dp(6).toFloat()); visibility = View.GONE
    }
    private val outline = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()
        override fun onDraw(canvas: Canvas) {
            rect.set(dp(4).toFloat(), dp(4).toFloat(), width - dp(4).toFloat(), height - dp(4).toFloat())
            paint.color = Color.BLACK; paint.strokeWidth = dp(7).toFloat(); canvas.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(), paint)
            paint.color = TvUi.accent; paint.strokeWidth = dp(3).toFloat(); canvas.drawRoundRect(rect, dp(5).toFloat(), dp(5).toFloat(), paint)
        }
    }.apply { visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    val previewFocusVisible get() = outline.visibility == View.VISIBLE
    val buttons = linkedMapOf<String, TvIconButton>()
    private var hideWork: Job? = null
    private var feedbackWork: Job? = null
    private var seekWork: Job? = null
    private var initialSeekWork: Job? = null
    private val hold = SeekHold()
    private var heldKey = 0
    private var lastStats = PlaybackStats()
    private var statusText = ""
    private var guideText = ""
    var hasSelection = false
    /** Null while the current item has no stable key (older catalog, unclassified entry). */
    private var favorite: Boolean? = null
    var isFullscreen = false; private set
    val controlsVisible get() = overlay.visibility == View.VISIBLE
    val seeking get() = hold.active
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        if (!video.hasFocus()) cancelSeek()
        updateFocus()
    }

    init {
        id = View.generateViewId(); contentDescription = context.getString(R.string.player_area); setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(-1, -1)); addView(empty, LayoutParams(-1, -1))
        addView(info, LayoutParams(-1, -2, Gravity.TOP))
        val timeline = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        timeline.addView(time, LinearLayout.LayoutParams(-2, -2))
        timeline.addView(progress, LinearLayout.LayoutParams(0, dp(3), 1f).apply { marginStart = dp(12) })
        overlay.addView(timeline)
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        for ((key, symbol, label) in listOf(
            Triple("back", "back", R.string.player_back_30), Triple("play", "play", R.string.player_play),
            Triple("forward", "forward", R.string.player_forward_30), Triple("stop", "stop", R.string.player_stop), Triple("fullscreen", "fullscreen", R.string.player_fullscreen),
            Triple("favorite", "star", R.string.favorite_add)
        )) {
            val button = TvIconButton(context, symbol, context.getString(label)) { touch(); cancelSeek(); action(key) }.apply { id = View.generateViewId() }
            TvUi.focus(button, 1f) { focused -> if (focused) { caption.text = button.contentDescription; touch() } }
            buttons[key] = button
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            // The ±30 icons carry their number; extra labels pushed them above the other buttons.
            column.addView(button, LinearLayout.LayoutParams(dp(44), dp(42)))
            controls.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        val ordered = buttons.values.toList()
        ordered.forEachIndexed { i, button ->
            button.nextFocusLeftId = ordered.getOrNull(i - 1)?.id ?: button.id
            button.nextFocusRightId = ordered.getOrNull(i + 1)?.id ?: button.id
            button.nextFocusUpId = video.id; button.nextFocusDownId = button.id
            button.setOnKeyListener { _, key, event ->
                if (button === buttons.values.firstOrNull { it.isEnabled && it.isFocusable } && !isFullscreen && key == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) { hideControls(); action("library") }; true
                } else false
            }
        }
        overlay.addView(controls); overlay.addView(caption, LinearLayout.LayoutParams(-1, -2))
        addView(overlay, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(feedback, LayoutParams(-2, -2, Gravity.CENTER))
        addView(focusHint, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        addView(outline, LayoutParams(-1, -1))
        video.setOnClickListener { if (isFullscreen) showControls(true) else if (hasSelection) action("fullscreen") else action("library") }
    }
    /** Shows the favourite state of what is playing (for an episode: its show). */
    fun setFavorite(active: Boolean?) {
        favorite = active
        buttons.getValue("favorite").apply {
            val wanted = if (active == true) "star_on" else "star"
            if (symbol != wanted) { symbol = wanted; invalidate() }
            contentDescription = context.getString(if (active == true) R.string.favorite_remove else R.string.favorite_add)
            val available = hasSelection && active != null
            isEnabled = available; isFocusable = available
            if (hasFocus()) caption.text = contentDescription
        }
    }
    fun updateInformation(status: String, guide: String = guideText) {
        statusText = status; guideText = guide; updateInfo()
    }
    private fun updateInfo() {
        val urgent = lastStats.phase in setOf("connecting", "buffering", "waiting", "error")
        info.visibility = if (isFullscreen && (controlsVisible || urgent)) View.VISIBLE else View.GONE
        val detail = if (lastStats.phase == "error") context.getString(R.string.player_error_hint) else guideText
        info.text = listOf(statusText, detail).filter { it.isNotBlank() }.joinToString("\n")
    }
    private fun updateFocus() {
        outline.visibility = if (hasFocus() && !isFullscreen) View.VISIBLE else View.GONE
        focusHint.visibility = if (video.hasFocus() && !isFullscreen && !controlsVisible && hasSelection) View.VISIBLE else View.GONE
    }
    fun update(stats: PlaybackStats) {
        lastStats = stats
        empty.visibility = if (video.player == null && !hasSelection) View.VISIBLE else View.GONE
        val play = buttons.getValue("play")
        val symbol = if (stats.playing) "pause" else "play"
        if (play.symbol != symbol) { play.symbol = symbol; play.invalidate() }
        play.contentDescription = context.getString(if (stats.playing) R.string.player_pause else R.string.player_play)
        play.isEnabled = hasSelection
        buttons.getValue("stop").isEnabled = video.player != null
        for (key in listOf("back", "forward")) buttons.getValue(key).apply { isEnabled = stats.seekable; isFocusable = stats.seekable }
        buttons.getValue("favorite").apply { val available = hasSelection && favorite != null; isEnabled = available; isFocusable = available }
        if (buttons.values.any { !it.isEnabled && it.hasFocus() }) play.requestFocus()
        if (seeking && (!stats.seekable || stats.live || !hasSelection)) cancelSeek()
        if (!seeking) updateTimeline(stats.positionMs)
        if (controlsVisible && stats.playing && hideWork == null) scheduleHide()
        if (controlsVisible && !stats.playing) { hideWork?.cancel(); hideWork = null }
        buttons.values.firstOrNull { it.hasFocus() }?.let { caption.text = it.contentDescription }
        updateInfo(); updateFocus()
    }
    private fun updateTimeline(position: Long) {
        time.text = if (lastStats.live) "● LIVE" else "${clock(position)} / ${lastStats.durationMs?.let(::clock) ?: "—"}"
        progress.visibility = if (lastStats.live) View.GONE else View.VISIBLE
        progress.progress = lastStats.durationMs?.takeIf { it > 0 }?.let { (position.toDouble() * 1000 / it).toInt().coerceIn(0, 1000) } ?: 0
    }
    fun showControls(focus: Boolean = false) {
        cancelSeek(); overlay.animate().cancel(); overlay.visibility = View.VISIBLE
        if (TvUi.animationsEnabled(context)) { overlay.alpha = 0f; overlay.animate().alpha(1f).setDuration(140).start() } else overlay.alpha = 1f
        if (focus) (buttons.values.firstOrNull { it.symbol == "play" || it.symbol == "pause" }?.takeIf { it.isEnabled }
            ?: buttons.getValue("fullscreen")).requestFocus()
        touch(); updateFocus(); updateInfo()
    }
    fun hideControls() {
        hideWork?.cancel(); hideWork = null
        if (overlay.hasFocus()) video.requestFocus()
        overlay.animate().cancel(); overlay.visibility = View.GONE
        updateFocus(); updateInfo()
    }
    fun setFullscreen(value: Boolean, fill: Boolean) {
        cancelSeek(); isFullscreen = value
        video.resizeMode = if (value && fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        buttons.getValue("fullscreen").apply { symbol = if (value) "restore" else "fullscreen"; contentDescription = context.getString(if (value) R.string.player_fullscreen_exit else R.string.player_fullscreen); invalidate() }
        hideControls(); video.requestFocus(); updateFocus(); updateInfo()
    }
    fun cancelSeek() {
        initialSeekWork?.cancel(); initialSeekWork = null
        stopHeldSeek()
    }
    private fun stopHeldSeek() {
        seekWork?.cancel(); seekWork = null; hold.cancel(); heldKey = 0
    }
    private fun startSeek(key: Int) {
        val direction = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
        val now = SystemClock.uptimeMillis()
        val player = video.player ?: return
        val target = hold.begin(direction, now, player.currentPosition, lastStats.durationMs) ?: return
        seekWork?.cancel(); initialSeekWork?.cancel(); heldKey = key; hideWork?.cancel(); hideWork = null
        initialSeekWork = scope.launch { seekTo(target); showSeek(target, now, false, direction) }
        seekWork = scope.launch {
            delay(SeekHold.HOLD_MS)
            initialSeekWork?.join()
            while (isActive && hold.active && video.hasFocus() && isFullscreen && video.player === player) {
                val tick = SystemClock.uptimeMillis()
                hold.advance(tick)?.let { position -> seekTo(position); showSeek(position, tick, true) }
                delay(160)
            }
        }
    }
    private fun showSeek(position: Long, now: Long, accelerated: Boolean, value: Int = hold.direction) {
        val direction = context.getString(if (value > 0) R.string.player_fast_forward else R.string.player_rewind)
        flash(if (accelerated) "$direction · ${hold.speed(now).toInt()} s/s · ${clock(position)}" else "${if (value > 0) "+30 s" else "−30 s"} · ${clock(position)}")
        updateTimeline(position)
    }
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == heldKey && heldKey != 0) { stopHeldSeek(); return true }
        if (event.action != KeyEvent.ACTION_DOWN || !hasFocus()) return false
        if (controlsVisible && !video.hasFocus()) {
            touch()
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.repeatCount == 0) hideControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!isFullscreen && event.repeatCount == 0) { hideControls(); action("details") }
                    return true
                }
            }
            return false
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    if (isFullscreen) showControls(true) else if (hasSelection) action("fullscreen") else action("library")
                }; return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.repeatCount == 0) showControls(true); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.repeatCount == 0) { if (isFullscreen) showControls(true) else { hideControls(); action("settings") } }; return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isFullscreen) { if (event.repeatCount == 0) { hideControls(); action(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) "library" else "statistics") } }
                else if (lastStats.seekable && !lastStats.live && hasSelection) {
                    if (heldKey != event.keyCode) startSeek(event.keyCode)
                } else if (event.repeatCount == 0) flash(context.getString(R.string.player_live_no_seek))
                return true
            }
        }
        return false
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (handleKey(event)) true else super.dispatchKeyEvent(event)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnGlobalFocusChangeListener(focusListener) }
    override fun onDetachedFromWindow() {
        cancelSeek(); hideWork?.cancel(); hideWork = null; feedbackWork?.cancel()
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        super.onDetachedFromWindow()
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) cancelSeek() }
    fun flash(value: String) {
        feedbackWork?.cancel(); feedback.text = value; feedback.visibility = View.VISIBLE
        feedbackWork = scope.launch { delay(1500); feedback.visibility = View.GONE }
    }
    private fun touch() { hideWork?.cancel(); hideWork = null; if (lastStats.playing) scheduleHide() }
    private fun scheduleHide() { hideWork = scope.launch { delay(4000); hideWork = null; hideControls() } }
    private fun clock(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
    }
    private fun dp(value: Int) = TvUi.dp(context, value)
}
