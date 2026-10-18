package de.iptv.tv

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

/** The primary series action keeps its episode identifier separate from its label.
 * At larger font sizes both lines stay inside the same remote-control target. */
class SeriesResumeButton(context: Context, click: () -> Unit) : LinearLayout(context) {
    private val action = TvUi.text(context, "", 13f)
    private val episode = TvUi.text(context, "", 10f).apply {
        textDirection = View.TEXT_DIRECTION_LTR
    }
    init {
        id = View.generateViewId()
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = TvUi.dp(context, 36)
        setPaddingRelative(TvUi.dp(context, 12), TvUi.dp(context, 6), TvUi.dp(context, 12), TvUi.dp(context, 6))
        TvUi.focus(this, 1f)
        isActivated = true
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(action, LayoutParams(0, -2, 1f))
        addView(episode, LayoutParams(-2, -2).apply { marginStart = TvUi.dp(context, 8) })
        setOnClickListener { click() }
    }
    fun show(actionLabel: String, episodeLabel: String, description: String) {
        action.text = actionLabel
        episode.text = episodeLabel
        contentDescription = description
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
        val needed = action.paint.measureText(action.text.toString()) + episode.paint.measureText(episode.text.toString()) + TvUi.dp(context, 8)
        val stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && needed > available
        val desiredOrientation = if (stacked) VERTICAL else HORIZONTAL
        if (orientation != desiredOrientation) {
            orientation = desiredOrientation
            action.layoutParams = (action.layoutParams as LayoutParams).apply {
                width = if (stacked) -1 else 0
                weight = if (stacked) 0f else 1f
            }
            episode.layoutParams = (episode.layoutParams as LayoutParams).apply {
                marginStart = if (stacked) 0 else TvUi.dp(context, 8)
                topMargin = if (stacked) TvUi.dp(context, 4) else 0
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
}

/** Long provider names and large system text must leave room for the episode list.
 * Native focus scrolling keeps every header action reachable; the scrollbar is persistent. */
class SeriesHeaderScroll(context: Context) : ScrollView(context) {
    init {
        isFocusable = false
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val boundedHeight = if (mode == MeasureSpec.UNSPECIFIED) heightMeasureSpec else
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) * 0.4f).toInt(), MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, boundedHeight)
    }
}
