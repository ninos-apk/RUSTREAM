package de.iptv.tv

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Native modal reader: text scrolls independently from the always visible actions. */
class UpdateDialog(context: Context, private val controller: UpdateController, returnFocus: View?,
    private val requestPermission: () -> Unit) : Dialog(context) {
    private val heading = TvUi.text(context, context.getString(R.string.updates_title), 20f)
    private val summary = TvUi.text(context, "", 13f)
    private val text = TvUi.text(context, "", 15f).apply {
        tag = "update_notes"; setPadding(dp(14), dp(12), dp(18), dp(12)); setLineSpacing(0f, 1.2f)
        setTextIsSelectable(false); isFocusable = false
    }
    private val primary = TvUi.button(context, context.getString(R.string.updates_now)) { performUpdate() }
        .apply { id = android.R.id.button1 }
    private val secondary = TvUi.button(context, context.getString(R.string.updates_check)) { controller.check(true) }
        .apply { id = android.R.id.button3 }
    private val close = TvUi.button(context, context.getString(R.string.updates_later)) { dismiss() }
        .apply { id = android.R.id.button2 }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100; isIndeterminate = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val reader = ScrollView(context).apply {
        id = View.generateViewId(); tag = "update_scroll"; isFillViewport = true
        isFocusable = true; isFocusableInTouchMode = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false
        background = TvUi.focusBackground(context)
        contentDescription = context.getString(R.string.updates_notes)
        addView(text, FrameLayout.LayoutParams(-1, -2))
        setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (event.action == KeyEvent.ACTION_DOWN) (if (primary.isEnabled && primary.isShown) primary else secondary.takeIf { it.isEnabled } ?: close).requestFocus()
                true
            } else false
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE); setTitle(R.string.updates_title)
        ViewCompat.setAccessibilityHeading(heading, true)
        summary.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(heading, LinearLayout.LayoutParams(-1, -2))
            addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(10) })
            addView(reader, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(progress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) })
            addView(LinearLayout(context).apply {
                val stacked = context.resources.configuration.fontScale >= 1.5f || context.resources.configuration.screenWidthDp < 600
                orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                for (button in listOf(primary, secondary, close)) {
                    addView(button, LinearLayout.LayoutParams(if (stacked) -1 else -2, -2).apply { if (stacked) topMargin = dp(4) else marginStart = dp(8) })
                    button.nextFocusUpId = reader.id
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        setOnDismissListener { if (returnFocus?.isShown == true && returnFocus.isFocusable) returnFocus.requestFocus() }
    }

    fun performUpdate() {
        if (!UpdateInstaller.permitted(context)) { requestPermission(); return }
        when (controller.state.phase) {
            UpdatePhase.READY -> controller.install()
            UpdatePhase.INSTALLING -> controller.resumeInstallation()
            else -> controller.download()
        }
    }

    fun render(state: UpdateState) {
        val release = state.release
        heading.text = if (release == null) context.getString(R.string.updates_title)
            else context.getString(R.string.updates_version, release.versionName)
        val nextText = context.getString(R.string.updates_installed, BuildConfig.VERSION_NAME) + "\n\n" +
            (release?.notes(AppLanguage.current(context))?.joinToString("\n\n") { "• $it" }
                ?: context.getString(if (state.checked > 0) R.string.updates_no_update else R.string.updates_explanation))
        if (text.text.toString() != nextText) text.text = nextText
        val busy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
        val message = when (state.phase) {
            UpdatePhase.CHECKING -> context.getString(R.string.updates_checking)
            UpdatePhase.DOWNLOADING -> context.getString(R.string.updates_downloading, state.percent)
            UpdatePhase.VERIFYING -> context.getString(R.string.updates_verifying)
            UpdatePhase.INSTALLING -> context.getString(R.string.updates_installing)
            UpdatePhase.READY -> context.getString(R.string.updates_ready)
            UpdatePhase.PAUSED -> context.getString(R.string.updates_paused)
            UpdatePhase.ERROR -> context.getString(when (state.problem) {
                UpdateProblem.RATE_LIMIT -> R.string.updates_error_rate
                UpdateProblem.INVALID -> R.string.updates_error_invalid
                UpdateProblem.STORAGE -> R.string.updates_error_storage
                UpdateProblem.DOWNLOAD -> R.string.updates_error_download
                UpdateProblem.INSTALL -> R.string.updates_error_install
                UpdateProblem.PERMISSION -> R.string.updates_error_permission
                else -> R.string.updates_error_network
            })
            else -> if (release != null) context.getString(R.string.updates_available) else context.getString(R.string.updates_explanation)
        }
        // Progress is visible continuously, but screen readers announce at ten-percent boundaries.
        summary.accessibilityLiveRegion = if (state.phase != UpdatePhase.DOWNLOADING || state.percent % 10 == 0)
            View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        summary.text = message
        primary.visibility = if (release == null) View.GONE else View.VISIBLE
        primary.isEnabled = !busy
        primary.setText(if (state.phase == UpdatePhase.INSTALLING) R.string.updates_resume_install else R.string.updates_now)
        secondary.isEnabled = state.phase !in listOf(UpdatePhase.CHECKING, UpdatePhase.VERIFYING, UpdatePhase.INSTALLING)
        secondary.setText(if (state.phase == UpdatePhase.DOWNLOADING) R.string.updates_cancel else R.string.updates_check)
        secondary.setOnClickListener {
            if (controller.state.phase == UpdatePhase.DOWNLOADING) controller.cancelDownload() else controller.check(true)
        }
        progress.visibility = if (state.phase == UpdatePhase.DOWNLOADING) View.VISIBLE else View.GONE
        progress.progress = state.percent
        val focused = currentFocus
        if (focused != null && (!focused.isEnabled || focused.visibility != View.VISIBLE)) close.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        val config = context.resources.configuration
        window?.setLayout(dp(minOf(config.screenWidthDp - 48, 900)), dp(config.screenHeightDp - 48))
        render(controller.state); reader.requestFocus()
    }
    private fun dp(value: Int) = TvUi.dp(context, value)
}
