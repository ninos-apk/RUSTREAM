package de.iptv.tv

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.ViewCompat

/** A bounded, native report reader: D-pad focus belongs to the scroller, not a text cursor. */
class DiagnosticsDialog(context: Context, private val report: String, returnFocus: View?) : Dialog(context) {
    private val copyButton = TvUi.button(context, context.getString(R.string.copy)) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostics_clip), report))
        Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }.apply { id = android.R.id.button1 }
    private val closeButton = TvUi.button(context, context.getString(R.string.close)) { dismiss() }
        .apply { id = android.R.id.button2 }
    private val reportText = TvUi.text(context, report, 14f).apply {
        tag = "diagnostics_text"
        setTextIsSelectable(false)
        isFocusable = false
        isFocusableInTouchMode = false
        setHorizontallyScrolling(false)
        setPadding(dp(16), dp(12), dp(24), dp(12))
        setLineSpacing(0f, 1.2f)
    }
    private val reportScroll = ScrollView(context).apply {
        id = View.generateViewId()
        tag = "diagnostics_scroll"
        contentDescription = context.getString(R.string.diagnostics_text)
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        background = TvUi.focusBackground(context)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        addView(reportText, FrameLayout.LayoutParams(-1, -2))
        setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.action == KeyEvent.ACTION_DOWN) copyButton.requestFocus()
                true
            } else false // Native ScrollView handles vertical scrolling, including held keys.
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setTitle(R.string.diagnostics_title)
        setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(TvUi.text(context, context.getString(R.string.diagnostics_title), 20f).apply {
                ViewCompat.setAccessibilityHeading(this, true)
                setPadding(0, 0, 0, dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(TvUi.text(context, context.getString(R.string.diagnostics_scroll_hint), 12f, TvUi.muted).apply {
                setPadding(0, 0, 0, dp(10))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(reportScroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(context).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                val narrow = context.resources.configuration.screenWidthDp < 600 || context.resources.configuration.fontScale >= 1.5f
                addView(closeButton, LinearLayout.LayoutParams(if (narrow) 0 else -2, -2, if (narrow) 1f else 0f))
                addView(copyButton, LinearLayout.LayoutParams(if (narrow) 0 else -2, -2, if (narrow) 1f else 0f).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        })
        copyButton.nextFocusUpId = reportScroll.id
        closeButton.nextFocusUpId = reportScroll.id
        setOnDismissListener {
            if (returnFocus?.isShown == true && returnFocus.isFocusable) returnFocus.requestFocus()
        }
    }

    override fun onStart() {
        super.onStart()
        val configuration = context.resources.configuration
        window?.setLayout(dp(minOf(configuration.screenWidthDp - 48, 900)), dp(configuration.screenHeightDp - 48))
        reportScroll.requestFocus()
    }

    private fun dp(value: Int) = TvUi.dp(context, value)
}
