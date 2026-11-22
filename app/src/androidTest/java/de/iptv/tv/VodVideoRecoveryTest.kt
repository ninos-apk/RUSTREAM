@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VodVideoRecoveryTest {
    @Test fun futureDatedVideoRecoversAtRunningAudioPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        // Optional byte-preserving fixture server on the test host, forwarded with adb reverse.
        // Only a loopback port and a timestamp ever enter device arguments or the playlist.
        val port = arguments.getString("vodFixturePort")?.toInt()
        val freezeMs = arguments.getString("vodFreezeMs")?.toLong() ?: 4_300L
        require(port == null || port in 1..65535)
        val startMs = (freezeMs - 6_000).coerceAtLeast(0)
        val bytes = instrumentation.context.assets.open("vod-pts-jump.mkv").use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            val playlist = File.createTempFile("pts-jump", ".m3u", instrumentation.targetContext.cacheDir)
            val base = if (port == null) server.base else "http://127.0.0.1:$port"
            playlist.writeText("#EXTM3U\n#EXTINF:-1,Test S01E01\n$base/series/test.mkv\n")
            val core = Core()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val revision = runBlocking { core.call("import", "path" to playlist.absolutePath).getLong("revision") }
                withTvActivity { scenario ->
                    lateinit var playback: PlaybackController
                    scenario.onTvActivity { activity ->
                        val view = PlayerView(activity)
                        activity.findViewById<ViewGroup>(android.R.id.content).addView(view, ViewGroup.LayoutParams(320, 180))
                        playback = PlaybackController(activity, core, scope, view, {})
                    }
                    try {
                        runBlocking { withContext(Dispatchers.Main) {
                            playback.start()
                            playback.select(revision, 0, startMs / 1000.0)
                        } }
                        var resumeMs = -1L
                        var firstRendered = -1
                        var recovered = false
                        val deadline = SystemClock.elapsedRealtime() + 45_000
                        while (!recovered && SystemClock.elapsedRealtime() < deadline) {
                            instrumentation.runOnMainSync {
                                val events = playback.diagnostics().getJSONArray("recoveries")
                                if (events.length() == 0) return@runOnMainSync
                                assertEquals("Only one recovery", 1, events.length())
                                val event = events.getJSONObject(0)
                                assertEquals("vod_video_frame_stall", event.getString("reason"))
                                resumeMs = event.getLong("position_ms")
                                val player = playback.player!!
                                if (!player.isPlaying || player.currentPosition < resumeMs + 500) return@runOnMainSync
                                val counters = player.videoDecoderCounters ?: return@runOnMainSync
                                counters.ensureUpdated()
                                if (firstRendered < 0) firstRendered = counters.renderedOutputBufferCount
                                recovered = counters.renderedOutputBufferCount - firstRendered >= 20 &&
                                    player.currentPosition in (resumeMs + 2_000)..(resumeMs + 6_000)
                            }
                            if (!recovered) SystemClock.sleep(100)
                        }
                        assertTrue("Recovery at running position after freeze at $freezeMs ms: $resumeMs",
                            resumeMs in (freezeMs + 7_000)..(freezeMs + 15_000))
                        assertTrue("At least two seconds of new images rendered after recovery", recovered)
                        android.util.Log.i("VodFrameTest", "Recovery at $resumeMs ms, subsequent frame rendering verified")
                    } finally {
                        instrumentation.runOnMainSync { playback.release() }
                    }
                }
            } finally {
                scope.cancel()
                core.close()
                playlist.delete()
            }
        }
    }
}
