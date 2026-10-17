package de.iptv.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Held D-pad scrolling through a large poster grid against a slow, partly failing image host.
 * Black-box: it observes only the visible cards and the server, so it also measures older builds.
 * The report is written before the assertions: /sdcard/Download/iptv-poster-scroll.json.
 */
@RunWith(AndroidJUnit4::class)
class PosterScrollTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private data class Snapshot(val expected:Int,val loaded:Int,val missing:List<Int>)

    @Test fun heldDpadScrollingShowsEveryValidPosterAndRecovers(){
        val store=SourceStore(context);val old=store.load()
        PosterHost().use{host->
            val file=File(context.filesDir,"poster-scroll-${System.nanoTime()}.m3u").apply{writeText(buildString{
                append("#EXTM3U\n")
                for(i in 0 until COUNT){
                    val logo=logo(i,host.base)?.let{" tvg-logo=\"$it\""} ?: ""
                    append("#EXTINF:-1 group-title=Filme$logo,Poster ${"%04d".format(i)}\nhttp://127.0.0.1:9/movie/u/p/$i.mp4\n")
                }
            })}
            val report=JSONObject().put("count",COUNT)
            val phases=JSONObject();report.put("phases",phases)
            try{
                store.save(listOf(JSONObject().put("kind","file").put("name","Poster Scroll").put("uri",android.net.Uri.fromFile(file).toString())))
                withTvActivity{scenario->
                    fun grid():RecyclerView?{var found:RecyclerView?=null;scenario.onTvActivity{a->found=grid(a)};return found}
                    fun snapshot():Snapshot{
                        var result=Snapshot(0,0,emptyList())
                        scenario.onTvActivity{a->
                            val list=grid(a) ?: return@onTvActivity
                            var expected=0;var loaded=0;val missing=mutableListOf<Int>()
                            for(c in 0 until list.childCount){
                                val card=list.getChildAt(c) as? ViewGroup ?: continue
                                if(card.bottom<=0||card.top>=list.height)continue
                                val index=Regex("\\d{4}").find(card.contentDescription?:"")?.value?.toInt() ?: continue
                                if(!expectsImage(index))continue
                                expected++
                                if((card.getChildAt(0) as? ImageView)?.drawable is BitmapDrawable)loaded++ else missing+=index
                            }
                            result=Snapshot(expected,loaded,missing)
                        }
                        return result
                    }
                    /** Milliseconds until every visible card with valid artwork shows it, or -1. */
                    fun settle(name:String,timeout:Long=20_000):Long{
                        val started=SystemClock.elapsedRealtime();var last=snapshot();var reliable=-1L
                        while(SystemClock.elapsedRealtime()-started<timeout){
                            last=snapshot()
                            // Posters whose host answers 503 once are expected to wait for their backoff.
                            if(reliable<0&&last.expected>0&&last.missing.all{it%20==13})reliable=SystemClock.elapsedRealtime()-started
                            if(last.expected>0&&last.missing.isEmpty())break
                            SystemClock.sleep(50)
                        }
                        val elapsed=if(last.expected>0&&last.missing.isEmpty())SystemClock.elapsedRealtime()-started else -1
                        var focus="";var firstVisible=-1
                        scenario.onTvActivity{a->
                            focus=a.currentFocus?.let{f->f.contentDescription?.toString()?:f.javaClass.simpleName}?:"none"
                            val list=grid(a);firstVisible=(0 until (list?.childCount?:0)).map{list!!.getChildAt(it)}.firstOrNull{it.bottom>0}
                                ?.contentDescription?.let{Regex("\\d{4}").find(it)?.value?.toInt()}?:-1
                        }
                        phases.put(name,JSONObject().put("settle_ms",elapsed).put("settle_ms_without_503_posters",reliable).put("visible_expected",last.expected).put("visible_loaded",last.loaded)
                            .put("missing",JSONArray(last.missing.take(12))).put("requests_so_far",host.total()).put("first_visible",firstVisible).put("focus",focus))
                        Log.i(TAG,"$name: settle=${elapsed}ms loaded=${last.loaded}/${last.expected} missing=${last.missing.take(12)} first=$firstVisible focus=$focus")
                        return elapsed
                    }
                    /** Scrolling must start from a focused card; a lost focus is recorded, not hidden. */
                    fun ensureFocus(name:String){
                        scenario.onTvActivity{a->
                            val list=grid(a) ?: return@onTvActivity
                            if(a.currentFocus?.let{list.findContainingItemView(it)}!=null)return@onTvActivity
                            report.put("focus_lost_before_$name",a.currentFocus?.javaClass?.simpleName?:"none")
                            (0 until list.childCount).map{list.getChildAt(it)}.firstOrNull{it.top>=0}?.requestFocus()
                        }
                    }
                    fun hold(code:Int,millis:Long){
                        val down=SystemClock.uptimeMillis();var repeat=0
                        instrumentation.sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,code,0))
                        var next=down+ViewConfiguration.getKeyRepeatTimeout()
                        while(SystemClock.uptimeMillis()<down+millis){
                            val now=SystemClock.uptimeMillis()
                            if(now<next){SystemClock.sleep(next-now);continue}
                            instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,code,++repeat))
                            next+=ViewConfiguration.getKeyRepeatDelay()
                        }
                        instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0))
                    }
                    fun openMovies(){
                        val title="Movies ($COUNT)"
                        assertTrue("Movies tab",device.wait(Until.hasObject(By.text(title)),60_000))
                        scenario.onTvActivity{a->assertTrue(a.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<Button>().first{it.text.toString()==title}.requestFocus())}
                        device.pressDPadCenter()
                        val deadline=SystemClock.elapsedRealtime()+10_000
                        while(grid()?.childCount.let{it==null||it==0}&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20)
                        scenario.onTvActivity{a->assertTrue("First card focus",grid(a)!!.getChildAt(0).requestFocus())}
                    }
                    openMovies()
                    report.put("columns",(grid()!!.layoutManager as GridLayoutManager).spanCount)
                    settle("first_screen")
                    device.executeShellCommand("dumpsys gfxinfo ${context.packageName} reset")
                    val cpuStart=mainThreadCpuMs()
                    hold(KeyEvent.KEYCODE_DPAD_DOWN,6_000);settle("held_down")
                    ensureFocus("held_up");hold(KeyEvent.KEYCODE_DPAD_UP,3_000);settle("held_up_revisit")
                    ensureFocus("direction_changes")
                    repeat(6){hold(KeyEvent.KEYCODE_DPAD_DOWN,700);hold(KeyEvent.KEYCODE_DPAD_UP,500)}
                    settle("direction_changes")
                    // The whole UI thread (layout, binding, drawing, artwork) while scrolling and settling.
                    report.put("main_thread_cpu_ms_scrolling",mainThreadCpuMs()-cpuStart)
                    report.put("frames",frames(device.executeShellCommand("dumpsys gfxinfo ${context.packageName}")))
                    ensureFocus("offline")
                    host.offline=true
                    hold(KeyEvent.KEYCODE_DPAD_DOWN,1_500);SystemClock.sleep(1_500)
                    host.offline=false
                    settle("offline_recovery",30_000)
                    // Same state for every build: unreachable bitmaps are freed before the sample.
                    repeat(2){Runtime.getRuntime().gc();System.runFinalization();SystemClock.sleep(200)}
                    report.put("memory",memory(device.executeShellCommand("dumpsys meminfo ${context.packageName}")))
                    stats(scenario)?.let{report.put("loader_before_restart",it)}
                }
                // A new Activity on the same catalog: the posters of the first screen again.
                val before=host.total()
                withTvActivity{scenario->
                    val title="Movies ($COUNT)"
                    assertTrue(device.wait(Until.hasObject(By.text(title)),60_000))
                    val started=SystemClock.elapsedRealtime()
                    scenario.onTvActivity{a->assertTrue(a.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<Button>().first{it.text.toString()==title}.requestFocus())}
                    device.pressDPadCenter()
                    var shown=-1L;val deadline=started+20_000
                    while(shown<0&&SystemClock.elapsedRealtime()<deadline){
                        scenario.onTvActivity{a->
                            val list=grid(a)
                            val cards=(0 until (list?.childCount?:0)).mapNotNull{list!!.getChildAt(it) as? ViewGroup}.filter{it.top<list!!.height}
                            val wanted=cards.filter{c->Regex("\\d{4}").find(c.contentDescription?:"")?.value?.toInt()?.let(::expectsImage)==true}
                            if(wanted.isNotEmpty()&&wanted.all{(it.getChildAt(0) as? ImageView)?.drawable is BitmapDrawable})shown=SystemClock.elapsedRealtime()-started
                        }
                        if(shown<0)SystemClock.sleep(20)
                    }
                    phases.put("restart_first_screen",JSONObject().put("settle_ms",shown).put("network_requests",host.total()-before))
                    stats(scenario)?.let{report.put("loader_after_restart",it)}
                }
                report.put("requests_total",host.total()).put("requests_offline",host.offlineRequests.get())
                    .put("redundant_downloads",host.redundant()).put("shared_poster_requests",JSONObject().apply{host.shared().forEach{(k,v)->put(k,v)}})
                    .put("max_parallel_requests",host.maxParallel.get())
            }finally{
                val text=report.toString(2)
                Log.i(TAG,text)
                File(context.getExternalFilesDir(null),"iptv-poster-scroll.json").also{it.writeText(text)}.let{
                    device.executeShellCommand("cp ${it.absolutePath} /sdcard/Download/iptv-poster-scroll.json")
                }
                store.save(old);file.delete()
            }
            for(name in phases.keys())assertTrue("Every valid visible poster appears: $name ${phases.getJSONObject(name)}",phases.getJSONObject(name).getLong("settle_ms")>=0)
            assertEquals("Shared posters download once",listOf(1,1),host.shared().values.toList())
        }
    }
    /** CPU time of the app's main thread; the instrumentation shares the app process. */
    private fun mainThreadCpuMs():Long{
        val fields=File("/proc/self/task/${android.os.Process.myPid()}/stat").readText().substringAfterLast(')').trim().split(' ')
        val ticks=fields[11].toLong()+fields[12].toLong()
        return ticks*1000/android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
    }
    private fun grid(activity:MainActivity)=activity.findViewById<ViewGroup>(android.R.id.content).descendants
        .filterIsInstance<RecyclerView>().firstOrNull{it.layoutManager is GridLayoutManager&&it.isShown}
    private fun stats(scenario:androidx.test.core.app.ActivityScenario<MainActivity>):JSONObject?{
        var result:JSONObject?=null
        scenario.onTvActivity{a->result=try{
            val field=MainActivity::class.java.getDeclaredField("artwork").apply{isAccessible=true}
            val loader=field.get(a)
            loader.javaClass.methods.firstOrNull{it.name=="stats"&&it.parameterCount==0}?.invoke(loader) as? JSONObject
        }catch(_:Exception){null}}
        return result
    }
    private fun frames(dump:String)=JSONObject().apply{
        for((key,pattern) in listOf("total" to "Total frames rendered: (\\d+)","janky" to "Janky frames: (\\d+)","p50_ms" to "50th percentile: (\\d+)ms",
            "p90_ms" to "90th percentile: (\\d+)ms","p95_ms" to "95th percentile: (\\d+)ms","p99_ms" to "99th percentile: (\\d+)ms"))
            Regex(pattern).find(dump)?.groupValues?.get(1)?.toLong()?.let{put(key,it)}
    }
    private fun memory(dump:String)=JSONObject().apply{
        for((key,pattern) in listOf("total_pss_kb" to "TOTAL PSS:\\s+(\\d+)","java_heap_kb" to "Java Heap:\\s+(\\d+)","native_heap_kb" to "Native Heap:\\s+(\\d+)",
            "code_kb" to "Code:\\s+(\\d+)","stack_kb" to "Stack:\\s+(\\d+)","graphics_kb" to "Graphics:\\s+(\\d+)","private_other_kb" to "Private Other:\\s+(\\d+)","system_kb" to "System:\\s+(\\d+)"))
            Regex(pattern).find(dump)?.groupValues?.get(1)?.toLong()?.let{put(key,it)}
    }
    companion object {
        private const val TAG="PosterScrollTest"
        private const val COUNT=1500
        /** Per 20 entries: 13 distinct posters, 1 transient 503, 1 missing (404), 1 without artwork,
         * 2 sharing one of two addresses, 1 slow host. */
        fun logo(i:Int,base:String):String?=when(i%20){
            13->"$base/t/$i.jpg";14->"$base/m/$i.jpg";15->null;16,17->"$base/s/${i%10}.jpg";18->"$base/slow/$i.jpg";else->"$base/v/$i.jpg"}
        fun expectsImage(i:Int)=i%20!=14&&i%20!=15
    }
}

