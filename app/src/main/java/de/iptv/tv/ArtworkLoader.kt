package de.iptv.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import android.widget.ImageView
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

/** Two bounded workers. Original artwork URLs never enter logs or a disk cache. */
class ArtworkLoader : java.io.Closeable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    private val cache=object:LruCache<String,Bitmap>(16*1024*1024){
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    private val failures=LinkedHashMap<String,Long>()
    private data class Task(val view:WeakReference<ImageView>,val key:String,val address:suspend ()->String?)
    private val queue=Channel<Task>(48)
    init { repeat(2) { scope.launch { for(task in queue) {
        val view=task.view.get() ?: continue
        if(withContext(Dispatchers.Main){view.tag!=task.key})continue
        var bitmap=cache.get(task.key)
        if(bitmap==null) {
            try {
                val url=task.address()?.toHttpUrlOrNull()
                if(url!=null) client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if(response.isSuccessful)response.body?.let { body ->
                        if(body.contentLength()<=8L*1024*1024) {
                            val output=ByteArrayOutputStream()
                            body.byteStream().use { BoundedCopy.copy(it,output,8L*1024*1024) }
                            bitmap=decode(output.toByteArray())
                        }
                    }
                }
            } catch(error:Exception) { if(error is kotlinx.coroutines.CancellationException)throw error }
            bitmap?.let{cache.put(task.key,it)} ?: synchronized(failures){
                failures[task.key]=SystemClock.elapsedRealtime()
                while(failures.size>96)failures.remove(failures.keys.first())
            }
        }
        withContext(Dispatchers.Main){if(view.tag==task.key)bitmap?.let{view.setImageBitmap(it)}}
    } } } }
    fun bind(view:ImageView,key:String,address:suspend ()->String?) {
        view.tag=key
        val bitmap=cache.get(key)
        if(bitmap!=null)view.setImageBitmap(bitmap) else view.setImageDrawable(ArtworkPlaceholder())
        if(bitmap!=null)return
        val failed=synchronized(failures){failures[key]}
        if(failed!=null&&SystemClock.elapsedRealtime()-failed<60_000)return
        queue.trySend(Task(WeakReference(view),key,address))
    }
    fun clear(){cache.evictAll()}
    override fun close(){queue.close();scope.cancel();client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown();clear()}
    companion object {
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
