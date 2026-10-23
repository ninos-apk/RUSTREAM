@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekControlsTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun previewAndFullscreenButtonsAccelerateAndMediaKeysShareTheSameGesture() {
        val store = SourceStore(instrumentation.targetContext)
        val old = store.load()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: ExoPlayer? = null
        try {
            store.save(emptyList())
            withTvActivity { scenario ->
                lateinit var pane: PlayerPane
                val targets = mutableListOf<Long>()
                scenario.onTvActivity { activity ->
                    player = ExoPlayer.Builder(activity).build()
                    pane = PlayerPane(activity, scope, {}, { targets.add(it) }).apply {
                        video.player = player; hasSelection = true
                        update(PlaybackStats(seekable = true, durationMs = 7_200_000))
                    }
                    activity.findViewById<ViewGroup>(android.R.id.content).addView(pane, FrameLayout.LayoutParams(-1, -1))
                }
                fun waitFor(message: String, check: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    var ok = false
                    while (!ok && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onTvActivity { ok = check() }
                        if (!ok) SystemClock.sleep(30)
                    }
                    assertTrue(message, ok)
                }
                fun key(down: Long, code: Int, action: Int, repeat: Int = 0) =
                    instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), action, code, repeat))
                for (fullscreen in listOf(false, true)) {
                    scenario.onTvActivity {
                        pane.setFullscreen(fullscreen, false); pane.showControls()
                        pane.buttons.getValue("forward").requestFocus(); targets.clear()
                    }
                    waitFor("Forward button owns focus") { pane.buttons.getValue("forward").hasFocus() && pane.hasWindowFocus() }
                    var down = SystemClock.uptimeMillis()
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
                    waitFor("A tap advances exactly thirty seconds") { targets == listOf(30_000L) }
                    down = SystemClock.uptimeMillis()
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 4)
                    SystemClock.sleep(1_800)
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
                    var count = 0
                    scenario.onTvActivity {
                        assertTrue("Holding accelerates in preview and fullscreen", targets.last() > 70_000)
                        assertFalse(pane.seeking); count = targets.size
                    }
                    SystemClock.sleep(450)
                    scenario.onTvActivity { assertEquals("Release stops further seeks", count, targets.size) }
                    scenario.onTvActivity { pane.buttons.getValue("back").requestFocus(); targets.clear() }
                    down = SystemClock.uptimeMillis()
                    key(down, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN)
                    key(down, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP)
                    waitFor("Rewind clamps at the beginning") { targets == listOf(0L) }
                    scenario.onTvActivity { player!!.seekTo(300_000); targets.clear() }
                    down = SystemClock.uptimeMillis()
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
                    SystemClock.sleep(1_800)
                    key(down, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
                    scenario.onTvActivity {
                        assertEquals(270_000L, targets.first())
                        assertTrue("Holding rewind accelerates too: $targets", targets.last() < 230_000)
                        player!!.seekTo(0)
                    }
                }
                // Exercise Activity routing for both edges, including focus outside the player.
                val field = MainActivity::class.java.getDeclaredField("playerPane").apply { isAccessible = true }
                var original: PlayerPane? = null
                scenario.onTvActivity { activity ->
                    // The idle production controller would otherwise publish empty stats into
                    // the temporary pane every second; this test owns its player and samples.
                    val controller = MainActivity::class.java.getDeclaredField("playback").apply { isAccessible = true }.get(activity)
                    (PlaybackController::class.java.getDeclaredField("ticker").apply { isAccessible = true }.get(controller) as? kotlinx.coroutines.Job)?.cancel()
                    original = field.get(activity) as PlayerPane
                    field.set(activity, pane)
                    pane.buttons.getValue("play").requestFocus(); targets.clear()
                }
                try {
                    val down = SystemClock.uptimeMillis()
                    key(down, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.ACTION_DOWN)
                    SystemClock.sleep(1_800)
                    key(down, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.ACTION_UP)
                    scenario.onTvActivity { assertTrue("Media key accelerates: $targets", targets.last() > 70_000); assertFalse(pane.seeking) }
                    // Losing focus cancels a held button before another view can receive its release.
                    scenario.onTvActivity { pane.buttons.getValue("forward").requestFocus(); targets.clear() }
                    val next = SystemClock.uptimeMillis()
                    key(next, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)
                    waitFor("Gesture starts") { pane.seeking }
                    var count = 0
                    scenario.onTvActivity { pane.buttons.getValue("play").requestFocus(); count = targets.size }
                    SystemClock.sleep(600)
                    key(next, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)
                    scenario.onTvActivity { assertFalse(pane.seeking); assertEquals(count, targets.size) }
                } finally {
                    scenario.onTvActivity { activity -> field.set(activity, original); pane.cancelSeek(); player?.release(); player = null }
                }
            }
        } finally {
            scope.cancel(); instrumentation.runOnMainSync { player?.release() }; store.save(old)
        }
    }
}
