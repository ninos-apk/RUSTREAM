package de.iptv.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.ImageView

/** App-owned loading screen; slow network operations never hold Android's system splash. */
class StartupPane(context: Context, private val retry: () -> Unit, private val sources: () -> Unit) : FrameLayout(context) {
    private val brand = TvUi.text(context, context.getString(R.string.app_name), 28f).apply {
        gravity = Gravity.CENTER; letterSpacing = .06f; setTypeface(typeface, Typeface.BOLD)
    }
    private val heading = TvUi.text(context, context.getString(R.string.startup_preparing), 22f).apply { gravity = Gravity.CENTER }
    private val message = TvUi.text(context, context.getString(R.string.loading_saved_source), 16f, TvUi.muted).apply {
        gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(TvUi.accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val retryButton = TvUi.button(context, context.getString(R.string.retry), retry)
    private val sourceButton = TvUi.button(context, context.getString(R.string.source_add), sources)
    var loading = false; private set
    init {
        id = View.generateViewId()
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF14262E.toInt(), TvUi.background, TvUi.background))
        isFocusable = true; isFocusableInTouchMode = true
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(TvUi.dp(context, 32), TvUi.dp(context, 30), TvUi.dp(context, 32), TvUi.dp(context, 30))
        }
        content.addView(ImageView(context).apply {
            setImageResource(R.drawable.rustream_logo);scaleType=ImageView.ScaleType.FIT_CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(-1,TvUi.dp(context,if(resources.configuration.fontScale>1.3f)72 else 112)).apply{bottomMargin=TvUi.dp(context,12)})
        content.addView(brand, LinearLayout.LayoutParams(-1, -2))
        content.addView(TvUi.text(context, "LIVE  /  MOVIES  /  SERIES", 11f, TvUi.accent).apply { gravity = Gravity.CENTER; letterSpacing = .12f }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = TvUi.dp(context, 24) })
        content.addView(heading, LinearLayout.LayoutParams(-1, -2))
        content.addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = TvUi.dp(context, 12) })
        content.addView(progress, LinearLayout.LayoutParams(TvUi.dp(context, 180), TvUi.dp(context, 4)).apply { topMargin = TvUi.dp(context, 24); bottomMargin = TvUi.dp(context, 18) })
        content.addView(retryButton, LinearLayout.LayoutParams(-2, -2))
        content.addView(sourceButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = TvUi.dp(context, 8) })
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(content) }
        addView(scroll, LayoutParams(TvUi.dp(context, minOf(context.resources.configuration.screenWidthDp,720)), -1, Gravity.CENTER))
    }
    fun showLoading(value: String) {
        val first = visibility != View.VISIBLE || !loading
        visibility = View.VISIBLE; loading = true
        heading.setText(R.string.startup_preparing)
        message.text = value
        progress.visibility = if (TvUi.animationsEnabled(context)) View.VISIBLE else View.INVISIBLE
        retryButton.visibility = View.GONE; sourceButton.visibility = View.GONE
        requestFocus()
        if (first && TvUi.animationsEnabled(context)) {
            brand.alpha = 0f; brand.animate().alpha(1f).setDuration(280).start()
        }
    }
    fun updateProgress(value: String) { if (loading) message.text = value }
    fun showEmpty() {
        showMessage(context.getString(R.string.startup_empty_title), context.getString(R.string.startup_empty_message), false)
    }
    fun showError(value: String) { showMessage(context.getString(R.string.startup_error_title), value, true) }
    private fun showMessage(title: String, value: String, failed: Boolean) {
        loading = false; visibility = View.VISIBLE
        brand.animate().cancel(); brand.alpha = 1f
        heading.text = title; message.text = value; progress.visibility = View.GONE
        retryButton.visibility = if (failed) View.VISIBLE else View.GONE
        sourceButton.setText(if (failed) R.string.source_choose else R.string.source_add)
        sourceButton.visibility = View.VISIBLE
        (if (failed) retryButton else sourceButton).requestFocus()
    }
    fun dismiss() { loading = false; brand.animate().cancel(); visibility = View.GONE }
    fun focusPrimary() { if(loading) requestFocus() else (if(retryButton.visibility==View.VISIBLE) retryButton else sourceButton).requestFocus() }
}
