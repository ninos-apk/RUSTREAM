@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.descendants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FullscreenDpadTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun field(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun pane(activity: MainActivity) = field(activity, "playerPane") as PlayerPane
    private fun playback(activity: MainActivity) = field(activity, "playback") as PlaybackController

    @Test fun verticalSwitchingIsLimitedToFullscreenVideoAndKeepsExistingRemotePaths() {
        val store = SourceStore(context)
        val old = store.load()
        val bytes = instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            val file = File(context.filesDir, "fullscreen-dpad-${System.nanoTime()}.m3u").apply {
                writeText(buildString {
                    append("#EXTM3U\n")
                    for (i in 1..3) append("#EXTINF:-1 group-title=Demo,Remote Live $i\n${server.base}/live$i.ts\n")
                    append("#EXTINF:-1 group-title=Movies,Remote Film\n${server.base}/movie/u/p/1.mp4\n")
                    for ((season, episode) in listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1))
                        append("#EXTINF:-1 group-title=Shows,Remote Show S$season E$episode\n${server.base}/series/u/p/$season-$episode.mp4\n")
                })
            }
            try {
                store.save(listOf(JSONObject().put("kind", "file").put("name", "Fullscreen remote fixture")
                    .put("uri", android.net.Uri.fromFile(file).toString())))
                withTvActivity { scenario ->
                    fun waitFor(message: String, check: (MainActivity) -> Boolean) {
                        val deadline = SystemClock.elapsedRealtime() + 15_000
                        var ok = false
                        while (!ok && SystemClock.elapsedRealtime() < deadline) {
                            scenario.onTvActivity { ok = check(it) }
                            if (!ok) SystemClock.sleep(40)
                        }
                        var state = ""
                        if (!ok) {
                            scenario.onTvActivity { state = " focus=${it.currentFocus?.contentDescription} fullscreen=${pane(it).isFullscreen} controls=${pane(it).controlsVisible}" }
                            device.takeScreenshot(File("/sdcard/Download/fullscreen-dpad-failure.png"))
                        }
                        assertTrue(message + state, ok)
                    }
                    fun activate(label: String) {
                        waitFor(label) { activity -> activity.findViewById<ViewGroup>(android.R.id.content).descendants
                            .any { it.contentDescription == label || (it as? TextView)?.text?.toString() == label } }
                        scenario.onTvActivity { activity ->
                            assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.first {
                                it.contentDescription == label || (it as? TextView)?.text?.toString() == label
                            }.requestFocus())
                        }
                        device.pressDPadCenter()
                    }
                    fun ready(title: String) = waitFor("Playing $title") {
                        playback(it).title == title && playback(it).player != null &&
                            (field(it, "selectionWork") as? kotlinx.coroutines.Job)?.isActive != true
                    }
                    fun unchanged(title: String) {
                        // Allow any incorrectly dispatched asynchronous navigation to complete.
                        SystemClock.sleep(400)
                        scenario.onTvActivity { assertEquals(title, playback(it).title) }
                    }
                    fun fullscreen() {
                        scenario.onTvActivity { pane(it).showControls(true); pane(it).buttons.getValue("fullscreen").requestFocus() }
                        device.pressDPadCenter()
                        waitFor("Fullscreen video focus") { pane(it).isFullscreen && pane(it).video.hasFocus() && !pane(it).controlsVisible }
                    }
                    fun repeatKey(code: Int) {
                        val now = SystemClock.uptimeMillis()
                        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 4))
                        instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
                    }

                    activate("Remote Live 1"); ready("Remote Live 1")
                    waitFor("First stream and preview are ready") { playback(it).player?.isPlaying == true && pane(it).video.isShown }
                    // A list arrow moves focus without changing playback.
                    device.pressDPadDown(); unchanged("Remote Live 1")
                    device.pressDPadRight()
                    waitFor("Right reaches preview video") { pane(it).video.hasFocus() && pane(it).previewFocusVisible }
                    device.pressDPadDown()
                    waitFor("Preview Down opens controls") { !pane(it).isFullscreen && pane(it).buttons.getValue("play").hasFocus() }
                    unchanged("Remote Live 1")
                    device.pressDPadUp()
                    waitFor("Preview Up from bar restores video") { pane(it).video.hasFocus() && !pane(it).controlsVisible }
                    device.pressDPadUp()
                    waitFor("Preview Up reaches settings") { (field(it, "settingsButton") as View).hasFocus() }
                    unchanged("Remote Live 1")
                    fullscreen()
                    device.pressDPadDown(); unchanged("Remote Live 1") // first channel boundary
                    device.pressDPadUp(); ready("Remote Live 2")
                    scenario.onTvActivity { assertTrue(pane(it).isFullscreen); assertTrue(pane(it).video.hasFocus()); assertFalse(pane(it).controlsVisible) }
                    repeatKey(KeyEvent.KEYCODE_DPAD_UP); unchanged("Remote Live 2")
                    device.pressDPadDown(); ready("Remote Live 1")
                    device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_UP); ready("Remote Live 2")
                    device.pressKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN); ready("Remote Live 1")
                    device.pressDPadCenter()
                    waitFor("OK keeps controls accessible") { pane(it).buttons.getValue("play").hasFocus() }
                    scenario.onTvActivity {
                        assertFalse(pane(it).buttons.getValue("play").contentDescription.isNullOrBlank())
                        assertTrue(pane(it).buttons.getValue("play").isShown)
                    }
                    device.pressDPadDown(); unchanged("Remote Live 1")
                    device.pressDPadRight()
                    waitFor("Right navigates live controls") { pane(it).buttons.any { (key, button) -> key != "play" && button.hasFocus() } }
                    device.takeScreenshot(File("/sdcard/Download/fullscreen-dpad-controls.png"))
                    device.pressDPadUp()
                    waitFor("Bar Up closes controls without switching") { pane(it).video.hasFocus() && !pane(it).controlsVisible }
                    unchanged("Remote Live 1")
                    repeatKey(KeyEvent.KEYCODE_DPAD_UP); unchanged("Remote Live 1")
                    device.pressDPadUp(); ready("Remote Live 2")
                    device.pressDPadUp(); ready("Remote Live 3")
                    device.pressDPadUp(); unchanged("Remote Live 3") // last channel boundary
                    device.pressBack()
                    waitFor("Exit restores preview") { !pane(it).isFullscreen && !pane(it).hasFocus() }
                    scenario.onTvActivity { pane(it).video.requestFocus() }
                    device.pressDPadDown(); unchanged("Remote Live 3")

                    activate("Series (1)"); activate("Remote Show"); activate("Remote Show S1 E1")
                    ready("Remote Show S1 E1")
                    fullscreen()
                    device.pressDPadDown(); unchanged("Remote Show S1 E1")
                    device.pressDPadUp(); ready("Remote Show S1 E2")
                    repeatKey(KeyEvent.KEYCODE_DPAD_UP); unchanged("Remote Show S1 E2")
                    device.pressDPadDown(); ready("Remote Show S1 E1")
                    device.pressDPadUp(); ready("Remote Show S1 E2")
                    device.pressDPadUp(); ready("Remote Show S1 E3")
                    device.pressDPadUp(); unchanged("Remote Show S1 E3") // never crosses season
                    scenario.onTvActivity { assertTrue(pane(it).isFullscreen); assertTrue(pane(it).video.hasFocus()) }
                    device.pressBack()
                    scenario.onTvActivity { pane(it).video.requestFocus() }
                    device.pressDPadDown(); unchanged("Remote Show S1 E3")
                    waitFor("Series preview still opens bar") { pane(it).buttons.getValue("play").hasFocus() }

                    activate("Movies (1)"); activate("Remote Film")
                    scenario.onTvActivity { assertTrue("Browsing a movie retains episode key context", pane(it).verticalNavigationEnabled) }
                    waitFor("Movie Play visible") { (field(it, "moviePlay") as View).isShown }
                    scenario.onTvActivity { (field(it, "moviePlay") as View).requestFocus() }
                    device.pressDPadCenter(); ready("Remote Film")
                    fullscreen()
                    device.pressDPadDown()
                    waitFor("Movie Down keeps original controls mapping") { pane(it).buttons.getValue("play").hasFocus() }
                    unchanged("Remote Film")
                    device.pressDPadUp()
                    device.pressDPadUp()
                    waitFor("Movie Up keeps original controls mapping") { pane(it).buttons.getValue("play").hasFocus() }
                    unchanged("Remote Film")
                }
            } finally { store.save(old); file.delete() }
        }
    }
}
