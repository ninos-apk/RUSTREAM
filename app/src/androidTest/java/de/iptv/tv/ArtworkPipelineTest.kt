package de.iptv.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The loader against a local image host: retries, failure memory, disk persistence and prefetch. */
@RunWith(AndroidJUnit4::class)
class ArtworkPipelineTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val directory=File(context.cacheDir,"artwork-test-${System.nanoTime()}")
    private val closing=CopyOnWriteArrayList<AutoCloseable>()
    @After fun cleanUp(){closing.reversed().forEach{try{it.close()}catch(_:Exception){}};directory.deleteRecursively();File(directory.path+".key").delete()}

    private fun image(width:Int,height:Int,format:Bitmap.CompressFormat=Bitmap.CompressFormat.PNG,quality:Int=90):ByteArray{
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        // A gradient with mild noise compresses like a photo poster.
        val random=java.util.Random(width.toLong()*height)
        val pixels=IntArray(width*height){i->val x=i%width;val y=i/width;val n=random.nextInt(24)
            Color.rgb(40+x*150/width+n,60+y*120/height+n,120+n)}
        bitmap.setPixels(pixels,0,width,0,0,width,height)
        return ByteArrayOutputStream().also{bitmap.compress(format,quality,it)}.toByteArray().also{bitmap.recycle()}
    }
    private fun loader(disk:Boolean=true)=ArtworkLoader(context,if(disk)directory else null).also(closing::add)
    private fun server(respond:(Request)->Reply)=ImageServer(respond).also(closing::add)
    private fun loaded(view:ImageView)=view.drawable is BitmapDrawable
    private fun waitFor(timeout:Long=10_000,condition:()->Boolean):Boolean{
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<deadline){var done=false;instrumentation.runOnMainSync{done=condition()};if(done)return true;SystemClock.sleep(25)}
        return false
    }
    private fun bind(loader:ArtworkLoader,key:String,address:String?):ImageView{
        lateinit var view:ImageView
        instrumentation.runOnMainSync{view=ImageView(context);loader.bind(view,key){address}}
        return view
    }
    private fun stats(loader:ArtworkLoader):JSONObject{var result=JSONObject();instrumentation.runOnMainSync{result=loader.stats()};return result}
    private fun storedFiles()=directory.listFiles().orEmpty().filter{it.name.endsWith(".img")}

    @Test fun transientFailuresRetryWhileTheViewWaits(){
        val png=image(60,90)
        val host=server{request->if(request.count<=2)Reply(503) else Reply(200,png)}
        val loader=loader(disk=false)
        val view=bind(loader,"1:1:false","${host.base}/poster.png")
        assertTrue("A transient failure is retried with backoff",waitFor(15_000){loaded(view)})
        assertEquals(3,host.requests("/poster.png"))
        assertEquals(2L,stats(loader).getJSONObject("failed").getLong("transient"))
    }
    @Test fun missingImagesAreNotRequestedAgain(){
        val host=server{Reply(404)}
        val loader=loader(disk=false)
        val first=bind(loader,"1:1:false","${host.base}/gone.jpg")
        assertTrue(waitFor{loader.stats().getJSONObject("failed").getLong("missing")==1L})
        val second=bind(loader,"1:2:false","${host.base}/gone.jpg")
        val again=bind(loader,"1:1:false","${host.base}/gone.jpg")
        SystemClock.sleep(1_500)
        assertEquals("Episodes sharing a missing poster and revisits do not ask again",1,host.requests("/gone.jpg"))
        instrumentation.runOnMainSync{for(view in listOf(first,second,again))assertFalse(loaded(view))}
    }
    @Test fun diskCacheSurvivesRestartAndServesOffline(){
        val png=image(200,300)
        val host=server{Reply(200,png)}
        val address="${host.base}/restart.png"
        val first=loader()
        val shown=bind(first,"1:1:false",address)
        assertTrue(waitFor{loaded(shown)})
        assertTrue("Written to disk",waitFor{storedFiles().isNotEmpty()})
        first.close();host.close()
        // Same catalog after a restart, now without any reachable host.
        val second=loader()
        val view=bind(second,"7:1:false",address)
        assertTrue("Shown from disk while offline",waitFor{loaded(view)})
        val stats=stats(second)
        assertEquals(1L,stats.getLong("disk_hits"));assertEquals(0L,stats.getLong("network_requests"))
        assertEquals(1,host.requests("/restart.png"))
    }
    @Test fun largeOriginalsAreStoredAtTheDecodedSize(){
        val jpeg=image(1200,1800,Bitmap.CompressFormat.JPEG,92)
        val host=server{Reply(200,jpeg)}
        val loader=loader()
        val view=bind(loader,"1:1:false","${host.base}/large.jpg")
        assertTrue(waitFor{loaded(view)})
        instrumentation.runOnMainSync{
            val bitmap=(view.drawable as BitmapDrawable).bitmap
            assertTrue("Decoded near the default poster size: ${bitmap.width}x${bitmap.height}",bitmap.width<=240&&bitmap.height<=368&&bitmap.width>=200)
        }
        assertTrue(waitFor{storedFiles().isNotEmpty()})
        val stored=storedFiles().sumOf{it.length()}
        assertTrue("Stored $stored bytes for a ${jpeg.size} byte original",stored<jpeg.size/8)
    }
    @Test fun noStoreResponsesStayInMemoryOnly(){
        val png=image(60,90)
        val host=server{Reply(200,png,listOf("Cache-Control: no-store"))}
        val loader=loader()
        val view=bind(loader,"1:1:false","${host.base}/private.png")
        assertTrue(waitFor{loaded(view)})
        SystemClock.sleep(500)
        assertTrue(storedFiles().isEmpty())
    }
    @Test fun prefetchWarmsPostersBeforeTheyAreBound(){
        val png=image(60,90)
        val host=server{Reply(200,png)}
        val loader=loader(disk=false)
        val keys=(0 until 6).map{"1:$it:false"}
        instrumentation.runOnMainSync{loader.prefetch(keys.mapIndexed{i,key->key to suspend{"${host.base}/p$i.png"}})}
        assertTrue(waitFor{loader.stats().getLong("prefetched")==6L})
        lateinit var views:List<ImageView>
        instrumentation.runOnMainSync{
            views=keys.mapIndexed{i,key->ImageView(context).also{loader.bind(it,key){"${host.base}/p$i.png"}}}
            assertTrue("Prefetched posters show on their first bind",views.all(::loaded))
        }
        assertEquals(6,host.total())
        assertEquals(6L,stats(loader).getLong("prefetch_used"))
    }
    @Test fun staleEntriesAreShownAndRevalidated(){
        val png=image(60,90)
        val conditional=AtomicInteger()
        val host=server{request->
            if(request.headers["if-none-match"]=="\"v1\""){conditional.incrementAndGet();Reply(304,headers=listOf("Cache-Control: max-age=3600"))}
            else Reply(200,png,listOf("ETag: \"v1\"","Cache-Control: max-age=3600"))
        }
        val address="${host.base}/stale.png"
        val first=loader()
        val shown=bind(first,"1:1:false",address)
        assertTrue(waitFor{loaded(shown)})
        assertTrue(waitFor{storedFiles().isNotEmpty()})
        first.close()
        // Age the entry: same key file, same name as the loader computes for the default size.
        val cache=ArtworkDiskCache(directory,File(directory.path+".key").readBytes(),1L shl 26).also{it.open()}
        val name=cache.name(address,"240x368")
        val entry=cache.get(name)!!
        cache.put(name,ArtworkDiskCache.Entry(entry.payload,0,entry.etag,entry.lastModified))
        val second=loader()
        val view=bind(second,"1:1:false",address)
        assertTrue("A stale image is shown at once",waitFor(3_000){loaded(view)})
        assertTrue("and revalidated with its ETag",waitFor{second.stats().getLong("not_modified")==1L})
        assertEquals(1,conditional.get());assertEquals(2,host.requests("/stale.png"))
        assertEquals(1L,stats(second).getLong("disk_stale"))
    }
    @Test fun redirectsOnTheSameSchemeAreFollowed(){
        val png=image(60,90)
        val host=server{request->if(request.path=="/old.png")Reply(302,headers=listOf("Location: /new.png")) else Reply(200,png)}
        val loader=loader(disk=false)
        val view=bind(loader,"1:1:false","${host.base}/old.png")
        assertTrue(waitFor{loaded(view)})
        assertEquals(1,host.requests("/old.png"));assertEquals(1,host.requests("/new.png"))
    }

    class Request(val path:String,val headers:Map<String,String>,val count:Int)
    class Reply(val status:Int,val body:ByteArray=ByteArray(0),val headers:List<String> = emptyList())
    /** HTTP/1.1 with one request per connection; counts requests per path. */
    class ImageServer(private val respond:(Request)->Reply):AutoCloseable{
        private val socket=ServerSocket(0)
        private val workers=Executors.newCachedThreadPool()
        private val counts=ConcurrentHashMap<String,AtomicInteger>()
        val base="http://127.0.0.1:${socket.localPort}"
        init{workers.execute{while(!socket.isClosed){try{val client=socket.accept();workers.execute{serve(client)}}catch(_:Exception){break}}}}
        private fun serve(client:Socket){client.use{
            try{
                client.soTimeout=5_000
                val reader=client.getInputStream().bufferedReader()
                val path=reader.readLine()?.split(' ')?.getOrNull(1) ?: return
                val headers=HashMap<String,String>()
                while(true){val line=reader.readLine() ?: break;if(line.isEmpty())break;val colon=line.indexOf(':');if(colon>0)headers[line.substring(0,colon).trim().lowercase()]=line.substring(colon+1).trim()}
                val reply=respond(Request(path,headers,counts.getOrPut(path){AtomicInteger()}.incrementAndGet()))
                val head=StringBuilder("HTTP/1.1 ${reply.status} X\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n")
                reply.headers.forEach{head.append(it).append("\r\n")}
                client.getOutputStream().apply{write(head.append("\r\n").toString().toByteArray());write(reply.body);flush()}
            }catch(_:Exception){}
        }}
        fun requests(path:String)=counts[path]?.get() ?: 0
        fun total()=counts.values.sumOf{it.get()}
        override fun close(){socket.close();workers.shutdownNow()}
    }
}
