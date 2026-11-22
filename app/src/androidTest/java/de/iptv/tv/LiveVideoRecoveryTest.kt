@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LiveVideoRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun liveFreezeRestartsChannelAndConfirmsRenderedVideo() {
        val bytes = instrumentation.context.assets.open("vod-pts-jump.mkv").use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            withChannel("${server.base}/live.ts") { playback ->
                val initial = onMain { playback.player }
                await("live freeze and rendered video after reload") {
                    val events = playback.diagnostics().getJSONArray("recoveries")
                    val reasons = (0 until events.length()).map { events.getJSONObject(it).getString("reason") }
                    playback.player !== initial && "video_frame_stall" in reasons && reasons.lastOrNull() == "recovered" &&
                        events.getJSONObject(events.length() - 1).getLong("video_gap_ms") >= 12_000 &&
                        rendered(playback.player) >= 3
                }
                onMain {
                    val events = playback.diagnostics().getJSONArray("recoveries")
                    val gap = events.getJSONObject(events.length() - 1).getLong("video_gap_ms")
                    android.util.Log.i("LiveRecoveryTiming", "Frozen live channel: observed video gap ${gap}ms")
                }
            }
        }
    }

    @Test fun expiredHlsWindowResyncsInPlaceAndRepeatedFailureFallsBack() {
        val segment = instrumentation.context.assets.open("live-segment.ts").use { it.readBytes() }
        val shift = AtomicInteger()
        val started = SystemClock.elapsedRealtime()
        ArtworkPipelineTest.ImageServer { request ->
            if (request.path.endsWith(".m3u8")) {
                val first = shift.get() + (SystemClock.elapsedRealtime() - started) / 2000
                val playlist = buildString {
                    append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n")
                    append("#EXT-X-MEDIA-SEQUENCE:$first\n#EXT-X-DISCONTINUITY-SEQUENCE:$first\n")
                    for (n in first until first + 8) {
                        if (n > first) append("#EXT-X-DISCONTINUITY\n")
                        append("#EXTINF:2.000,\n$n.ts\n")
                    }
                }
                ArtworkPipelineTest.Reply(200, playlist.toByteArray(), listOf("Content-Type: application/vnd.apple.mpegurl"))
            } else ArtworkPipelineTest.Reply(200, segment, listOf("Content-Type: video/mp2t"))
        }.use { server ->
            withChannel("${server.base}/live.m3u8") { playback ->
                val initial = onMain { checkNotNull(playback.player) }
                fun reasons(): List<String> {
                    val events = playback.diagnostics().getJSONArray("recoveries")
                    return (0 until events.length()).map { events.getJSONObject(it).getString("reason") }
                }
                await("HLS initially rendering") { rendered(initial) >= 20 }
                shift.addAndGet(1000)
                await("real window expiry recovered on original player") {
                    reasons() == listOf("behind_live_window", "recovered") && playback.player === initial
                }
                onMain {
                    val events = playback.diagnostics().getJSONArray("recoveries")
                    val confirmedAfterMs = events.getJSONObject(1).getLong("elapsed_ms") - events.getJSONObject(0).getLong("elapsed_ms")
                    android.util.Log.i("LiveRecoveryTiming", "HLS in-place recovery confirmed after ${confirmedAfterMs}ms")
                }
                shift.addAndGet(1000)
                await("fresh incident gets one new local attempt") { reasons().lastOrNull() == "behind_live_window" }
                // Deliver another failure on Media3's playback thread before new frames can confirm the repair.
                onMain {
                    initial.createMessage { _, _ -> throw ExoPlaybackException.createForSource(
                        IOException("Repeated window expiry"), PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
                    }.send()
                }
                await("repeated expiry reaches Rust backoff and recovers") {
                    val history = reasons()
                    playback.player !== initial && history.count { it == "behind_live_window" } == 2 &&
                        "backend_error" in history && history.lastOrNull() == "recovered" && rendered(playback.player) >= 3
                }
            }
        }
    }

    private fun rendered(player: ExoPlayer?): Int {
        val counters = player?.videoDecoderCounters ?: return 0
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 40_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    private fun withChannel(url: String, block: (PlaybackController) -> Unit) {
        val file = File.createTempFile("live-recovery", ".m3u", instrumentation.targetContext.cacheDir)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            file.writeText("#EXTM3U\n#EXTINF:-1,Synthetic Live\n$url\n")
            Core().use { core ->
                val revision = runBlocking { core.call("import", "path" to file.absolutePath).getLong("revision") }
                withTvActivity { scenario ->
                    lateinit var playback: PlaybackController
                    scenario.onTvActivity { activity ->
                        val view = PlayerView(activity)
                        activity.findViewById<ViewGroup>(android.R.id.content).addView(view, ViewGroup.LayoutParams(320, 180))
                        playback = PlaybackController(activity, core, scope, view, {})
                    }
                    try {
                        runBlocking { withContext(Dispatchers.Main) { playback.start(); playback.select(revision, 0) } }
                        block(playback)
                    } finally { onMain { playback.release() }; scope.cancel() }
                }
            }
        } finally { scope.cancel(); file.delete() }
    }
}
