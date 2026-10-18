package de.iptv.tv

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Keep code and name on one line until large text in a narrow panel needs a stack. */
class EpisodeRowLayout(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val code = getChildAt(0) as? TextView
        val content = getChildAt(1)
        if (code != null && content != null) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
            val needed = code.paint.measureText(code.text.toString()) + TvUi.dp(context, 8) +
                TvUi.dp(context, 48) * resources.configuration.fontScale
            val stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && needed > available
            val wanted = if (stacked) VERTICAL else HORIZONTAL
            if (orientation != wanted) {
                orientation = wanted
                code.layoutParams = (code.layoutParams as LayoutParams).apply {
                    width = if (stacked) -1 else -2
                    marginEnd = if (stacked) 0 else TvUi.dp(context, 8)
                    bottomMargin = if (stacked) TvUi.dp(context, 2) else 0
                    gravity = Gravity.TOP
                }
                content.layoutParams = (content.layoutParams as LayoutParams).apply {
                    width = if (stacked) -1 else 0
                    weight = if (stacked) 0f else 1f
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
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
            MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(heightMeasureSpec) * 0.3f).toInt(), MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, boundedHeight)
    }
}
