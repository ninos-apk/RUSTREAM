@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in private-provider review. Playlist is pushed separately, never bundled as an asset. */
@RunWith(AndroidJUnit4::class)
class PrivatePlaylistReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun reviewPrivatePlaylist() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("privatePlaylist")
        assumeTrue("Private list not supplied", path != null)
        val privateFile = File(checkNotNull(path))
        val samples = JSONArray(args.getString("reviewSamples") ?: "[]")
        val store = SourceStore(context)
        val previous = store.load()
        val report = JSONObject().put("samples",JSONArray()).put("searches",JSONArray())
        val resultFile = File(context.getExternalFilesDir(null),"private-review.json")
        fun save() { resultFile.writeText(report.toString(2)) }
        try {
            require(privateFile.isFile)
            store.save(listOf(JSONObject().put("kind","file").put("name","Private Testplaylist").put("uri",Uri.fromFile(privateFile).toString())))
            val launched = SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var core: Core
                lateinit var playback: PlaybackController
                scenario.onActivity { activity ->
                    core = MainActivity::class.java.getDeclaredField("core").apply { isAccessible=true }.get(activity) as Core
                    playback = MainActivity::class.java.getDeclaredField("playback").apply { isAccessible=true }.get(activity) as PlaybackController
                }
                var categories = core.call("categories")
                val deadline = SystemClock.elapsedRealtime()+90_000
                while (categories.getInt("count")==0 && SystemClock.elapsedRealtime()<deadline) { delay(200); categories=core.call("categories") }
                val count = categories.getInt("count")
                report.put("channels",count).put("categories",categories.getJSONArray("categories").length())
                    .put("startup_import_ms",SystemClock.elapsedRealtime()-launched).put("rust_import_ms",categories.optDouble("import_ms"))
                    .put("native_heap_mib",Debug.getNativeHeapAllocatedSize()/1048576.0).put("process_pss_mib",Debug.getPss()/1024.0)
                save()
                assertEquals(args.getString("expectedCount")!!.toInt(),count)
                val revision=categories.getLong("revision")
                for ((index,query) in listOf("", "hd", "de", "zdf", "sky", "not-a-real-channel-982361").withIndex()) {
                    val start=SystemClock.elapsedRealtimeNanos()
                    val result=core.call("search","revision" to revision,"query" to query,"offset" to 0,"limit" to 64)
                    report.getJSONArray("searches").put(JSONObject().put("query_id",index).put("matches",result.getInt("total"))
                        .put("round_trip_ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6).put("page_size",result.getJSONArray("items").length()))
                }
                val last = core.call("search","revision" to revision,"query" to "","offset" to (count-10),"limit" to 64)
                assertEquals(10,last.getJSONArray("items").length())
                report.put("last_page_ok",true)
                val pingStart = SystemClock.elapsedRealtimeNanos()
                withContext(Dispatchers.Main) { assertNotNull(playback.player ?: playback) }
                report.put("main_thread_round_trip_ms",(SystemClock.elapsedRealtimeNanos()-pingStart)/1e6)
                save()
                var reconnectChecked=false
                var vodSeekChecked=false
                for (i in 0 until samples.length()) {
                    val sample=samples.getJSONObject(i)
                    val row=JSONObject().put("id",sample.getLong("id")).put("kind",sample.getString("kind"))
                    val start=SystemClock.elapsedRealtime()
                    var firstFrame: Long?=null
                    var errorCode: Int?=null
                    var httpCode: Int?=null
                    var errorCauses: List<String> = emptyList()
                    var rebufferCount=0
                    withContext(Dispatchers.Main) {
                        playback.select(revision,sample.getLong("id"))
                        playback.player!!.addListener(object: Player.Listener {
                            override fun onRenderedFirstFrame() { if (firstFrame==null) firstFrame=SystemClock.elapsedRealtime()-start }
                            override fun onPlaybackStateChanged(state: Int) { if (state==Player.STATE_BUFFERING && firstFrame!=null) rebufferCount++ }
                            override fun onPlayerError(error: PlaybackException) {
                                errorCode=error.errorCode
                                errorCauses=generateSequence(error as Throwable) { it.cause }.take(12).map { it.javaClass.simpleName }.toList()
                                httpCode=generateSequence(error as Throwable) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                            }
                        })
                    }
                    var position=0L
                    while (SystemClock.elapsedRealtime()-start<30_000) {
                        delay(250)
                        val ready=withContext(Dispatchers.Main) {
                            val p=playback.player
                            position=p?.currentPosition ?: 0
                            p?.isPlaying==true && position>=1500 && firstFrame!=null
                        }
                        if (ready || httpCode in setOf(401,403,404,410) || errorCode in setOf(4001,4003,3002)) break
                    }
                    val played = withContext(Dispatchers.Main) { playback.player?.isPlaying==true && firstFrame!=null && position>=1500 }
                    row.put("video_played",played).put("first_frame_ms",firstFrame ?: JSONObject.NULL).put("elapsed_ms",SystemClock.elapsedRealtime()-start)
                        .put("position_ms",position).put("error_code",errorCode ?: JSONObject.NULL).put("http_status",httpCode ?: JSONObject.NULL)
                        .put("error_cause_types",JSONArray(errorCauses))
                    withContext(Dispatchers.Main) {
                        val p=playback.player
                        val format=p?.videoFormat
                        row.put("video_mime",format?.sampleMimeType ?: JSONObject.NULL).put("width",format?.width ?: JSONObject.NULL).put("height",format?.height ?: JSONObject.NULL)
                        row.put("seekable",p?.isCurrentMediaItemSeekable==true)
                    }
                    if (played && !reconnectChecked) {
                        val holdStart=SystemClock.elapsedRealtime()
                        val initialPosition=position
                        delay(30_000)
                        val sustained=withContext(Dispatchers.Main) { playback.player?.isPlaying==true && playback.player!!.currentPosition>initialPosition+25_000 }
                        row.put("sustained_30s_ok",sustained).put("sustained_ms",SystemClock.elapsedRealtime()-holdStart).put("rebuffer_count",rebufferCount)
                        withContext(Dispatchers.Main) { playback.togglePause() }
                        val pausedPosition=withContext(Dispatchers.Main) { playback.player!!.currentPosition }
                        delay(1500)
                        val pauseOk=withContext(Dispatchers.Main) { !playback.player!!.isPlaying && kotlin.math.abs(playback.player!!.currentPosition-pausedPosition)<250 }
                        row.put("pause_ok",pauseOk)
                        withContext(Dispatchers.Main) { playback.togglePause(); playback.reconnect() }
                        val retryStart=SystemClock.elapsedRealtime()
                        while (SystemClock.elapsedRealtime()-retryStart<20_000 && !withContext(Dispatchers.Main) { playback.player?.isPlaying==true }) delay(250)
                        row.put("manual_reconnect_ok",withContext(Dispatchers.Main) { playback.player?.isPlaying==true }).put("manual_reconnect_ms",SystemClock.elapsedRealtime()-retryStart)
                        val oldPlayer=withContext(Dispatchers.Main) { playback.player }
                        scenario.moveToState(Lifecycle.State.CREATED)
                        delay(1200)
                        val released=withContext(Dispatchers.Main) { playback.player==null }
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        val resumeStart=SystemClock.elapsedRealtime()
                        while (SystemClock.elapsedRealtime()-resumeStart<20_000 && !withContext(Dispatchers.Main) { playback.player!==oldPlayer && playback.player?.isPlaying==true }) delay(250)
                        row.put("lifecycle_release_ok",released).put("lifecycle_resume_ok",withContext(Dispatchers.Main) { playback.player!==oldPlayer && playback.player?.isPlaying==true })
                        reconnectChecked=true
                    }
                    if (played && row.optBoolean("seekable") && !vodSeekChecked) {
                        withContext(Dispatchers.Main) { playback.togglePause() }
                        val beforeSeek=withContext(Dispatchers.Main) { playback.player!!.currentPosition }
                        withContext(Dispatchers.Main) { playback.seek(15_000) }
                        delay(1000)
                        row.put("vod_seek_while_paused_ok",withContext(Dispatchers.Main) { !playback.player!!.playWhenReady && playback.player!!.currentPosition>=beforeSeek+10_000 })
                        withContext(Dispatchers.Main) { playback.togglePause() }
                        val resumed=SystemClock.elapsedRealtime()
                        while (SystemClock.elapsedRealtime()-resumed<15_000 && !withContext(Dispatchers.Main) { playback.player?.isPlaying==true }) delay(250)
                        row.put("vod_resume_after_seek_ok",withContext(Dispatchers.Main) { playback.player?.isPlaying==true })
                        vodSeekChecked=true
                    }
                    row.put("process_pss_mib",Debug.getPss()/1024.0)
                    withContext(Dispatchers.Main) { playback.stop() }
                    report.getJSONArray("samples").put(row); save()
                    instrumentation.sendStatus(0,Bundle().apply { putString("review","sample ${i+1}/${samples.length()}: ${sample.getString("kind")}, video=$played, http=${httpCode ?: 0}, error=${errorCode ?: 0}") })
                    delay(750)
                }
                report.put("complete",true); save()
            }
        } catch (error: Exception) {
            report.put("failure_type",error.javaClass.simpleName); save(); throw AssertionError("Private review failed; see redacted JSON")
        } finally { store.save(previous); privateFile.delete() }
    }
}

