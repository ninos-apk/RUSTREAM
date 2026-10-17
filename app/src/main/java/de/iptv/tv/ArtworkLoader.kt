package de.iptv.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/** Bounded workers. Original artwork URLs stay in memory only, never in logs or a disk cache. */
class ArtworkLoader : java.io.Closeable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    // Decoded images are shared by address: episodes of a series and repeated logos reuse one
    // download, also across a catalog refresh that changes every row key. A fixed 16 MiB held
    // only ~30 posters (~0.5 MiB each), so scrolling a few grid rows evicted the visible ones.
    private val cache=object:LruCache<String,Bitmap>(memoryBudget(Runtime.getRuntime().maxMemory()/8,16,64)){
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    // Compressed bytes are ~10x smaller: an evicted poster is decoded again, not downloaded again.
    private val encoded=object:LruCache<String,ByteArray>(memoryBudget(Runtime.getRuntime().maxMemory()/16,8,32)){
        override fun sizeOf(key:String,value:ByteArray)=value.size
    }
    private val addresses=LruCache<String,String>(4096)
    private val failures=LinkedHashMap<String,Long>()
    private val loading=HashMap<String,CompletableDeferred<Bitmap?>>()
    private data class Task(val view:WeakReference<ImageView>,val key:String,val address:suspend ()->String?)
    // Newest request first and never dropped while a bound view still waits for it.
    private val pending=ArrayDeque<Task>()
    private val wake=Channel<Unit>(Channel.CONFLATED)
    init { repeat(WORKERS) { scope.launch {
        while(true) {
            val task=synchronized(pending){pending.removeLastOrNull().also{if(pending.isNotEmpty())wake.trySend(Unit)}}
            if(task==null){wake.receive();continue}
            val view=task.view.get() ?: continue
            if(withContext(Dispatchers.Main){view.tag!=task.key})continue
            val bitmap=try {
                (addresses.get(task.key) ?: task.address()?.also{addresses.put(task.key,it)})?.let{load(it)}
            } catch(error:Exception) { if(error is kotlinx.coroutines.CancellationException)throw error; null }
            if(bitmap==null)fail(task.key)
            withContext(Dispatchers.Main){if(view.tag==task.key)bitmap?.let{view.setImageBitmap(it)}}
        }
    } } }
    private fun failed(key:String)=synchronized(failures){failures[key]}?.let{SystemClock.elapsedRealtime()-it<60_000}==true
    private fun fail(key:String)=synchronized(failures){
        failures[key]=SystemClock.elapsedRealtime()
        while(failures.size>96)failures.remove(failures.keys.first())
    }
    /** One download per address; concurrent requests for the same image wait for it. */
    private suspend fun load(address:String):Bitmap? {
        cache.get(address)?.let{return it}
        if(failed(address))return null
        val (result,owner)=synchronized(loading){loading[address]?.let{it to false} ?: (CompletableDeferred<Bitmap?>().also{loading[address]=it} to true)}
        if(!owner)return result.await()
        var bitmap:Bitmap?=null
        try {
            val bytes=encoded.get(address) ?: download(address)?.also{encoded.put(address,it)}
            bitmap=bytes?.let(::decode)
        }
        finally {
            // LruCache.put returns the previous entry, so it must not drive the failure branch:
            // that marked every new image as failed and blocked it after eviction for a minute.
            if(bitmap!=null)cache.put(address,bitmap) else fail(address)
            synchronized(loading){loading.remove(address)}
            result.complete(bitmap)
        }
        return bitmap
    }
    private fun download(address:String):ByteArray? {
        val url=address.toHttpUrlOrNull() ?: return null
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body=response.body?.takeIf{response.isSuccessful&&it.contentLength()<=8L*1024*1024} ?: return null
                val output=ByteArrayOutputStream()
                body.byteStream().use { BoundedCopy.copy(it,output,8L*1024*1024) }
                output.toByteArray()
            }
        } catch(_:java.io.IOException) { null } catch(_:IllegalArgumentException) { null }
    }
    fun bind(view:ImageView,key:String,address:suspend ()->String?) {
        val shown=(view.drawable as? BitmapDrawable)?.bitmap?.takeIf{view.tag==key}
        view.tag=key
        val known=addresses.get(key)
        val bitmap=known?.let{cache.get(it)}
        if(bitmap!=null){view.setImageBitmap(bitmap);return}
        // A rebind of the same row (adapter refresh) keeps its image instead of flashing a placeholder.
        if(shown!=null){known?.let{cache.put(it,shown)};return}
        view.setImageDrawable(ArtworkPlaceholder())
        if(failed(key))return
        synchronized(pending){
            pending.addLast(Task(WeakReference(view),key,address))
            // Only the oldest requests go; their rows have long been rebound or scrolled away.
            while(pending.size>MAX_PENDING)pending.removeFirst()
        }
        wake.trySend(Unit)
    }
    fun clear(){cache.evictAll();encoded.evictAll();addresses.evictAll()}
    // Cancelling the scope ends idle workers in receive(); closing the channel would crash them.
    override fun close(){scope.cancel();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown();clear()}
    companion object {
        // Unreachable logo hosts hold a worker until the call timeout; two could stall every row.
        private const val WORKERS=4
        private const val MAX_PENDING=256
        internal fun memoryBudget(bytes:Long,minMiB:Int,maxMiB:Int)=bytes.coerceIn(minMiB*1048576L,maxMiB*1048576L).toInt()
        internal fun decode(bytes:ByteArray):Bitmap? {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            if(bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192)return null
            val options=BitmapFactory.Options()
            // Bound decoded pixels before allocation, including very wide or tall images.
            while(bounds.outWidth/options.inSampleSize.coerceAtLeast(1)>480 || bounds.outHeight/options.inSampleSize.coerceAtLeast(1)>720)
                options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
            return BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
        }
    }
}

/** Draw at the actual target size, avoiding a magnified low-resolution system icon. */
private class ArtworkPlaceholder:Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas){
        val area=RectF(bounds)
        paint.color=TvUi.background;paint.style=Paint.Style.FILL;canvas.drawRect(area,paint)
        val width=minOf(area.width(),area.height())*.38f
        val icon=RectF(area.centerX()-width/2,area.centerY()-width*.38f,area.centerX()+width/2,area.centerY()+width*.38f)
        paint.color=TvUi.muted;paint.alpha=130;paint.style=Paint.Style.STROKE;paint.strokeWidth=(width*.035f).coerceAtLeast(1f)
        canvas.drawRoundRect(icon,width*.05f,width*.05f,paint)
        val mountain=Path().apply{moveTo(icon.left+width*.12f,icon.bottom-width*.13f);lineTo(icon.left+width*.36f,icon.top+width*.3f);lineTo(icon.left+width*.55f,icon.top+width*.47f);lineTo(icon.left+width*.71f,icon.top+width*.33f);lineTo(icon.right-width*.12f,icon.bottom-width*.13f)}
        canvas.drawPath(mountain,paint);paint.style=Paint.Style.FILL
        canvas.drawCircle(icon.left+width*.25f,icon.top+width*.2f,width*.045f,paint)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
