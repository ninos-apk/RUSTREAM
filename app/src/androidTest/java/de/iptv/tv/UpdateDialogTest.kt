package de.iptv.tv

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDialogTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun remoteScrollsNotesAndBackRestoresFocusAtLargeText() {
        withTvActivity { scenario ->
            lateinit var dialog: UpdateDialog
            lateinit var reader: ScrollView
            lateinit var origin: View
            lateinit var controller: UpdateController
            var cleanup: (() -> Unit)? = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                scenario.onTvActivity { activity ->
                    origin = checkNotNull(activity.currentFocus)
                    val large = ContextThemeWrapper(activity, activity.theme).apply {
                        applyOverrideConfiguration(Configuration(activity.resources.configuration).apply { fontScale = 2f })
                    }
                    controller = UpdateController(activity, scope, {}, {})
                    dialog = UpdateDialog(large, controller, origin) { fail("Reading never asks for permission") }
                    cleanup = { dialog.dismiss(); controller.close() }
                    dialog.show()
                    dialog.window!!.setLayout(TvUi.dp(activity, 520), TvUi.dp(activity, 480))
                    val notes = List(12) { "Änderung ${it + 1}: " + "Verbesserte Bedienung auf dem Fernseher. ".repeat(9) }
                    val release = UpdateRelease("v0.5.9", "0.5.9", 20, 23, listOf("arm64-v8a"),
                        "RUSTREAM-IPTV-PLAYER-0.5.9.apk", 100, "a".repeat(64), "b".repeat(64), notes, notes)
                    dialog.render(UpdateState(UpdatePhase.AVAILABLE, release))
                    reader = dialog.findViewById<View>(android.R.id.content).findViewWithTag("update_scroll")
                }
                assertTrue(device.wait(Until.hasObject(By.scrollable(true).focused(true)), 5_000))
                device.pressDPadDown(); android.os.SystemClock.sleep(200)
                instrumentation.runOnMainSync {
                    assertTrue("D-pad scrolls long release notes", reader.scrollY > 0)
                    val text = reader.findViewWithTag<TextView>("update_notes")
                    assertFalse(text.isFocusable)
                    assertFalse(reader.canScrollHorizontally(1))
                    val available = text.width - text.totalPaddingLeft - text.totalPaddingRight
                    assertTrue((0 until text.lineCount).maxOf { text.layout.getLineMax(it) } <= available + 1f)
                    for (id in listOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)) {
                        val button = dialog.findViewById<Button>(id)
                        val bounds = android.graphics.Rect()
                        assertTrue(button.getGlobalVisibleRect(bounds)); assertEquals(button.height, bounds.height())
                        assertTrue(button.createAccessibilityNodeInfo().text.isNotBlank())
                    }
                }
                device.pressDPadRight()
                assertTrue(device.wait(Until.hasObject(By.res("android:id/button1").focused(true)), 5_000))
                device.pressDPadUp()
                assertTrue(device.wait(Until.hasObject(By.scrollable(true).focused(true)), 5_000))
                device.takeScreenshot(java.io.File("/sdcard/Download/updates-large-text.png"))
                device.pressBack(); device.waitForIdle()
                instrumentation.runOnMainSync { assertFalse(dialog.isShowing); assertTrue(origin.hasFocus()) }
            } finally {
                instrumentation.runOnMainSync { cleanup?.invoke() }
                scope.cancel()
            }
        }
    }

    @Test fun settingsOpensUpdaterWithoutAPlaylist() {
        withTvActivity { scenario ->
            scenario.onTvActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("showUpdates").apply { isAccessible = true }.invoke(activity)
            }
            assertTrue(device.wait(Until.hasObject(By.text("App-Updates")), 5_000))
            device.pressBack()
        }
    }
}
