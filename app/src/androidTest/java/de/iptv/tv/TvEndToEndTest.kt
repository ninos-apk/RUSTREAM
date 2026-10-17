@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.core.view.descendants
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real Media3 + Rust/JNI + Activity + D-pad, using only synthetic local provider content. */
@RunWith(AndroidJUnit4::class)
class TvEndToEndTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private fun tab(scenario:ActivityScenario<MainActivity>,title:String){
        scenario.onTvActivity{activity->assertTrue(activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants.filterIsInstance<android.widget.Button>().first{it.text.toString()==title}.requestFocus())}
        assertTrue("Remote section focus: $title",UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).wait(Until.hasObject(By.text(title).focused(true)),5_000))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressDPadCenter()
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun control(scenario: ActivityScenario<MainActivity>, description: String) {
        scenario.onTvActivity { activity ->
            val pane=activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerPane>().first()
            pane.showControls()
            assertTrue(pane.buttons.values.first { it.contentDescription==description }.requestFocus())
        }
        device.pressDPadCenter()
    }

    @Test fun nativeImportSearchEpgAndKeystoreRoundTrip() = runBlocking {
        val core = Core()
        val playlist = File(context.cacheDir,"jni-fixture.m3u").apply { writeText("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",Native News\nhttps://example.test/a.ts") }
        val guide = File(context.cacheDir,"jni-guide.xml").apply { writeText("<tv><programme channel=\"a\" start=\"20261015180000 +0200\" stop=\"20261015190000 +0200\"><title>Fixture EPG</title></programme></tv>") }
        try {
            val revision = core.call("import", "path" to playlist.absolutePath).getLong("revision")
            val rows = core.call("search", "revision" to revision, "query" to "news", "offset" to 0, "limit" to 64)
            assertEquals("Native News", rows.getJSONArray("items").getJSONObject(0).getString("name"))
            core.call("epg_import", "path" to guide.absolutePath)
            val epg = core.call("epg", "revision" to revision, "id" to 0, "now" to 1792080000)
            assertEquals("Fixture EPG", epg.getJSONArray("programmes").getJSONObject(0).getString("title"))
            val store = SourceStore(context)
            val old = store.load()
            try {
                store.save(listOf(JSONObject().put("kind","url").put("url","https://example.test/?password=fixture-secret")))
                assertTrue(store.load()[0].getString("url").contains("fixture-secret"))
                assertFalse(File(context.noBackupFilesDir,"sources.enc").readBytes().toString(Charsets.ISO_8859_1).contains("fixture-secret"))
            } finally { store.save(old) }
        } finally { core.close(); playlist.delete(); guide.delete() }
    }

    @Test fun liveProfileEofTimerAndPendingRetryCancellation() {
        val store = SourceStore(context)
        val old = store.load()
        val bytes = instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            val playlist = File(context.filesDir, "performance-fixture.m3u").apply {
                writeText("#EXTM3U\n#EXTINF:-1,Performance Live\n${server.base}/live.ts\n#EXTINF:20,Performance Retry\n${server.base}/flaky.mp4\n")
            }
            try {
                store.save(listOf(JSONObject().put("kind", "file").put("name", "Performance").put("uri", android.net.Uri.fromFile(playlist).toString())))
                withTvActivity { scenario ->
                    assertTrue(device.wait(Until.hasObject(By.desc("Performance Live").focused(true)), 20_000))
                    val started = SystemClock.elapsedRealtime()
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Performance Live")), 20_000))
                    val startupMs = SystemClock.elapsedRealtime() - started
                    val endedAt = AtomicLong()
                    val loadedAt = AtomicLong()
                    lateinit var controller: PlaybackController
                    var targetMs = 0
                    lateinit var previousPlayer: androidx.media3.exoplayer.ExoPlayer
                    scenario.onTvActivity { activity ->
                        val playback = MainActivity::class.java.getDeclaredField("playback").apply { isAccessible = true }.get(activity) as PlaybackController
                        controller = playback
                        previousPlayer = playback.player!!
                        // Keep the existing persisted choice; verify its actual read-ahead window.
                        targetMs = playback.bufferProfile.targetMs
                        previousPlayer.addListener(object : androidx.media3.common.Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == androidx.media3.common.Player.STATE_ENDED) endedAt.set(SystemClock.elapsedRealtime())
                            }
                        })
                        previousPlayer.seekTo((previousPlayer.duration - 500).coerceAtLeast(0))
                    }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (loadedAt.get() == 0L && SystemClock.elapsedRealtime() < deadline) {
                        scenario.onTvActivity { activity ->
                            val playback = MainActivity::class.java.getDeclaredField("playback").apply { isAccessible = true }.get(activity) as PlaybackController
                            if (playback.player != null && playback.player !== previousPlayer) loadedAt.set(SystemClock.elapsedRealtime())
                        }
                        if (loadedAt.get() == 0L) SystemClock.sleep(20)
                    }
                    assertTrue("Natural live EOF observed", endedAt.get() > 0)
                    val retryMs = loadedAt.get() - endedAt.get()
                    assertTrue("EOF retry uses the subsecond timer: $retryMs ms", retryMs in 0..900)
                    android.util.Log.i("IptvPerformance", JSONObject().put("startup_status_ms", startupMs)
                        .put("eof_reload_ms", retryMs).put("live_target_ms", targetMs).toString())
                    // Enter a normal backoff, then stop before it expires.
                    tab(scenario,"Movies (1)")
                    assertTrue(device.wait(Until.hasObject(By.desc("Performance Retry")), 5_000))
                    scenario.onTvActivity { activity -> activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants.first { it.contentDescription=="Performance Retry" }.requestFocus() }
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Abspielen").focused(true)),5_000))
                    device.pressDPadCenter()
                    var waiting = false
                    val retryDeadline = SystemClock.elapsedRealtime() + 5_000
                    while (!waiting && SystemClock.elapsedRealtime() < retryDeadline) {
                        scenario.onTvActivity { waiting = controller.phase == "waiting" }
                        if (!waiting) SystemClock.sleep(20)
                    }
                    assertTrue("Broken source enters backoff", waiting)
                    runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { controller.stop() } }
                    assertTrue(device.wait(Until.hasObject(By.text("Gestoppt")), 5_000))
                    val requests = server.flakyRequests.get()
                    SystemClock.sleep(2_500)
                    assertEquals("Stop cancels scheduled reload", requests, server.flakyRequests.get())
                }
            } finally { store.save(old); playlist.delete() }
        }
    }

    @Test fun remoteChannelSwitchVodReconnectAndHomeResume() {
        val store = SourceStore(context)
        val old = store.load()
        val bytes = instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
        FixtureServer(bytes).use { server ->
            val playlist = File(context.filesDir,"e2e-fixture.m3u").apply {
                writeText("#EXTM3U\n#EXTINF:-1 group-title=\"Test\",Fixture Live\n${server.base}/live.ts\n#EXTINF:20 group-title=\"Test\",Fixture VOD\n${server.base}/vod.mp4\n#EXTINF:20 group-title=\"Test\",Fixture Reconnect\n${server.base}/flaky.mp4\n#EXTINF:20 group-title=\"Test\",Fixture Disconnect\n${server.base}/cut.mp4\n")
            }
            try {
                store.save(listOf(JSONObject().put("kind","file").put("name","E2E").put("uri", android.net.Uri.fromFile(playlist).toString())))
                withTvActivity { scenario ->
                    assertTrue(device.wait(Until.hasObject(By.desc("Fixture Live")), 20_000))
                    assertTrue("First channel receives remote focus", device.wait(Until.hasObject(By.desc("Fixture Live").focused(true)), 5_000))
                    // Request real focus, then activate exclusively with a remote center key.
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Fixture Live")), 20_000))
                    assertTrue(server.mediaRequests.get() > 0)
                    tab(scenario,"Movies (3)")
                    assertTrue(device.wait(Until.hasObject(By.desc("Fixture VOD")), 5_000))
                    assertFalse(device.hasObject(By.desc("Fixture Live")))
                    scenario.onTvActivity { activity -> activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants.first { it.contentDescription=="Fixture VOD" }.requestFocus() }
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Abspielen").focused(true)),5_000))
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Fixture VOD")), 15_000))
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    assertTrue(device.wait(Until.hasObject(By.text("Pausiert · Fixture VOD")), 5_000))
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    assertTrue(device.hasObject(By.text("Pausiert · Fixture VOD")))
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Fixture Reconnect")), 25_000))
                    assertTrue("Core must retry the broken source", server.flakyRequests.get() >= 3)
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    while (server.cutRequests.get() < 2 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    assertTrue("Mid-stream disconnect must recover", server.cutRequests.get() >= 2)
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Fixture Disconnect")),15_000))
                    device.executeShellCommand("screencap -p /sdcard/Download/iptv-tv-e2e.png")
                    control(scenario,"Vollbild")
                    scenario.onTvActivity { activity ->
                        val root=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        val pane=root.descendants.filterIsInstance<PlayerPane>().first()
                        assertTrue(pane.isFullscreen)
                        assertEquals(root.width,pane.width)
                        assertEquals(root.height,pane.height)
                        val at=IntArray(2); pane.getLocationOnScreen(at)
                        assertEquals(0,at[0]); assertEquals(0,at[1])
                    }
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.desc("Wiedergabesteuerung")),5000))
                    assertTrue(device.wait(Until.hasObject(By.desc("Pause").focused(true)),5000))
                    device.pressDPadCenter()
                    assertTrue(device.wait(Until.hasObject(By.desc("Wiedergabe starten").focused(true)),5000))
                    device.pressBack()
                    scenario.onTvActivity { activity ->
                        val pane=activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerPane>().first()
                        assertTrue(pane.isFullscreen); assertFalse(pane.controlsVisible)
                    }
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.desc("Einstellungen")),5_000))
                    device.pressHome()
                    SystemClock.sleep(1_000)
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Fixture Disconnect")),15_000))
                    control(scenario,"Stopp")
                    assertTrue(device.wait(Until.hasObject(By.text("Gestoppt")),5_000))
                    // Play can restart the last selection after a deliberate stop.
                    control(scenario,"Wiedergabe starten")
                    assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Fixture Disconnect")),15_000))
                    control(scenario,"Stopp")
                    device.pressBack()
                    device.pressBack()
                    // Back closes controls, returns to film details, then the grid and the app exit.
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.desc("Fixture VOD")),5_000))
                    device.pressBack()
                    assertTrue(device.wait(Until.gone(By.pkg("de.iptv.tv")),5_000))
                }
            } finally { store.save(old); playlist.delete() }
        }
    }

    @Test fun contentSectionsAndIndependentCategoryAndChannelSearch() {
        val store = SourceStore(context)
        val old = store.load()
        val playlist = File(context.filesDir, "sections-fixture.m3u").apply {
            writeText("#EXTM3U\n" +
                "#EXTINF:-1 group-title=Drama,Fixture News\nhttps://example.test/live/u/p/1.ts\n" +
                "#EXTINF:-1 group-title=Drama,Fixture Movie HD\nhttps://example.test/movie/u/p/2.mp4\n" +
                "#EXTINF:-1 group-title=Drama,Fixture Movie SD\nhttps://example.test/movie/u/p/3.mp4\n" +
                "#EXTINF:-1 group-title=Comedy,Fixture Funny\nhttps://example.test/movie/u/p/4.mp4\n" +
                "#EXTINF:-1 group-title=Drama,Fixture Show S01E01\nhttps://example.test/series/u/p/5.mp4\n")
        }
        try {
            store.save(listOf(JSONObject().put("kind", "file").put("name", "Sections").put("uri", android.net.Uri.fromFile(playlist).toString())))
            withTvActivity { scenario ->
                assertTrue("Initial library focus is ready before remote section switching",device.wait(Until.hasObject(By.desc("Fixture News").focused(true)),20_000))
                assertFalse(device.hasObject(By.desc("Fixture Movie HD")))
                tab(scenario,"Movies (3)")
                assertTrue(device.wait(Until.hasObject(By.desc("Fixture Movie HD")), 5_000))
                assertFalse(device.hasObject(By.text("Fixture News")))
                device.findObject(By.desc("Kategorie suchen")).text = "com"
                assertTrue(device.wait(Until.hasObject(By.desc("Comedy (1)")), 5_000))
                assertFalse(device.hasObject(By.desc("Drama (2)")))
                // Category search narrows only the category list, not the channel results.
                assertTrue(device.hasObject(By.desc("Fixture Movie HD")))
                device.findObject(By.desc("Comedy (1)")).click()
                device.pressDPadCenter()
                assertTrue(device.wait(Until.gone(By.desc("Fixture Movie HD")), 5_000))
                assertTrue(device.hasObject(By.desc("Fixture Funny")))
                device.findObject(By.desc("Filme suchen")).text = "missing"
                assertTrue(device.wait(Until.hasObject(By.text("Keine Treffer für „missing“.")), 5_000))
                assertTrue(device.hasObject(By.text("Suche zurücksetzen")))
                device.findObject(By.desc("Filme suchen")).text = ""
                assertTrue(device.wait(Until.hasObject(By.desc("Fixture Funny")), 5_000))
                device.findObject(By.desc("Kategorie suchen")).text = ""
                device.findObject(By.desc("Alle Kategorien")).click()
                device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.desc("Fixture Movie HD")), 5_000))
                device.findObject(By.desc("Filme suchen")).text = "HD"
                assertTrue(device.wait(Until.gone(By.desc("Fixture Movie SD")), 5_000))
                assertTrue(device.hasObject(By.desc("Fixture Movie HD")))
                tab(scenario,"Series (1)")
                assertTrue(device.wait(Until.hasObject(By.desc("Fixture Show")), 5_000))
                assertFalse(device.hasObject(By.desc("Fixture Movie HD")))
                scenario.onTvActivity { activity ->
                    val field = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        .descendants.filterIsInstance<android.widget.EditText>()
                        .first { it.contentDescription == "Serien / Episoden suchen" }
                    assertEquals("", field.text.toString())
                }
                assertTrue(device.hasObject(By.desc("Drama (1)")))
                device.executeShellCommand("screencap -p /sdcard/Download/iptv-tv-sections.png")
                tab(scenario,"Live (1)")
                assertTrue(device.wait(Until.hasObject(By.text("Fixture News")), 5_000))
            }
        } finally { store.save(old); playlist.delete() }
    }

    @Test fun compactListsSettingsCreditAndAutomaticPaging() {
        val store=SourceStore(context)
        val old=store.load()
        val playlist=File(context.filesDir,"paging-fixture.m3u").apply {
            writeText(buildString {
                append("#EXTM3U\n")
                repeat(140) { i->append("#EXTINF:-1 group-title=Demo,Sender ${i.toString().padStart(3,'0')}\nhttps://example.test/live/u/p/$i.ts\n") }
            })
        }
        try {
            store.save(listOf(JSONObject().put("kind","file").put("name","Paging").put("uri",android.net.Uri.fromFile(playlist).toString())))
            withTvActivity { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("Sender 000")),20_000))
                assertTrue(device.hasObject(By.text("developed by Ninos Oshana")))
                assertFalse(device.hasObject(By.text("+ URL")))
                assertFalse(device.hasObject(By.text("Pause / Play")))
                val first=device.findObject(By.text("Sender 000")).visibleBounds
                assertTrue("Compact list row",first.height()<=80)
                scenario.onTvActivity { activity ->
                    val root=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    assertTrue(root.descendants.first { it.contentDescription=="Einstellungen" }.requestFocus())
                }
                device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Quellen verwalten")),5000))
                assertTrue(device.hasObject(By.text("URL hinzufügen")))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.desc("Einstellungen").focused(true)),5000))
                fun scrollTo(position: Int) { scenario.onTvActivity { activity ->
                    val list=activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants
                        .filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.contentDescription=="Eintragsliste" }
                    list.scrollToPosition(position)
                } }
                fun waitForRows(expected: Int) {
                    val deadline=SystemClock.elapsedRealtime()+5000
                    var count=0
                    while(count<expected && SystemClock.elapsedRealtime()<deadline) {
                        scenario.onTvActivity { activity ->
                            count=activity.findViewById<android.view.ViewGroup>(android.R.id.content).descendants
                                .filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first { it.contentDescription=="Eintragsliste" }.adapter!!.itemCount
                        }
                        if(count<expected) SystemClock.sleep(100)
                    }
                    assertTrue("Next page appended: $count / $expected",count>=expected)
                }
                scrollTo(60)
                waitForRows(128)
                scrollTo(64)
                assertTrue(device.wait(Until.hasObject(By.text("Sender 064")),5000))
                scrollTo(124)
                waitForRows(140)
                scrollTo(139)
                assertTrue(device.wait(Until.hasObject(By.text("Sender 139")),5000))
                device.findObject(By.desc("Sender suchen")).text="Sender 139"
                assertTrue(device.wait(Until.hasObject(By.text("1 Eintrag")),5000))
                assertTrue(device.hasObject(By.text("Sender 139")))
            }
        } finally { store.save(old); playlist.delete() }
    }

    @Test fun androidHttpImportRedirectXtreamAndXmltv() = runBlocking {
        val core = Core()
        val loader = SourceLoader(context)
        FixtureServer(byteArrayOf()).use { server ->
            try {
                val imported = loader.load(JSONObject().put("kind","url").put("url","${server.base}/redirect"),core)
                val selected = core.call("select","revision" to imported.getLong("revision"),"id" to 0)
                assertEquals("${server.base}/vod.mp4",selected.getString("url"))
                val xtream = loader.load(JSONObject().put("kind","xtream").put("server",server.base).put("username","fixture-user").put("password","fixture-pass").put("vod",true),core)
                assertEquals(2,xtream.getInt("count"))
                loader.load(JSONObject().put("kind","url").put("url","${server.base}/guide.xml"),core,true)
                val epg = core.call("epg","revision" to xtream.getLong("revision"),"id" to 0,"now" to 1792080000)
                assertEquals("Fixture EPG",epg.getJSONArray("programmes").getJSONObject(0).getString("title"))
            } finally { loader.close(); core.close() }
        }
    }
}

