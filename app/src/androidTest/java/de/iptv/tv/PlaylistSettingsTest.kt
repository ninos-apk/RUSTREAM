package de.iptv.tv

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PlaylistSettingsTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun emptyLibrary(block: () -> Unit) {
        val store = SourceStore(context)
        val previous = store.load()
        try { store.save(emptyList()); block() } finally { store.save(previous) }
    }

    @Test fun settingsHaveOnePlaylistEntryAndWizardReturnsFromEveryMethod() = emptyLibrary {
        withTvActivity {
            pressFocused(By.text("Quelle hinzufügen"), 10_000)
            assertTrue(device.wait(Until.hasObject(By.text("Playlist hinzufügen").focused(true)), 5_000))
            assertTrue(device.hasObject(By.text("Playlists verwalten")))
            for (hidden in listOf("Datei hinzufügen", "URL hinzufügen", "Xtream anmelden", "EPG importieren", "TMDB")) {
                assertFalse("Settings hides $hidden", device.hasObject(By.text(hidden)))
            }
            pressFocused(By.text("Playlist hinzufügen"))
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Datei hinzufügen").focused(true)), 5_000))
            device.pressDPadDown()
            pressFocused(By.textStartsWith("URL hinzufügen"))
            assertTrue(device.wait(Until.hasObject(By.text("Playlist-Adresse")), 5_000))
            focusDialogView("android:id/button2")
            pressFocused(By.res("android:id/button2"))
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("URL hinzufügen").focused(true)), 5_000))
            device.pressDPadDown()
            pressFocused(By.textStartsWith("Xtream anmelden"))
            assertTrue(device.wait(Until.hasObject(By.desc("Passwort")), 5_000))
            focusDialogView("android:id/button2")
            pressFocused(By.res("android:id/button2"))
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Xtream anmelden").focused(true)), 5_000))
            device.pressDPadUp(); device.pressDPadUp()
            pressFocused(By.textStartsWith("Datei hinzufügen"))
            val noPicker = By.text(AppLanguage.wrap(context).getString(R.string.no_file_picker))
            // Some TV images ship a resolver stub that immediately returns RESULT_CANCELED.
            val alreadyReturned = device.wait(Until.hasObject(By.textStartsWith("Datei hinzufügen").focused(true)), 1_000)
            if (!alreadyReturned) {
                if (!device.wait(Until.hasObject(noPicker), 2_000)) {
                    assertNotEquals("A system file picker replaces the app", context.packageName, device.currentPackageName)
                }
                device.pressBack()
            }
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Datei hinzufügen").focused(true)), 5_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Playlist hinzufügen").focused(true)), 5_000))
        }
    }

    @Test fun emptyManagementOffersTheSamePlaylistWizard() = emptyLibrary {
        withTvActivity {
            pressFocused(By.text("Quelle hinzufügen"), 10_000)
            assertTrue(device.wait(Until.hasObject(By.text("Playlist hinzufügen").focused(true)), 5_000))
            device.pressDPadDown()
            pressFocused(By.text("Playlists verwalten"))
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Noch keine Playlists")), 5_000))
            pressFocused(By.res("android:id/button1"))
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("Datei hinzufügen").focused(true)), 5_000))
        }
    }

    @Test fun narrowRtlWizardAtDoubleTextSizeKeepsActionsAndBackReachable() = emptyLibrary {
        withTvActivity { scenario ->
            lateinit var dialog: AlertDialog
            val choices = listOf(R.string.settings_add_file, R.string.settings_add_url, R.string.settings_xtream)
            scenario.onTvActivity { activity ->
                val configuration = Configuration(activity.resources.configuration).apply {
                    fontScale = 2f; screenWidthDp = 320; screenHeightDp = 360
                    setLayoutDirection(Locale.forLanguageTag("ar"))
                }
                val wrapped = ContextThemeWrapper(activity, R.style.AppTheme).apply { applyOverrideConfiguration(configuration) }
                dialog = TvMenuDialog(wrapped, R.string.settings_add_playlist, listOf(MenuSection(null, listOf(
                    MenuAction(choices[0], R.string.playlist_file_hint) {},
                    MenuAction(choices[1], R.string.playlist_url_hint) {},
                    MenuAction(choices[2], R.string.playlist_xtream_hint) {}
                ))), choices[0], sidePanel = false, introduction = R.string.playlist_method_hint)
                dialog.show()
            }
            try {
                choices.forEachIndexed { index, resource ->
                    val label = AppLanguage.wrap(context).getString(resource)
                    assertTrue(device.wait(Until.hasObject(By.textStartsWith(label).focused(true)), 5_000))
                    instrumentation.runOnMainSync {
                        val button = dialog.window!!.decorView.findViewWithTag<Button>(resource)
                        val bounds = Rect()
                        assertEquals(View.LAYOUT_DIRECTION_RTL, button.layoutDirection)
                        assertTrue("Action is visible", button.getGlobalVisibleRect(bounds))
                        assertEquals("Whole action fits the viewport", button.height, bounds.height())
                        assertTrue(bounds.left >= 0 && bounds.right <= device.displayWidth)
                        for (line in 0 until button.layout.lineCount) assertEquals(0, button.layout.getEllipsisCount(line))
                    }
                    if (index < choices.lastIndex) device.pressDPadDown()
                }
                device.pressDPadDown()
                assertTrue(device.wait(Until.hasObject(By.text("Zurück").focused(true)), 5_000))
                device.executeShellCommand("screencap -p /sdcard/Download/playlist-wizard-rtl.png")
            } finally { instrumentation.runOnMainSync { dialog.dismiss() } }
        }
    }
}
