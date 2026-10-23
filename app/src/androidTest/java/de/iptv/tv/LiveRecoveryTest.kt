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
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP -> Media3 -> JNI -> Rust recovery, without provider traffic or channel switching. */
@RunWith(AndroidJUnit4::class)
class LiveRecoveryTest {
    @get:org.junit.Rule val remoteUi = RemoteUiRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun temporarilyMissingLiveStreamsRecoverWithoutChangingChannels() = runBlocking {
        val bytes = instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
        for (http in listOf(404, 410)) {
            val server = ServerSocket(0)
            val workers = Executors.newCachedThreadPool()
            val requests = AtomicInteger()
            workers.execute {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        workers.execute {
                            socket.use { client ->
                                try {
                                    client.soTimeout = 5_000
                                    val reader = client.getInputStream().bufferedReader()
                                    while (reader.readLine()?.isNotEmpty() == true) { }
                                    val out = client.getOutputStream()
                                    if (requests.incrementAndGet() <= 3) {
                                        out.write("HTTP/1.1 $http Missing\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                                    } else {
                                        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                        out.write(bytes)
                                    }
                                    out.flush()
                                } catch (_: Exception) { /* Player cancellation closes its connection. */ }
                            }
                        }
                    } catch (_: Exception) { break }
                }
            }
            val context = instrumentation.targetContext
            val playlist = File(context.cacheDir, "live-recovery-$http.m3u").apply {
                writeText("#EXTM3U\n#EXTINF:-1,Temporary Live\nhttp://127.0.0.1:${server.localPort}/live.ts\n")
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
                    runBlocking { withContext(Dispatchers.Main) { playback.start(); playback.select(revision, 0) } }
                    var recovered = false
                    val deadline = SystemClock.elapsedRealtime() + 30_000
                    while (!recovered && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync {
                            recovered = playback.player?.let { it.isPlaying && it.currentPosition >= 500 } == true
                        }
                        if (!recovered) SystemClock.sleep(50)
                    }
                    assertTrue("HTTP $http recovers without reselecting the channel", recovered)
                    assertTrue("Local retries exhausted before a fresh session", requests.get() >= 4)
                    instrumentation.runOnMainSync {
                        assertTrue("A full recovery is recorded", playback.diagnostics().getJSONArray("recoveries").length() > 0)
                        assertEquals("Temporary Live", playback.title)
                        assertFalse(playback.diagnostics().toString().contains("127.0.0.1"))
                        playback.release()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { controller?.release(); scope.cancel() }
                core.close(); playlist.delete(); server.close(); workers.shutdownNow()
            }
        }
    }
}
