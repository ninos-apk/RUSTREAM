package de.iptv.tv

import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, local-only measurements. Never selects a stream or exports playlist content. */
@RunWith(AndroidJUnit4::class)
class LocalPlaylistBenchmarkTest {
    @Test fun benchmarkLocalPlaylist(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val suppliedPath = args.getString("benchmarkPlaylist")
        assumeTrue("No local benchmark playlist supplied", suppliedPath != null)
        val input = File(checkNotNull(suppliedPath))
        require(input.canonicalFile.parentFile == context.getExternalFilesDir(null)?.canonicalFile && input.name == "local-benchmark.m3u")
        val report = JSONObject().put("imports", JSONArray()).put("network_streams_opened", 0)
        val output = File(context.getExternalFilesDir(null), "local-playlist-benchmark.json")
        val running = AtomicBoolean(true)
        val peakNative = AtomicLong()
        val peakJava = AtomicLong()
        val sampler = Thread {
            while (running.get()) {
                peakNative.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                val runtime = Runtime.getRuntime()
                peakJava.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                Thread.sleep(25)
            }
        }.apply { isDaemon = true; start() }
        val store = SourceStore(context)
        val previous = store.load()
        val temporary = File.createTempFile("benchmark-", ".m3u", context.cacheDir)
        try {
            report.put("bytes", input.length()).put("pss_before_mib", Debug.getPss() / 1024.0)
            val started = SystemClock.elapsedRealtimeNanos()
            input.inputStream().use { from -> temporary.outputStream().use { BoundedCopy.copy(from, it) } }
            report.put("local_copy_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1e6)
            Core().use { core ->
                SourceLoader(context).use { loader ->
                repeat(3) { index ->
                    val begin = SystemClock.elapsedRealtimeNanos()
                    val result = loader.load(JSONObject().put("kind","file").put("uri",Uri.fromFile(temporary).toString()),core)
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - begin) / 1e6
                    val categories = core.call("categories")
                    assertEquals(257468, result.getInt("count"))
                    val searches = JSONArray()
                    for (kind in listOf("live", "movies", "series")) {
                        val searchStart = SystemClock.elapsedRealtimeNanos()
                        val page = core.call("search", "revision" to result.getLong("revision"), "kind" to kind,
                            "query" to "", "offset" to 0, "limit" to 64)
                        searches.put(JSONObject().put("kind", kind).put("total", page.getInt("total"))
                            .put("ms", (SystemClock.elapsedRealtimeNanos() - searchStart) / 1e6)
                            .put("page_size", page.getJSONArray("items").length()))
                    }
                    report.getJSONArray("imports").put(JSONObject().put("reload_index", index)
                        .put("jni_import_ms", elapsed).put("rust_import_ms", result.getDouble("import_ms"))
                        .put("categories", categories.getJSONArray("categories").length())
                        .put("kinds", categories.getJSONArray("kinds")).put("searches", searches)
                        .put("native_heap_mib", Debug.getNativeHeapAllocatedSize() / 1048576.0)
                        .put("pss_after_mib", Debug.getPss() / 1024.0))
                    output.writeText(report.toString(2))
                }
                }
            }
            args.getString("benchmarkUrl")?.let { address ->
                require(Uri.parse(address).host == "127.0.0.1")
                SourceLoader(context).use { loader ->
                    Core().use { core ->
                        val begin = SystemClock.elapsedRealtimeNanos()
                        val imported = loader.load(JSONObject().put("kind", "url").put("url", "$address/playlist.m3u"), core)
                        report.put("local_http", JSONObject().put("total_ms", (SystemClock.elapsedRealtimeNanos() - begin) / 1e6)
                            .put("rust_import_ms", imported.getDouble("import_ms")).put("channels", imported.getInt("count")))
                        val faults = JSONArray()
                        for (status in listOf(401, 403, 404, 429, 500, 503)) {
                            try {
                                loader.load(JSONObject().put("kind", "url").put("url", "$address/http/$status"), core)
                                fail("HTTP error accepted")
                            } catch (error: Exception) {
                                val code = error.message?.takeIf { it.matches(Regex("http_[0-9]{3}")) } ?: "unclassified"
                                faults.put(JSONObject().put("http_status", status).put("loader_code", code).put("exception_type", error.javaClass.simpleName))
                            }
                        }
                        report.put("http_faults", faults)
                        try {
                            val html = loader.load(JSONObject().put("kind", "url").put("url", "$address/html"), core)
                            report.put("html_200_probe", JSONObject().put("accepted", true).put("channels", html.getInt("count")))
                            fail("HTML response must not replace the playlist")
                        } catch (error: Exception) {
                            report.put("html_200_probe", JSONObject().put("accepted", false).put("exception_type", error.javaClass.simpleName))
                        }
                    }
                }
            }
            val uiSource=JSONObject().put("kind", "file").put("name", "Local benchmark").put("uri", Uri.fromFile(input).toString())
            SourceLoader(context).use{it.remove(uiSource)}
            // Exercise the real SourceLoader + MainActivity path through the first laid-out rows.
            store.save(listOf(JSONObject().put("kind", "file").put("name", "Local benchmark")
                .put("uri", Uri.fromFile(input).toString())))
            val launched = SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var rows = 0
                var laidOut = false
                val deadline = SystemClock.elapsedRealtime() + 60000
                while (!laidOut && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val view = MainActivity::class.java.getDeclaredField("channels")
                            .apply { isAccessible = true }.get(activity) as RecyclerView
                        rows = view.adapter?.itemCount ?: 0
                        laidOut = rows > 0 && view.findViewHolderForAdapterPosition(0) != null
                    }
                    if (!laidOut) delay(25)
                }
                assertTrue("No rendered playlist rows", laidOut)
                report.put("activity_launch_to_rows_ms", SystemClock.elapsedRealtime() - launched)
                    .put("initial_ui_rows", rows).put("pss_ui_mib", Debug.getPss() / 1024.0)
                for ((kind, expected) in listOf("live" to 6186, "movies" to 20416, "series" to 230866)) {
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("switchKind", String::class.java, Boolean::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(activity, kind, true)
                    }
                    var total = 0
                    val until = SystemClock.elapsedRealtime() + 10000
                    while (total == 0 && SystemClock.elapsedRealtime() < until) {
                        delay(25)
                        scenario.onActivity { activity ->
                            total = MainActivity::class.java.getDeclaredField("total").apply { isAccessible = true }.getInt(activity)
                        }
                    }
                    assertEquals("Section total $kind", expected, total)
                }
                report.put("ui_all_sections_verified", true)
            }
            val warmStarted=SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var laidOut=false
                while(!laidOut&&SystemClock.elapsedRealtime()-warmStarted<10000){
                    scenario.onActivity{ activity ->
                        val view=MainActivity::class.java.getDeclaredField("channels").apply{isAccessible=true}.get(activity) as RecyclerView
                        laidOut=(view.adapter?.itemCount?:0)>0&&view.findViewHolderForAdapterPosition(0)!=null
                    }
                    if(!laidOut)delay(25)
                }
                assertTrue("Cached catalog did not restore",laidOut)
                report.put("warm_launch_to_rows_ms",SystemClock.elapsedRealtime()-warmStarted)
            }
            report.put("complete", true)
        } catch (error: Exception) {
            report.put("failure_type", error.javaClass.simpleName)
            throw AssertionError("Local playlist benchmark failed; see redacted report")
        } finally {
            running.set(false)
            sampler.join(1000)
            report.put("sampled_peak_native_mib", peakNative.get() / 1048576.0)
                .put("sampled_peak_java_mib", peakJava.get() / 1048576.0)
                .put("memory_sample_interval_ms", 25)
            output.writeText(report.toString(2))
            store.save(previous)
            temporary.delete()
            input.delete()
        }
    }
}
