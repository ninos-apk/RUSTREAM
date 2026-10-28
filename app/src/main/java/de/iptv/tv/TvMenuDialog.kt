package de.iptv.tv

import android.app.AlertDialog
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class MenuAction(val title: Int, val hint: Int? = null, val run: () -> Unit)
internal data class MenuSection(val title: Int?, val actions: List<MenuAction>)

/** Shared, bounded TV menu. Back stays visible while long options scroll.
 * Labels wrap, groups use spacing, and START/END follow the current layout direction. */
internal class TvMenuDialog(
    context: Context,
    title: Int,
    sections: List<MenuSection>,
    initialAction: Int,
    private val sidePanel: Boolean = true,
    introduction: Int? = null,
    onCancel: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) : AlertDialog(context) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(18), dp(20), dp(18), dp(16))
        background = TvUi.surface(TvUi.panel, dp(10).toFloat())
    }
    private val actions = linkedMapOf<Int, Button>()
    private val initial = initialAction

    init {
        val options = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(0, if (sidePanel) dp(12) else 0, 0, dp(4))
        }
        // At large text sizes a wizard's title and explanation must scroll too;
        // otherwise they consume the space needed to read a complete option.
        val headingContainer = if (sidePanel) content else options
        headingContainer.addView(TvUi.text(context, context.getString(title), 20f),
            LinearLayout.LayoutParams(-1, -2).apply { if (!sidePanel) bottomMargin = dp(12) })
        introduction?.let {
            headingContainer.addView(TvUi.text(context, context.getString(it), 13f, TvUi.muted),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
        sections.forEachIndexed { index, section ->
            section.title?.let {
                options.addView(TvUi.text(context, context.getString(it), 12f, TvUi.muted).apply {
                    setPaddingRelative(dp(12), 0, dp(12), 0)
                }, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = if (index == 0) 0 else dp(12)
                    bottomMargin = dp(8)
                })
            }
            section.actions.forEach { action ->
                val label = context.getString(action.title)
                val button = TvUi.button(context, label) { dismiss(); action.run() }.apply {
                    tag = action.title
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
                    action.hint?.let { hint ->
                        val detail = context.getString(hint)
                        text = SpannableString("$label\n$detail").apply {
                            setSpan(RelativeSizeSpan(11f / 13f), label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(ForegroundColorSpan(TvUi.muted), label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        minimumHeight = dp(58)
                        contentDescription = "$label. $detail"
                    }
                }
                actions[action.title] = button
                options.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
        }
        content.addView(ScrollView(context).apply {
            isFillViewport = false
            isSmoothScrollingEnabled = false
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(options)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val back = TvUi.button(context, context.getString(if (sidePanel) R.string.close else R.string.playlist_back)) {
            cancel()
        }
        content.addView(back, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        actions.values.lastOrNull()?.let { last ->
            // ScrollView otherwise consumes the first Down to scroll its bottom padding,
            // which can strand focus on the last option at large text sizes.
            last.setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_DPAD_DOWN) false else {
                    if (event.action == KeyEvent.ACTION_DOWN) back.requestFocus()
                    true
                }
            }
            back.setOnKeyListener { _, key, event ->
                if (key != KeyEvent.KEYCODE_DPAD_UP) false else {
                    if (event.action == KeyEvent.ACTION_DOWN) last.requestFocus()
                    true
                }
            }
        }
        setView(content, 0, 0, 0, 0)
        setOnCancelListener { onCancel?.invoke() }
        setOnDismissListener { onDismiss?.invoke() }
    }

    override fun onStart() {
        super.onStart()
        val configuration = context.resources.configuration
        val maxWidth = dp((configuration.screenWidthDp - 36).coerceAtLeast(1))
        val desiredWidth = dp(if (!sidePanel) 520 else if (configuration.fontScale > 1.3f) 460 else 310)
        val width = minOf(desiredWidth, maxWidth)
        val maxHeight = dp((configuration.screenHeightDp - 36).coerceAtLeast(1))
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(width, content.measuredHeight.coerceAtMost(maxHeight))
            setGravity(if (sidePanel) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER)
            attributes = attributes.apply { x = if (sidePanel) dp(18) else 0; dimAmount = 0.55f }
        }
        (actions[initial] ?: actions.values.firstOrNull())?.requestFocus()
    }

    private fun dp(value: Int) = TvUi.dp(context, value)
}