internal class FixtureServer(private val bytes: ByteArray, private val includeSeries:Boolean=false) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val workers = Executors.newCachedThreadPool()
    val mediaRequests = AtomicInteger()
    val flakyRequests = AtomicInteger()
    val cutRequests = AtomicInteger()
    val conditionalRequests = AtomicInteger()
    val base = "http://127.0.0.1:${socket.localPort}"
    init { workers.execute { while (!socket.isClosed) { try { val client = socket.accept(); workers.execute { serve(client) } } catch (_: Exception) { break } } } }
    private fun serve(client: Socket) {
        client.use {
            try {
                client.soTimeout = 10_000
                val reader = client.getInputStream().bufferedReader()
                val request = reader.readLine() ?: return
                val path = request.split(' ').getOrNull(1) ?: return
                var offset = 0
                var ranged = false
                var conditional = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:",true)) { ranged = true; offset = line.substringAfter("bytes=").substringBefore('-').toIntOrNull() ?: 0 }
                    if(line.startsWith("If-None-Match:",true))conditional=true
                }
                val out = client.getOutputStream()
                if(path.startsWith("/cached.m3u")){
                    if(conditional){conditionalRequests.incrementAndGet();out.write("HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n".toByteArray())}
                    else{val body="#EXTM3U\n#EXTINF:20,Cached VOD\n${base}/vod.mp4\n".toByteArray();out.write("HTTP/1.1 200 OK\r\nETag: \"fixture-1\"\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(body)}
                    return
                }
                if (path.startsWith("/redirect")) {
                    out.write("HTTP/1.1 302 Found\r\nLocation: /playlist.m3u\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
                }
                val text = when {
                    path.startsWith("/playlist.m3u") -> "#EXTM3U\n#EXTINF:20,Redirect VOD\nvod.mp4\n"
                    path.startsWith("/guide.xml") -> "<tv><programme channel=\"a\" start=\"20261015180000 +0200\" stop=\"20261015190000 +0200\"><title>Fixture EPG</title></programme></tv>"
                    path.startsWith("/player_api.php") -> when {
                        path.contains("get_series_categories") -> if(includeSeries)"[{\"category_id\":\"1\",\"category_name\":\"Fixture\"}]" else "[]"
                        path.contains("get_series_info") -> "{\"episodes\":{\"1\":[{\"id\":11,\"title\":\"First episode\",\"episode_num\":1,\"container_extension\":\"mp4\"}],\"2\":[{\"id\":12,\"title\":\"Second season episode\",\"episode_num\":1,\"container_extension\":\"mp4\"}]}}"
                        path.contains("get_series") -> if(includeSeries)"[{\"series_id\":7,\"name\":\"Synthetic show\",\"category_id\":\"1\"}]" else "[]"
                        path.contains("get_live_categories") || path.contains("get_vod_categories") -> "[{\"category_id\":\"1\",\"category_name\":\"Fixture\"}]"
                        path.contains("get_live_streams") || path.contains("get_vod_streams") -> "[{\"stream_id\":1,\"name\":\"Fixture\",\"category_id\":\"1\",\"epg_channel_id\":\"a\",\"container_extension\":\"mp4\"}]"
                        else -> "{\"user_info\":{\"auth\":1,\"status\":\"Active\"}}"
                    }
                    else -> null
                }
                if (text != null) {
                    val data = text.toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(data); return
                }
                mediaRequests.incrementAndGet()
                if (path.startsWith("/flaky") && flakyRequests.incrementAndGet() <= 2) {
                    out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
                }
                offset = offset.coerceIn(0,bytes.size)
                val range = if (ranged) "Content-Range: bytes $offset-${bytes.size-1}/${bytes.size}\r\n" else ""
                out.write("HTTP/1.1 ${if (ranged) "206 Partial Content" else "200 OK"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${range}Content-Length: ${bytes.size-offset}\r\nConnection: close\r\n\r\n".toByteArray())
                if (path.startsWith("/cut.mp4") && cutRequests.incrementAndGet() == 1) {
                    // Advertise the complete body, deliver enough media to start, then break the socket.
                    val end = 700_000.coerceAtMost(bytes.size)
                    var written = offset
                    while (written < end) {
                        val length = 32_768.coerceAtMost(end-written)
                        out.write(bytes,written,length); out.flush(); written += length
                        SystemClock.sleep(150)
                    }
                    return
                }
                out.write(bytes,offset,bytes.size-offset); out.flush()
            } catch (_: Exception) { /* Client deliberately cancels requests on channel switch. */ }
        }
    }
    override fun close() { socket.close(); workers.shutdownNow() }
}

