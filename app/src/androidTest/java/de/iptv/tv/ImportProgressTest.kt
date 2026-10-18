package de.iptv.tv

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class ImportProgressTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun source(url:String)=JSONObject().put("kind","url").put("url",url).put("name","Progress fixture")
    private fun startup(activity:MainActivity)=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<StartupPane>().first()
    private fun withImportActivity(block:(ActivityScenario<MainActivity>)->Unit) {
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity:MainActivity
        scenario.onActivity { activity=it }
        try { block(scenario) } finally { finishTvActivity(activity);scenario.close() }
    }

    @Test fun measuredAndUnknownDownloadsNeverGuessTheirTotals():Unit=runBlocking {
        PlaylistProgressServer(12_000,2).use { server ->
            for(path in listOf("known","unknown")) {
                val source=source("${server.base}/$path.m3u")
                val events=CopyOnWriteArrayList<ImportProgress>()
                try {
                    SourceLoader(context).use { loader -> Core().use { core ->
                        loader.progress={events.add(it)}
                        val result=loader.load(source,core)
                        assertEquals(12_000,result.getInt("count"))
                    } }
                    val download=events.filter { it.message==R.string.progress_downloading }
                    assertTrue(download.any { it.completed>0 })
                    if(path=="known") {
                        assertTrue(download.any { it.perMille in 1..999 })
                        assertEquals(server.bytes.size.toLong(),download.last().total)
                        assertEquals(1000,download.last().perMille)
                    } else assertTrue("No percentage when Content-Length is absent",download.all { it.total==null && it.perMille==null })
                    assertTrue("Rust reports measured processing progress",events.any { it.message==R.string.progress_parsing && it.perMille in 0..999 })
                    assertEquals(R.string.progress_opening,events.last().message)
                } finally { CatalogStore(context).remove(source) }
            }
        }
    }

    @Test fun foregroundImportShowsProgressUntilTheFirstChannelIsReady() {
        val store=SourceStore(context);val old=store.load()
        PlaylistProgressServer(30_000,35).use { server ->
            val source=source("${server.base}/known.m3u")
            try {
                AppLanguage.save(context,"en")
                store.save(emptyList())
                withImportActivity { scenario ->
                    pressFocused(By.text("Add source"),10_000)
                    assertTrue(device.wait(Until.hasObject(By.text("Manage sources").focused(true)),5_000))
                    device.pressDPadDown();device.pressDPadDown()
                    pressFocused(By.text("Add URL"))
                    val addressLabel=AppLanguage.wrap(context).getString(R.string.field_url)
                    assertTrue(device.wait(Until.hasObject(By.desc(addressLabel)),5_000))
                    device.findObject(By.desc("Name")).text="Progress fixture"
                    device.findObject(By.desc(addressLabel)).text=source.getString("url")
                    focusDialogView("android:id/button1");pressFocused(By.res("android:id/button1"))
                    assertTrue(device.wait(Until.hasObject(By.text("Importing playlist")),10_000))
                    assertFalse("Source form does not cover import progress",device.hasObject(By.res("android:id/button1")))
                    assertFalse("There is no Cancel button",device.hasObject(By.text("Cancel")))
                    val deadline=SystemClock.elapsedRealtime()+15_000
                    var measured=false
                    while(!measured && SystemClock.elapsedRealtime()<deadline) {
                        scenario.onActivity { activity ->
                            val root=startup(activity)
                            val bar=root.descendants.filterIsInstance<ProgressBar>().firstOrNull()
                            measured=bar!=null && !bar.isIndeterminate && bar.progress in 10..900
                            if(measured) {
                                assertFalse("Progress never adds a remote stop",bar!!.isFocusable)
                                assertTrue(root.descendants.filterIsInstance<TextView>().any { it.text.startsWith("Step 1 of 3") })
                            }
                        }
                        if(!measured)SystemClock.sleep(40)
                    }
                    assertTrue("Measured download is visible in the foreground",measured)
                    device.executeShellCommand("screencap -p /sdcard/Download/import-progress-download.png")
                    assertTrue(device.wait(Until.hasObject(By.text("Processing entries …")),20_000))
                    device.executeShellCommand("screencap -p /sdcard/Download/import-progress-processing.png")
                    assertTrue(device.wait(Until.hasObject(By.desc("Progress 0").focused(true)),40_000))
                    scenario.onActivity { assertEquals("Loading screen closes after successful import",android.view.View.GONE,startup(it).visibility) }
                }
            } finally { store.save(old);CatalogStore(context).remove(source) }
        }
    }

    @Test fun reducedMotionKeepsInlineProgressVisibleWithoutADialogOrCancelButton() {
        val store=SourceStore(context);val old=store.load()
        val oldMotion=device.executeShellCommand("settings get global animator_duration_scale").trim()
        PlaylistProgressServer(30_000,35).use { server ->
            val source=source("${server.base}/known.m3u")
            try {
                device.executeShellCommand("settings put global animator_duration_scale 0")
                store.save(listOf(source))
                withImportActivity { scenario ->
                    assertTrue(device.wait(Until.hasObject(By.text("Playlist importieren")),10_000))
                    assertFalse(device.hasObject(By.text("Abbrechen")))
                    assertFalse(device.hasObject(By.res("android:id/button2")))
                    scenario.onActivity { activity ->
                        val indicator=ImportProgressView(activity)
                        indicator.update(ImportProgress(R.string.progress_parsing,500,1000))
                        val bar=indicator.descendants.filterIsInstance<ProgressBar>().first()
                        assertFalse(bar.isIndeterminate);assertEquals(500,bar.progress)
                        indicator.update(ImportProgress(R.string.progress_parsing))
                        assertFalse("No indeterminate animation with motion disabled",bar.isIndeterminate)
                        assertEquals(android.view.View.VISIBLE,bar.visibility)
                        assertTrue(indicator.descendants.filterIsInstance<TextView>().any { it.text.contains("Schritt 2 von 3") })
                    }
                }
            } finally {
                device.executeShellCommand("settings put global animator_duration_scale ${oldMotion.toFloatOrNull() ?: 1f}")
                store.save(old);CatalogStore(context).remove(source)
            }
        }
    }
}

/** Deliberately slow, entirely synthetic playlist; no media is played or contacted. */
private class PlaylistProgressServer(count:Int,private val pause:Long):AutoCloseable {
    private val socket=ServerSocket(0)
    private val workers=Executors.newCachedThreadPool()
    val base="http://127.0.0.1:${socket.localPort}"
    val bytes=buildString {
        appendLine("#EXTM3U")
        repeat(count) { appendLine("#EXTINF:-1 group-title=Progress,Progress $it");appendLine("https://example.test/live/u/p/$it.ts") }
    }.toByteArray()
    init { workers.execute { while(!socket.isClosed)try {
        val client=socket.accept()
        workers.execute { client.use { try {
            val reader=client.getInputStream().bufferedReader()
            val request=reader.readLine().orEmpty()
            while(reader.readLine()?.isNotEmpty()==true){}
            val size=if(request.contains("/unknown"))"" else "Content-Length: ${bytes.size}\r\n"
            val output=client.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\n${size}Connection: close\r\n\r\n".toByteArray())
            var offset=0
            while(offset<bytes.size) {
                val length=minOf(16_384,bytes.size-offset)
                output.write(bytes,offset,length);output.flush();offset+=length
                Thread.sleep(pause)
            }
        }catch(_:Exception){} } }
    }catch(_:Exception){break} } }
    override fun close(){socket.close();workers.shutdownNow()}
}