/** Synthetic JPEG posters with 120-320 ms latency; offline closes connections without a response. */
private class PosterHost:AutoCloseable{
    private val socket=ServerSocket(0)
    private val workers=Executors.newCachedThreadPool()
    private val requests=ConcurrentHashMap<String,AtomicInteger>()
    private val parallel=AtomicInteger()
    val maxParallel=AtomicInteger()
    val offlineRequests=AtomicInteger()
    @Volatile var offline=false
    val base="http://127.0.0.1:${socket.localPort}"
    private val variants=List(8){v->
        val image=Bitmap.createBitmap(500,750,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.rgb(20+v*20,60,90))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(103,222,211)}
        canvas.drawCircle(350f,240f,200f,paint);paint.color=Color.rgb(25,44,60);canvas.drawCircle(140f,450f,190f,paint)
        ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.JPEG,85,it)}.toByteArray().also{image.recycle()}
    }
    init{workers.execute{while(!socket.isClosed){try{val client=socket.accept();workers.execute{serve(client)}}catch(_:Exception){break}}}}
    private fun serve(client:Socket)=client.use{
        try{
            client.soTimeout=10_000
            val reader=client.getInputStream().bufferedReader()
            val path=reader.readLine()?.split(' ')?.getOrNull(1) ?: return
            while(reader.readLine()?.isNotEmpty()==true){}
            if(offline){offlineRequests.incrementAndGet();return}
            val count=requests.getOrPut(path){AtomicInteger()}.incrementAndGet()
            val now=parallel.incrementAndGet();maxParallel.accumulateAndGet(now,::maxOf)
            try{
                SystemClock.sleep(if(path.startsWith("/slow/"))2_500 else 120L+(path.hashCode() and 0x7fffffff)%200)
                val out=client.getOutputStream()
                when{
                    path.startsWith("/m/")->out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    path.startsWith("/t/")&&count==1->out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else->{
                        val bytes=variants[(path.filter(Char::isDigit).toIntOrNull()?:0)%variants.size]
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes)
                    }
                }
                out.flush()
            }finally{parallel.decrementAndGet()}
        }catch(_:Exception){}
    }
    fun total()=requests.values.sumOf{it.get()}
    /** Downloads beyond the first successful one; a transient 503 legitimately needs a second request. */
    fun redundant()=requests.entries.sumOf{(path,n)->(n.get()-if(path.startsWith("/t/"))2 else 1).coerceAtLeast(0)}
    fun shared()=requests.filterKeys{it.startsWith("/s/")}.toSortedMap().mapValues{it.value.get()}
    override fun close(){socket.close();workers.shutdownNow()}
}
