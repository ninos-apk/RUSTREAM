@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL

/** Remote settings plus opt-in long-video coverage through the production controller and Rust core. */
@RunWith(AndroidJUnit4::class)
class BufferProfileTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)

    @Test fun customLiveChoicesPersistAndBackDoesNotChangeSelection() {
        val preferences = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        val old = preferences.getString("liveBufferProfile", null)
        preferences.edit().putString("liveBufferProfile", "balanced").commit()
        try {
            withTvActivity { scenario ->
                fun open() = scenario.onTvActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("bufferDialog")
                        .apply { isAccessible = true }.invoke(activity)
                }
                fun choose(text: String) {
                    // Native single-choice lists keep input focus on the ListView, with a selected row.
                    assertTrue("Selected row: $text", device.wait(Until.hasObject(By.text(text).selected(true)), 5_000))
                    device.pressDPadCenter()
                }
                open()
                assertTrue(device.wait(Until.hasObject(By.text("Ausgewogen · 3 s / 32 MiB").selected(true)), 5_000))
                device.pressDPadDown(); device.pressDPadDown()
                choose("Benutzerdefiniert …")
                for ((index, seconds) in listOf(10, 15, 20).withIndex()) {
                    if (index > 0) {
                        open()
                        choose("Benutzerdefiniert · ${seconds - 5} s")
                        device.pressDPadDown()
                    }
                    choose("$seconds s / ${seconds * 6} MB")
                    assertEquals("custom_$seconds", preferences.getString("liveBufferProfile", null))
                    scenario.onTvActivity {
                        val controller = field(it, "playback") as PlaybackController
                        assertEquals(seconds * 1_000, controller.bufferProfile.targetMs)
                    }
                }
                scenario.recreate()
                scenario.onTvActivity {
                    assertEquals(PlaybackBufferProfile.CUSTOM_20, (field(it, "playback") as PlaybackController).bufferProfile)
                }
                open()
                choose("Benutzerdefiniert · 20 s")
                assertTrue(device.wait(Until.hasObject(By.text("20 s / 120 MB").checked(true)), 5_000))
                device.pressDPadUp()
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("Benutzerdefiniert · 20 s").checked(true)), 5_000))
                assertEquals("custom_20", preferences.getString("liveBufferProfile", null))
                device.takeScreenshot(File("/sdcard/Download/buffer-profiles.png"))
                device.pressDPadUp()
                choose("Stabil · 6 s / 64 MiB")
                assertEquals("stable", preferences.getString("liveBufferProfile", null))
            }
        } finally {
            preferences.edit().apply { if (old == null) remove("liveBufferProfile") else putString("liveBufferProfile", old) }.commit()
        }
    }

    @Test fun vodBuildsTwoMinutesRefillsAndHandlesSeekNearEnd() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("bufferFixtureUrl")
        assumeTrue("Supply a local five-minute video", url?.startsWith("http://127.0.0.1:") == true)
        val connection = URL(url!!).openConnection().apply { connectTimeout = 10_000; readTimeout = 10_000 }
        val bytes = connection.getInputStream().use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            val playlist = File(context.cacheDir, "buffer-reserve.m3u").apply {
                writeText("#EXTM3U\n#EXTINF:300,Reserve Film\n${server.base}/movie/reserve.mp4\n#EXTINF:300,Reserve Series S01E01\n${server.base}/series/reserve.mp4\n#EXTINF:300,Stalled Film\n${server.base}/stall.mp4\n")
            }
            val core = Core()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            var controller: PlaybackController? = null
            try {
                val revision = core.call("import", "path" to playlist.absolutePath).getLong("revision")
                withTvActivity { scenario ->
                    scenario.onTvActivity { activity ->
                        val video = PlayerView(activity)
                        activity.findViewById<ViewGroup>(android.R.id.content).addView(video, ViewGroup.LayoutParams(320, 180))
                        controller = PlaybackController(activity, core, scope, video, {})
                    }
                    val playback = checkNotNull(controller)
                    fun waitFor(message: String, condition: () -> Boolean) {
                        val deadline = SystemClock.elapsedRealtime() + 20_000
                        var done = false
                        while (!done && SystemClock.elapsedRealtime() < deadline) {
                            instrumentation.runOnMainSync { done = condition() }
                            if (!done) SystemClock.sleep(40)
                        }
                        assertTrue(message, done)
                    }
                    runBlocking {
                        withContext(Dispatchers.Main) {
                            playback.bufferProfile = PlaybackBufferProfile.CUSTOM_20
                            playback.start()
                            playback.select(revision, 0)
                        }
                    }
                    waitFor("VOD loads about two minutes instead of stopping at thirty seconds") {
                        playback.player!!.let { it.totalBufferedDuration >= 115_000 && !it.isLoading }
                    }
                    instrumentation.runOnMainSync {
                        // Progressive sources check loading in byte blocks; this low-bitrate fixture
                        // can overshoot the time target by part of a block (about 25 seconds).
                        assertTrue("Actual read-ahead: ${playback.player!!.totalBufferedDuration} ms", playback.player!!.totalBufferedDuration < 170_000)
                        assertEquals(Player.STATE_READY, playback.player!!.playbackState)
                        playback.player!!.seekTo(90_000)
                    }
                    waitFor("Dropping below one minute resumes loading towards two minutes") {
                        playback.player!!.let { it.bufferedPosition >= 205_000 && !it.isLoading }
                    }
                    instrumentation.runOnMainSync { playback.player!!.seekTo(285_000) }
                    waitFor("Near the end the remaining fifteen seconds are enough to resume") {
                        playback.player!!.let { it.playbackState == Player.STATE_READY && it.bufferedPosition >= 299_000 }
                    }
                    runBlocking { withContext(Dispatchers.Main) { playback.select(revision, 1) } }
                    waitFor("Series use the same two-minute reserve independently of the live profile") {
                        playback.player!!.let { it.totalBufferedDuration >= 115_000 && !it.isLoading }
                    }
                    runBlocking { withContext(Dispatchers.Main) { playback.select(revision, 2) } }
                    waitFor("The stalled transfer starts playback with a low reserve") {
                        playback.player!!.let { it.isPlaying && it.currentPosition >= 2_000 && it.totalBufferedDuration < 60_000 }
                    }
                    var stalled: androidx.media3.exoplayer.ExoPlayer? = null
                    var positionBeforeRetry = 0L
                    instrumentation.runOnMainSync {
                        stalled = playback.player
                        positionBeforeRetry = playback.player!!.currentPosition
                    }
                    waitFor("A stalled download retries before the existing reserve is exhausted") {
                        playback.player!==stalled && server.stallRequests.get()>=2 && playback.player?.isPlaying==true
                    }
                    instrumentation.runOnMainSync {
                        assertTrue("Recovery resumes from the reported position", playback.player!!.currentPosition >= positionBeforeRetry)
                        assertTrue("Recovery happens before the initial reserve runs out", playback.player!!.currentPosition < 50_000)
                    }
                    instrumentation.runOnMainSync { playback.release() }
                }
            } finally {
                withContext(Dispatchers.Main) { controller?.release(); scope.cancel() }
                core.close()
                playlist.delete()
            }
        }
    }
}
