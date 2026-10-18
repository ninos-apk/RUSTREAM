package de.iptv.tv

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real D-pad reading, dialog actions and native accessibility scroll actions. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDialogTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun reader() = WindowInspector.getGlobalWindowViews()
        .firstNotNullOf { it.findViewWithTag<ScrollView>("diagnostics_scroll")?.takeIf { view -> view.isShown } }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            onMain { done = condition() }
            if (!done) SystemClock.sleep(40)
        }
        assertTrue(message, done)
    }

    @Test fun realReportScrollsCopiesWithoutClosingAndRestoresFocus() {
        withTvActivity { scenario ->
            lateinit var origin: View
            scenario.onTvActivity { activity ->
                origin = checkNotNull(activity.currentFocus)
                MainActivity::class.java.getDeclaredMethod("diagnosticsDialog")
                    .apply { isAccessible = true }.invoke(activity)
            }
            assertTrue(device.wait(Until.hasObject(By.desc("Diagnosebericht · Text").focused(true)), 5_000))
            lateinit var scroll: ScrollView
            lateinit var expected: String
            onMain {
                scroll = reader()
                val text = scroll.findViewWithTag<TextView>("diagnostics_text")
                expected = text.text.toString()
                assertTrue(expected.contains("app_version"))
                assertFalse("Text cursor cannot steal D-pad navigation", text.isFocusable)
                assertTrue(scroll.canScrollVertically(1))
            }
            device.pressDPadDown()
            waitFor("D-pad down scrolls the actual report") { scroll.scrollY > 0 }
            var position = 0
            onMain { position = scroll.scrollY }
            device.pressDPadUp()
            waitFor("D-pad up scrolls towards the beginning") { scroll.scrollY < position }
            device.pressDPadDown()
            waitFor("Report is scrolled again") { scroll.scrollY > 0 }
            device.pressDPadRight()
            pressFocused(By.res("android:id/button1"))
            onMain {
                assertTrue("Copy leaves the report open", scroll.isShown)
                val clipboard = instrumentation.targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
            device.pressDPadUp()
            waitFor("Up from the actions returns to the report") { scroll.hasFocus() }
            var atEnd = false
            repeat(60) {
                onMain { atEnd = !scroll.canScrollVertically(1) }
                if (!atEnd) device.pressDPadDown()
            }
            waitFor("The final report line is reachable") { !scroll.canScrollVertically(1) }
            device.takeScreenshot(File("/sdcard/Download/diagnostics-scrolled.png"))
            device.pressBack()
            waitFor("Back closes the dialog and restores the original focus") { !scroll.isShown && origin.hasFocus() }
        }
    }

    @Test fun largeTextWrapsAndNativeAccessibilityActionsReachBothEnds() {
        val report = JSONObject().apply {
            repeat(160) { put("diagnostic_$it", "A long diagnostic value ".repeat(8)) }
            put("end_of_report", "Last line")
        }.toString(2)
        withTvActivity { scenario ->
            lateinit var dialog: DiagnosticsDialog
            lateinit var scroll: ScrollView
            lateinit var origin: View
            scenario.onTvActivity { activity ->
                origin = checkNotNull(activity.currentFocus)
                val largeContext = ContextThemeWrapper(activity, activity.theme).apply {
                    applyOverrideConfiguration(Configuration(activity.resources.configuration).apply { fontScale = 2f })
                }
                dialog = DiagnosticsDialog(largeContext, report, origin)
                dialog.show()
                // Also exercise a narrower reader, with fixed actions outside its scroll viewport.
                dialog.window!!.setLayout(TvUi.dp(activity, 320), TvUi.dp(activity, 560))
            }
            waitFor("The large-text reader is laid out and focused") {
                scroll = reader()
                scroll.hasFocus() && scroll.height > 0
            }
            device.takeScreenshot(File("/sdcard/Download/diagnostics-large-text.png"))
            onMain {
                val text = scroll.findViewWithTag<TextView>("diagnostics_text")
                assertEquals(2f, text.resources.configuration.fontScale)
                assertEquals(report, text.createAccessibilityNodeInfo().text.toString())
                assertFalse(scroll.canScrollHorizontally(1))
                val availableWidth = text.width - text.totalPaddingLeft - text.totalPaddingRight
                val widestLine = (0 until text.lineCount).maxOf { text.layout.getLineMax(it) }
                assertTrue("Visible lines fit: $widestLine / $availableWidth px", widestLine <= availableWidth + 1f)
                val node = scroll.createAccessibilityNodeInfo()
                assertEquals(ScrollView::class.java.name, node.className.toString())
                assertTrue(node.isScrollable)
                assertTrue(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
                assertTrue(scroll.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                for (button in listOf(android.R.id.button1, android.R.id.button2)) {
                    val view = dialog.findViewById<Button>(button)
                    assertTrue("Actions have native readable names", view.createAccessibilityNodeInfo().text.isNotBlank())
                    val bounds = android.graphics.Rect()
                    assertTrue(view.getGlobalVisibleRect(bounds))
                    assertEquals("Actions remain fully visible with large text", view.height, bounds.height())
                }
            }
            waitFor("Accessibility scroll moves the text") { scroll.scrollY > 0 }
            onMain { scroll.fullScroll(View.FOCUS_DOWN) }
            waitFor("Large text can reach the end") { !scroll.canScrollVertically(1) }
            onMain { assertTrue(scroll.createAccessibilityNodeInfo().actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) }
            device.takeScreenshot(File("/sdcard/Download/diagnostics-large-text.png"))
            onMain { assertTrue(scroll.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)) }
            waitFor("Accessibility can return from the end") { scroll.canScrollVertically(1) }
            onMain { dialog.dismiss() }
            waitFor("Dismiss restores focus with large text too") { origin.hasFocus() }
        }
    }
}
