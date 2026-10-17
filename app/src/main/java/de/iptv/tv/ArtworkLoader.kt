package de.iptv.tv

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Posters and logos: memory → disk → network, scheduled for what is on screen first.
 *
 * Scheduling state belongs to the main thread. Disk reads, downloads and disk writes run on separate
 * bounded I/O lanes, so a slow host never delays posters that are already on disk, and speculative
 * prefetching never takes the last connections from posters on screen. Every bound view keeps its
 * request until it shows the image: RecyclerView re-attaches cached rows without binding them again,
 * so an attach listener asks again for those, and transient failures are retried with backoff while
 * a view still waits. Original image addresses stay in memory only: disk names are keyed HMACs and
 * logs carry counts, never addresses.
 */
class ArtworkLoader(context:Context?=null,cacheDirectory:File?=context?.let{File(it.cacheDir,"artwork")}):java.io.Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate+CoroutineExceptionHandler{_,error->Log.w(TAG,"task failed: ${error.javaClass.simpleName}")})
    private val diskLane=Dispatchers.IO.limitedParallelism(DISK_SLOTS)
    private val networkLane=Dispatchers.IO.limitedParallelism(NETWORK_SLOTS)
    // One writer: bursts of new posters must not compete with reads for the flash storage.
    private val writeLane=Dispatchers.IO.limitedParallelism(1)
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS)
        // Redirects are followed below: http may upgrade to https, https never continues over http.
        .followRedirects(false).followSslRedirects(false).build()
    // Decoded images are shared by address: episodes of a series and repeated logos reuse one
    // decode, also across a catalog refresh that changes every row key.
    private val memory=object:LruCache<String,Bitmap>(memoryBudget(Runtime.getRuntime().maxMemory()/8,16,64)){
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    @Volatile private var diskCache:ArtworkDiskCache?=null
    private val disk:Deferred<ArtworkDiskCache?> = if(cacheDirectory==null)CompletableDeferred(null) else scope.async(diskLane){openDisk(cacheDirectory)}
    private val diskEnabled=cacheDirectory!=null

    /** What a view shows or waits for. */
    private class Binding(val key:String,val resolve:suspend ()->String?,var sequence:Long){var address:String?=null;var shown=false}
    private val bindings=WeakHashMap<ImageView,Binding>()
    private val watched=Collections.newSetFromMap(WeakHashMap<ImageView,Boolean>())
    /** Views whose row key still needs its address, and views waiting for an image by address. */
    private val unresolved=HashMap<String,MutableSet<ImageView>>()
    private val waiting=HashMap<String,MutableSet<ImageView>>()
    /** Queued work; [score] is its interest from the latest dispatch pass. */
    private abstract class Work(var sequence:Long){var score=NONE_WANTED}
    private class Lookup(val key:String,val resolve:suspend ()->String?,sequence:Long):Work(sequence){var running=false}
    private val lookups=LinkedHashMap<String,Lookup>()
    private enum class Phase{DISK,DISK_RUNNING,NETWORK,NETWORK_RUNNING}
    private class Validators(val etag:String?,val lastModified:String?)
    private class Load(val address:String,var phase:Phase,sequence:Long,val revalidate:Validators?=null):Work(sequence){var speculative=false}
    // Most wanted first; among equals the latest request, i.e. the rows the user moves towards.
    private val byInterest=Comparator<Work>{a,b->if(a.score!=b.score)a.score.compareTo(b.score) else b.sequence.compareTo(a.sequence)}
    private val loads=LinkedHashMap<String,Load>()
    private class Failure(val kind:ArtworkPolicy.Failure,val count:Int,val failedAt:Long,var retryAt:Long)
    private val failures=object:LinkedHashMap<String,Failure>(64,0.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Failure>)=size>2048
    }
    /** Row key → address, or [NONE] for rows without artwork. */
    private val addresses=LruCache<String,String>(4096)
    /** Ranks of the latest prefetch window, by row key and, once known, by address. */
    private val prefetchKeys=HashMap<String,Int>()
    private val prefetchAddresses=HashMap<String,Int>()
    private var prefetchList=emptyList<String>()
    private var decodeAhead=0
    private val prefetched=object:LinkedHashMap<String,Boolean>(64,0.75f,false){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Boolean>)=size>1024
    }
    private val revalidated=HashSet<String>()
    private var lookupsRunning=0;private var diskRunning=0;private var networkRunning=0;private var speculativeRunning=0
    private var sequence=0L
    private var active=true
    @Volatile private var closed=false
    private var pumpPosted=false
    private var timerAt=Long.MAX_VALUE
    private var target=ArtworkPolicy.bucket(DEFAULT_WIDTH) to ArtworkPolicy.bucket(DEFAULT_HEIGHT)
    private val pump=Runnable{pumpPosted=false;onMain(Counter.MAIN_DISPATCH,::dispatch)}
    private val timer=Runnable{timerAt=Long.MAX_VALUE;onMain(Counter.MAIN_DISPATCH,::dispatch)}

    private class Host(var failures:Int=0,var pausedUntil:Long=0,var pause:Long=HOST_PAUSE)
    private val hosts=HashMap<String,Host>()
    /** Hosts whose connections broke (main thread); their first success retries the other posters. */
    private val brokenHosts=HashSet<String>()

    private val counters=Array(Counter.entries.size){AtomicLong()}
    private enum class Counter{BINDS,DISPATCHES,SCORED,MAIN_BIND,MAIN_DISPATCH,MAIN_START,MAIN_DELIVER,MAIN_SHOW,MEMORY_HITS,DISK_HITS,DISK_MISSES,DISK_STALE,NETWORK_REQUESTS,NETWORK_BYTES,NOT_MODIFIED,RETRIES,
        NO_ARTWORK,PREFETCHED,PREFETCH_USED,DECODES,DECODE_MICROS,HOST_PAUSES,FAILED_INVALID,FAILED_MISSING,FAILED_DENIED,FAILED_TRANSIENT}
    private fun count(counter:Counter,by:Long=1){counters[counter.ordinal].addAndGet(by)}
    /** Main-thread CPU time of the loader itself (not wall time, which includes preemption). */
    private inline fun <T> onMain(counter:Counter,block:()->T):T{
        val started=android.os.Debug.threadCpuTimeNanos()
        try{return block()}finally{count(counter,(android.os.Debug.threadCpuTimeNanos()-started)/1000)}
    }
    private var firstBindAt=0L;private var firstImageMs=-1L
    private var failureLogAt=0L;private val failureLog=IntArray(ArtworkPolicy.Failure.entries.size)

    private val connectivity=context?.applicationContext?.getSystemService(ConnectivityManager::class.java)
    private val networkCallback=object:ConnectivityManager.NetworkCallback(){
        override fun onAvailable(network:Network){main.post{networkReturned()}}
    }
    init{
        try{connectivity?.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networkCallback)}
        catch(error:RuntimeException){Log.w(TAG,"no connectivity callback: ${error.javaClass.simpleName}")}
    }

    /** Shows the image for row [key] in [view]; [address] looks up the image address once per key.
     * Main thread. Rebinding a view replaces its request; recycled views are [unbind]ed. */
    fun bind(view:ImageView,key:String,address:suspend ()->String?)=onMain(Counter.MAIN_BIND){
        count(Counter.BINDS)
        val shown=(view.drawable as? BitmapDrawable)?.bitmap?.takeIf{view.tag==key}
        release(view)
        view.tag=key
        watch(view)
        val binding=Binding(key,address,++sequence).also{bindings[view]=it}
        if(firstBindAt==0L)firstBindAt=SystemClock.elapsedRealtime()
        val known=addresses.get(key)
        if(known==NONE){placeholder(view);return@onMain}
        if(known!=null){
            binding.address=known
            memory.get(known)?.let{show(view,binding,it);hit(known);return@onMain}
            // A rebind of the same row (adapter refresh) keeps its image instead of flashing a placeholder.
            if(shown!=null){memory.put(known,shown);binding.shown=true;return@onMain}
            placeholder(view)
            wait(view,known,binding.sequence,revisit=true)
        }else{
            if(shown!=null){binding.shown=true;return@onMain}
            placeholder(view)
            unresolved.getOrPut(key,::viewSet).add(view)
            lookups.getOrPut(key){Lookup(key,address,binding.sequence)}.sequence=binding.sequence
        }
        schedule()
    }
    /** A recycled view requests nothing any more. Main thread. */
    fun unbind(view:ImageView){release(view);view.tag=null;schedule()}

    /** Replaces the speculative window: row keys with their address lookup, nearest first. The first
     * [decode] are decoded into memory, the rest are only downloaded into the disk cache. Main thread. */
    fun prefetch(items:List<Pair<String,suspend ()->String?>>,decode:Int=items.size)=onMain(Counter.MAIN_BIND){
        val keys=items.map{it.first}
        if(keys==prefetchList&&decode==decodeAhead)return@onMain
        prefetchList=keys;decodeAhead=decode
        prefetchKeys.clear();prefetchAddresses.clear()
        items.forEachIndexed{rank,(key,resolve)->
            prefetchKeys[key]=rank
            when(val address=addresses.get(key)){
                null->lookups.getOrPut(key){Lookup(key,resolve,0)}
                NONE->{}
                else->{prefetchAddresses[address]=minOf(rank,prefetchAddresses[address]?:rank);if(memory.get(address)==null)want(address,0,revisit=false)}
            }
        }
        schedule()
    }

    /** Decoded size of the largest artwork on screen, a grid poster, in pixels. Main thread. */
    fun setTargetSize(width:Int,height:Int){
        if(width<=0||height<=0)return
        val size=ArtworkPolicy.bucket(width) to ArtworkPolicy.bucket(height)
        if(size==target)return
        target=size
        // Images decoded for another size would show blurred or waste memory; disk names include the size.
        memory.evictAll()
    }
    /** Stopped activities start no new work; running downloads still complete into the caches. */
    fun setActive(value:Boolean){active=value;if(value)schedule()}
    @Suppress("DEPRECATION")
    fun trim(level:Int){
        when{
            level>=ComponentCallbacks2.TRIM_MEMORY_MODERATE->memory.evictAll()
            level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND->memory.trimToSize(memory.maxSize()/4)
            level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW->memory.trimToSize(memory.maxSize()/2)
        }
    }
    fun clear(){memory.evictAll();addresses.evictAll()}

    /** Counters for the diagnostics report: cache hits, network use, failures and current queues. */
    fun stats():JSONObject{
        fun value(counter:Counter)=counters[counter.ordinal].get()
        val decodes=value(Counter.DECODES)
        val cache=diskCache
        // Main-thread CPU ms: binding and attach handling, dispatching, completions (including
        // setImageBitmap, which is also reported alone as "show").
        return JSONObject().put("binds",value(Counter.BINDS)).put("dispatches",value(Counter.DISPATCHES)).put("scored",value(Counter.SCORED)).put("main_thread_cpu_ms",JSONObject()
                .put("bind",value(Counter.MAIN_BIND)/1000.0).put("dispatch",value(Counter.MAIN_DISPATCH)/1000.0)
                .put("dispatch_starting_work",value(Counter.MAIN_START)/1000.0).put("deliver",value(Counter.MAIN_DELIVER)/1000.0).put("show",value(Counter.MAIN_SHOW)/1000.0))
            .put("memory_hits",value(Counter.MEMORY_HITS)).put("memory_kib",memory.size()/1024).put("memory_max_kib",memory.maxSize()/1024)
            .put("disk_hits",value(Counter.DISK_HITS)).put("disk_misses",value(Counter.DISK_MISSES)).put("disk_stale",value(Counter.DISK_STALE))
            .put("disk_files",cache?.count?:0).put("disk_kib",(cache?.size?:0)/1024).put("disk_max_kib",(cache?.maxBytes?:0)/1024)
            .put("network_requests",value(Counter.NETWORK_REQUESTS)).put("network_kib",value(Counter.NETWORK_BYTES)/1024)
            .put("not_modified",value(Counter.NOT_MODIFIED)).put("retries",value(Counter.RETRIES)).put("host_pauses",value(Counter.HOST_PAUSES))
            .put("failed",JSONObject().put("invalid",value(Counter.FAILED_INVALID)).put("missing",value(Counter.FAILED_MISSING))
                .put("denied",value(Counter.FAILED_DENIED)).put("transient",value(Counter.FAILED_TRANSIENT)))
            .put("no_artwork",value(Counter.NO_ARTWORK)).put("prefetched",value(Counter.PREFETCHED)).put("prefetch_used",value(Counter.PREFETCH_USED))
            .put("decodes",decodes).put("decode_ms_avg",if(decodes==0L)0.0 else value(Counter.DECODE_MICROS)/1000.0/decodes)
            .put("first_image_ms",firstImageMs).put("target","${target.first}x${target.second}")
            .put("queued_loads",loads.size).put("queued_lookups",lookups.size).put("bound_views",bindings.size)
    }

    // Cancelling the scope ends queued work; blocking calls end through the HTTP dispatcher.
    override fun close(){
        closed=true
        try{connectivity?.unregisterNetworkCallback(networkCallback)}catch(_:RuntimeException){}
        main.removeCallbacks(pump);main.removeCallbacks(timer)
        scope.cancel()
        client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
        memory.evictAll()
    }

    private fun viewSet():MutableSet<ImageView> = Collections.newSetFromMap(WeakHashMap())
    private fun release(view:ImageView){
        val previous=bindings.remove(view) ?: return
        unresolved[previous.key]?.let{it.remove(view);if(it.isEmpty())unresolved.remove(previous.key)}
        previous.address?.let{address->waiting[address]?.let{it.remove(view);if(it.isEmpty())waiting.remove(address)}}
    }
    private fun watch(view:ImageView){if(watched.add(view))view.addOnAttachStateChangeListener(attachment)}
    private val attachment=object:View.OnAttachStateChangeListener{
        // RecyclerView re-attaches cached rows without binding them again. A request that failed or
        // was dropped meanwhile starts again here; otherwise such a poster would stay blank for good.
        override fun onViewAttachedToWindow(view:View)=onMain(Counter.MAIN_BIND){
            val image=view as? ImageView ?: return@onMain
            val binding=bindings[image] ?: return@onMain
            if(binding.shown||image.tag!=binding.key||closed)return@onMain
            binding.sequence=++sequence
            val address=binding.address
            if(address!=null){
                memory.get(address)?.let{show(image,binding,it);return@onMain}
                wait(image,address,binding.sequence,revisit=true)
            }else if(addresses.get(binding.key)!=NONE){
                unresolved.getOrPut(binding.key,::viewSet).add(image)
                lookups.getOrPut(binding.key){Lookup(binding.key,binding.resolve,binding.sequence)}.sequence=binding.sequence
            }
            schedule()
        }
        override fun onViewDetachedFromWindow(view:View){}
    }
    private fun placeholder(view:ImageView){if(view.drawable !is ArtworkPlaceholder)view.setImageDrawable(ArtworkPlaceholder())}
    private fun show(view:ImageView,binding:Binding,bitmap:Bitmap){
        val started=android.os.Debug.threadCpuTimeNanos()
        view.setImageBitmap(bitmap);binding.shown=true
        count(Counter.MAIN_SHOW,(android.os.Debug.threadCpuTimeNanos()-started)/1000)
        if(firstImageMs<0&&firstBindAt!=0L)firstImageMs=SystemClock.elapsedRealtime()-firstBindAt
    }
    private fun hit(address:String){count(Counter.MEMORY_HITS);if(prefetched.remove(address)!=null)count(Counter.PREFETCH_USED)}
    private fun wait(view:ImageView,address:String,order:Long,revisit:Boolean){
        waiting.getOrPut(address,::viewSet).add(view)
        want(address,order,revisit)
    }
    /** Queues [address] unless it is already loading or a permanent failure is still paused. A
     * revisit by the user retries a transient failure after a short pause instead of the backoff. */
    private fun want(address:String,order:Long,revisit:Boolean){
        loads[address]?.let{it.sequence=maxOf(it.sequence,order);return}
        val failure=failures[address]
        if(failure!=null){
            if(failure.kind.permanent&&SystemClock.elapsedRealtime()<failure.retryAt)return
            if(!failure.kind.permanent&&revisit)failure.retryAt=minOf(failure.retryAt,failure.failedAt+REVISIT_PAUSE)
        }
        loads[address]=Load(address,if(onDisk(address))Phase.DISK else Phase.NETWORK,order)
    }
    /** Only images in the disk index take the disk lane; known misses go straight to the network
     * instead of queueing behind disk reads. Until the index is open every image is checked. */
    private fun onDisk(address:String):Boolean{
        if(!diskEnabled)return false
        val cache=diskCache ?: return true
        return cache.contains(cache.name(address,variant(target.first,target.second))).also{if(!it)count(Counter.DISK_MISSES)}
    }
    /** 0 shown on screen, 1 bound but detached or hidden (RecyclerView cache and prefetch), 2+rank speculative. */
    private fun interest(views:Set<ImageView>?,rank:Int?):Int{
        var best=if(rank!=null)PREFETCH+rank else NONE_WANTED
        views?.forEach{view->
            val binding=bindings[view]
            if(binding!=null&&!binding.shown&&view.tag==binding.key){
                if(view.isShown)return VISIBLE
                best=BOUND
            }
        }
        return best
    }
    private fun interest(load:Load):Int{
        val wanted=interest(waiting[load.address],prefetchAddresses[load.address])
        return if(load.revalidate!=null)minOf(wanted,REVALIDATE) else wanted
    }
    private fun interest(lookup:Lookup)=interest(unresolved[lookup.key],prefetchKeys[lookup.key])
    private fun schedule(){if(!pumpPosted&&!closed){pumpPosted=true;main.post(pump)}}

    /** Starts the most wanted work of each lane; queued work nobody wants any more is dropped.
     * Each queued item is scored once per dispatch, not once per free slot. */
    private fun dispatch(){
        if(closed||!active)return
        count(Counter.DISPATCHES)
        // The core answers one call at a time; a few in flight keep it busy without delaying page loads.
        if(lookupsRunning<LOOKUP_SLOTS&&lookups.isNotEmpty()){
            val ready=ArrayList<Lookup>()
            val iterator=lookups.values.iterator()
            while(iterator.hasNext()){
                val lookup=iterator.next()
                if(lookup.running)continue
                lookup.score=interest(lookup);count(Counter.SCORED)
                if(lookup.score==NONE_WANTED)iterator.remove() else ready+=lookup
            }
            ready.sortWith(byInterest)
            for(lookup in ready){if(lookupsRunning>=LOOKUP_SLOTS)break;onMain(Counter.MAIN_START){startLookup(lookup)}}
        }
        if(diskRunning>=DISK_SLOTS&&networkRunning>=NETWORK_SLOTS)return
        val now=SystemClock.elapsedRealtime()
        var wake=Long.MAX_VALUE
        val disk=ArrayList<Load>();val network=ArrayList<Load>()
        val iterator=loads.values.iterator()
        while(iterator.hasNext()){
            val load=iterator.next()
            if(load.phase!=Phase.DISK&&load.phase!=Phase.NETWORK)continue
            load.score=interest(load);count(Counter.SCORED)
            if(load.score==NONE_WANTED){iterator.remove();continue}
            if(load.phase==Phase.DISK){disk+=load;continue}
            val retryAt=failures[load.address]?.retryAt ?: 0L
            if(retryAt>now){if(load.score<PREFETCH)wake=minOf(wake,retryAt);continue}
            network+=load
        }
        disk.sortWith(byInterest)
        for(load in disk){if(diskRunning>=DISK_SLOTS)break;onMain(Counter.MAIN_START){startDisk(load)}}
        network.sortWith(byInterest)
        for(load in network){
            if(networkRunning>=NETWORK_SLOTS)break
            // Speculative downloads never take the connections that posters on screen need.
            if(load.score>=PREFETCH&&speculativeRunning>=SPECULATIVE_SLOTS)continue
            onMain(Counter.MAIN_START){startNetwork(load)}
        }
        if(wake!=Long.MAX_VALUE&&wake<timerAt){main.removeCallbacks(timer);timerAt=wake;main.postDelayed(timer,(wake-now).coerceAtLeast(1))}
    }
    private fun startLookup(lookup:Lookup){
        lookup.running=true;lookupsRunning++
        scope.launch{
            val address=try{lookup.resolve()?.takeIf{it.isNotBlank()}}catch(error:Exception){if(error is CancellationException)throw error;null}
            lookupsRunning--
            onMain(Counter.MAIN_DELIVER){completeLookup(lookup,address)}
        }
    }
    private fun completeLookup(lookup:Lookup,address:String?){
        lookups.remove(lookup.key)
        addresses.put(lookup.key,address ?: NONE)
        val views=unresolved.remove(lookup.key)
        if(address==null)count(Counter.NO_ARTWORK)
        else{
            prefetchKeys[lookup.key]?.let{rank->prefetchAddresses[address]=minOf(rank,prefetchAddresses[address]?:rank)}
            views?.forEach{view->
                val binding=bindings[view]
                if(binding==null||binding.key!=lookup.key||view.tag!=lookup.key)return@forEach
                binding.address=address
                val bitmap=memory.get(address)
                if(bitmap!=null){show(view,binding,bitmap);hit(address)} else wait(view,address,binding.sequence,revisit=true)
            }
            if(prefetchAddresses.containsKey(address)&&memory.get(address)==null)want(address,0,revisit=false)
        }
        schedule()
    }
    private fun decodeFor(load:Load)=interest(load)<PREFETCH+decodeAhead
    private fun startDisk(load:Load){
        load.phase=Phase.DISK_RUNNING;diskRunning++
        load.speculative=load.score>=PREFETCH
        val (width,height)=target;val decode=load.score<PREFETCH+decodeAhead
        scope.launch{
            val hit=withContext(diskLane){readDisk(load.address,width,height,decode)}
            diskRunning--
            onMain(Counter.MAIN_DELIVER){completeDisk(load,hit)}
        }
    }
    private fun completeDisk(load:Load,hit:DiskHit?){
        if(hit==null){count(Counter.DISK_MISSES);load.phase=Phase.NETWORK;schedule();return}
        count(Counter.DISK_HITS)
        loads.remove(load.address)
        if(hit.bitmap!=null){
            if(load.speculative)prefetchDone(load.address)
            deliver(load.address,hit.bitmap)
        }else if(decodeFor(load)){
            // Became visible while only its presence on disk was checked.
            want(load.address,load.sequence,revisit=false)
        }
        if(hit.stale!=null){
            count(Counter.DISK_STALE)
            // Shown at once; revalidated once per session over an otherwise idle connection.
            if(revalidated.add(load.address)&&!loads.containsKey(load.address))loads[load.address]=Load(load.address,Phase.NETWORK,0,hit.stale)
        }
        schedule()
    }
    /** A view already waiting for a speculative result was helped by it; otherwise a later bind is. */
    private fun prefetchDone(address:String){
        if(waiting[address]?.isNotEmpty()==true)count(Counter.PREFETCH_USED) else prefetched[address]=true
    }
    private class DiskHit(val bitmap:Bitmap?,val stale:Validators?)
    private suspend fun readDisk(address:String,width:Int,height:Int,decode:Boolean):DiskHit?{
        val cache=disk.await() ?: return null
        val name=cache.name(address,variant(width,height))
        val entry=cache.get(name) ?: return null
        val bitmap=if(decode)(decodeTimed(entry.payload,width,height)?.bitmap ?: run{cache.remove(name);return null}) else null
        val stale=if(System.currentTimeMillis()>=entry.expires)Validators(entry.etag,entry.lastModified) else null
        return DiskHit(bitmap,stale)
    }
    private fun deliver(address:String,bitmap:Bitmap){
        memory.put(address,bitmap)
        waiting.remove(address)?.forEach{view->
            val binding=bindings[view] ?: return@forEach
            if(binding.address==address&&view.tag==binding.key)show(view,binding,bitmap)
        }
    }
    private fun startNetwork(load:Load){
        load.phase=Phase.NETWORK_RUNNING;networkRunning++
        load.speculative=load.score>=PREFETCH;if(load.speculative)speculativeRunning++
        if(failures[load.address]!=null)count(Counter.RETRIES)
        val (width,height)=target;val keep=load.score<PREFETCH+decodeAhead
        scope.launch{
            val result=withContext(networkLane){fetch(load.address,width,height,load.revalidate)}
            networkRunning--;if(load.speculative)speculativeRunning--
            onMain(Counter.MAIN_DELIVER){completeNetwork(load,result,keep)}
        }
    }
    private fun completeNetwork(load:Load,result:Fetch,keep:Boolean){
        if(loads[load.address]===load)loads.remove(load.address)
        when(result){
            is Fetched->{
                failures.remove(load.address)
                if(load.revalidate!=null){
                    // New content at the same address: replace what bound views show.
                    memory.put(load.address,result.bitmap)
                    for((view,binding) in bindings)if(binding.address==load.address&&view.tag==binding.key)show(view,binding,result.bitmap)
                }else if(keep||waiting[load.address]?.isNotEmpty()==true){
                    if(load.speculative)prefetchDone(load.address)
                    deliver(load.address,result.bitmap)
                }
                if(load.speculative)count(Counter.PREFETCHED)
                if(brokenHosts.isNotEmpty())hostOf(load.address)?.let{if(brokenHosts.remove(it))hostRecovered(it)}
            }
            is NotModified->count(Counter.NOT_MODIFIED)
            is Failed->{
                if(result.io)hostOf(load.address)?.let(brokenHosts::add)
                if(load.revalidate==null)failed(load,result.kind)
            }
        }
        // A revalidation can have held the address while a view started to wait for it.
        if(load.revalidate!=null&&waiting[load.address]?.isNotEmpty()==true)want(load.address,load.sequence,revisit=false)
        schedule()
    }
    private fun hostOf(address:String)=address.toHttpUrlOrNull()?.host
    /** A host answering again after broken connections: its waiting posters retry now and start their
     * backoff afresh, since the outage was the host's, not theirs. HTTP errors of single images never
     * mark a host, so they cannot cause a burst. */
    private fun hostRecovered(host:String){
        val now=SystemClock.elapsedRealtime()
        for(entry in failures.entries){
            val failure=entry.value
            if(!failure.kind.permanent&&hostOf(entry.key)==host)entry.setValue(Failure(failure.kind,0,failure.failedAt,minOf(failure.retryAt,now)))
        }
    }
    private fun failed(load:Load,kind:ArtworkPolicy.Failure){
        val now=SystemClock.elapsedRealtime()
        val count=(failures[load.address]?.takeIf{it.kind==kind}?.count ?: 0)+1
        failures[load.address]=Failure(kind,count,now,now+if(kind.permanent)ArtworkPolicy.PERMANENT_PAUSE else ArtworkPolicy.retryDelay(count))
        count(when(kind){ArtworkPolicy.Failure.INVALID->Counter.FAILED_INVALID;ArtworkPolicy.Failure.MISSING->Counter.FAILED_MISSING
            ArtworkPolicy.Failure.DENIED->Counter.FAILED_DENIED;ArtworkPolicy.Failure.TRANSIENT->Counter.FAILED_TRANSIENT})
        failureLog[kind.ordinal]++
        if(now-failureLogAt>=60_000){
            failureLogAt=now
            Log.w(TAG,"image failures since last report: "+ArtworkPolicy.Failure.entries.joinToString{"${it.name.lowercase()}=${failureLog[it.ordinal]}"})
            failureLog.fill(0)
        }
        // Views still waiting keep the request: the dispatcher retries it when the backoff ends.
        if(!kind.permanent&&waiting[load.address]?.isNotEmpty()==true)loads[load.address]=Load(load.address,Phase.NETWORK,load.sequence)
    }
    private fun networkReturned(){
        if(closed)return
        synchronized(hosts){hosts.clear()}
        brokenHosts.clear()
        failures.entries.removeAll{!it.value.kind.permanent}
        for((view,binding) in bindings){
            if(binding.shown||view.tag!=binding.key||!view.isShown)continue
            binding.address?.let{waiting.getOrPut(it,::viewSet).add(view);want(it,binding.sequence,revisit=false)}
        }
        schedule()
    }

    private sealed class Fetch
    private class Fetched(val bitmap:Bitmap):Fetch()
    private object NotModified:Fetch()
    /** [io]: the connection broke (reset, timeout, offline), as opposed to an HTTP error status. */
    private class Failed(val kind:ArtworkPolicy.Failure,val io:Boolean=false):Fetch()
    private sealed class Download
    private class Body(val bytes:ByteArray,val freshness:Long?,val etag:String?,val lastModified:String?):Download()
    private class Unchanged(val freshness:Long?):Download()
    private class Refused(val kind:ArtworkPolicy.Failure):Download()

    /** Network lane: download, decode at the target size and store the result. */
    private fun fetch(address:String,width:Int,height:Int,validators:Validators?):Fetch{
        val original=address.toHttpUrlOrNull() ?: return Failed(ArtworkPolicy.Failure.INVALID)
        val host=original.host
        if(hostPaused(host))return Failed(ArtworkPolicy.Failure.TRANSIENT)
        var failure=ArtworkPolicy.Failure.INVALID
        // A resized variant first where the host is known to offer one; the original if that is missing.
        for(url in listOfNotNull(ArtworkPolicy.sized(original,width),original)){
            val response=try{download(url,validators)}
            catch(error:IOException){if(ArtworkPolicy.unreachable(error))hostFailed(host);return Failed(ArtworkPolicy.Failure.TRANSIENT,io=true)}
            catch(_:IllegalArgumentException){Refused(ArtworkPolicy.Failure.INVALID)}
            hostReachable(host)
            when(response){
                is Body->{
                    val decoded=decodeTimed(response.bytes,width,height) ?: return Failed(ArtworkPolicy.Failure.INVALID)
                    response.freshness?.let{store(address,width,height,response,decoded,it)}
                    return Fetched(decoded.bitmap)
                }
                is Unchanged->{
                    val cache=diskCache
                    if(cache!=null&&response.freshness!=null)scope.launch(writeLane){
                        val name=cache.name(address,variant(width,height))
                        cache.get(name)?.let{cache.put(name,ArtworkDiskCache.Entry(it.payload,System.currentTimeMillis()+response.freshness,it.etag,it.lastModified))}
                    }
                    return NotModified
                }
                is Refused->{failure=response.kind;if(failure!=ArtworkPolicy.Failure.MISSING)return Failed(failure)}
            }
        }
        return Failed(failure)
    }
    private fun download(start:HttpUrl,validators:Validators?):Download{
        var url=start
        repeat(MAX_REDIRECTS+1){
            val request=Request.Builder().url(url).header("Accept",ACCEPT).apply{
                validators?.etag?.let{header("If-None-Match",it)};validators?.lastModified?.let{header("If-Modified-Since",it)}
            }.build()
            count(Counter.NETWORK_REQUESTS)
            client.newCall(request).execute().use{response->
                if(response.isRedirect){
                    val next=response.header("Location")?.let(url::resolve)
                    if(next==null||!ArtworkPolicy.redirectAllowed(url,next))return Refused(ArtworkPolicy.Failure.INVALID)
                    url=next;return@repeat
                }
                val control=response.cacheControl
                val freshness=ArtworkPolicy.freshness(control.noStore,control.maxAgeSeconds,response.headers.getDate("Expires")?.time,
                    response.headers.getDate("Date")?.time,System.currentTimeMillis())
                if(response.code==304&&validators!=null)return Unchanged(freshness)
                if(!response.isSuccessful)return Refused(ArtworkPolicy.classify(response.code))
                val body=response.body ?: return Refused(ArtworkPolicy.Failure.INVALID)
                val length=body.contentLength()
                if(length>MAX_BYTES)return Refused(ArtworkPolicy.Failure.INVALID)
                val output=ByteArrayOutputStream(if(length in 1..MAX_BYTES)length.toInt() else 32*1024)
                body.byteStream().use{BoundedCopy.copy(it,output,MAX_BYTES)}
                count(Counter.NETWORK_BYTES,output.size().toLong())
                return Body(output.toByteArray(),freshness,response.header("ETag"),response.header("Last-Modified"))
            }
        }
        return Refused(ArtworkPolicy.Failure.INVALID)
    }
    /** Disk payload: small originals as they are, large ones re-encoded at the decoded size. */
    private fun store(address:String,width:Int,height:Int,body:Body,decoded:Decoded,freshness:Long){
        val cache=diskCache ?: return
        scope.launch(writeLane){
            val bitmap=decoded.bitmap
            val payload=if(ArtworkPolicy.keepOriginal(body.bytes.size,decoded.sourceWidth,decoded.sourceHeight,bitmap.width,bitmap.height))body.bytes else{
                @Suppress("DEPRECATION")
                val format=if(!bitmap.hasAlpha())Bitmap.CompressFormat.JPEG else if(Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                val output=ByteArrayOutputStream(32*1024)
                if(bitmap.compress(format,88,output)&&output.size()<body.bytes.size)output.toByteArray() else body.bytes
            }
            cache.put(cache.name(address,variant(width,height)),ArtworkDiskCache.Entry(payload,System.currentTimeMillis()+freshness,body.etag,body.lastModified))
        }
    }
    private fun decodeTimed(bytes:ByteArray,width:Int,height:Int):Decoded?{
        val started=SystemClock.elapsedRealtimeNanos()
        return decodeSized(bytes,width,height).also{
            count(Counter.DECODES);count(Counter.DECODE_MICROS,(SystemClock.elapsedRealtimeNanos()-started)/1000)
        }
    }
    private fun hostPaused(host:String)=synchronized(hosts){(hosts[host]?.pausedUntil ?: 0L)>SystemClock.elapsedRealtime()}
    /** A host that cannot be reached four times in a row is paused, so its posters fail fast and
     * leave the connections to other hosts instead of each waiting for the connect timeout. */
    private fun hostFailed(host:String)=synchronized(hosts){
        val state=hosts.getOrPut(host,::Host)
        if(++state.failures>=HOST_FAILURES){
            state.failures=0;state.pausedUntil=SystemClock.elapsedRealtime()+state.pause;state.pause=minOf(state.pause*2,HOST_PAUSE_MAX)
            count(Counter.HOST_PAUSES)
        }
    }
    private fun hostReachable(host:String){synchronized(hosts){hosts.remove(host)}}
    // Free space as it is, not getAllocatableBytes: a poster cache must not make the system clear
    // other apps' caches to grow.
    @android.annotation.SuppressLint("UsableSpace")
    private fun openDisk(directory:File):ArtworkDiskCache?=try{
        val started=SystemClock.elapsedRealtime()
        val parent=directory.absoluteFile.parentFile ?: error("no parent")
        val keyFile=File(parent,directory.name+".key")
        var salt=keyFile.takeIf{it.length()==32L}?.readBytes()
        if(salt==null){
            // Without the key the existing names cannot be matched again: start empty.
            directory.deleteRecursively()
            salt=ByteArray(32).also(SecureRandom()::nextBytes)
            val temporary=File(parent,keyFile.name+".tmp");temporary.writeBytes(salt)
            check(temporary.renameTo(keyFile))
        }
        val budget=ArtworkPolicy.diskBudget(parent.totalSpace,parent.usableSpace)
        ArtworkDiskCache(directory,salt,budget).also{
            it.open();diskCache=it
            Log.i(TAG,"disk cache: ${it.count} images, ${it.size/1024} KiB of ${budget/1048576} MiB, opened in ${SystemClock.elapsedRealtime()-started} ms")
        }
    }catch(error:Exception){Log.w(TAG,"disk cache unavailable: ${error.javaClass.simpleName}");null}

    internal class Decoded(val bitmap:Bitmap,val sourceWidth:Int,val sourceHeight:Int)
    companion object {
        private const val TAG="Artwork"
        private const val NONE=""
        private const val VISIBLE=0
        private const val BOUND=1
        private const val PREFETCH=2
        private const val REVALIDATE=100_000
        private const val NONE_WANTED=Int.MAX_VALUE
        private const val LOOKUP_SLOTS=3
        private const val DISK_SLOTS=2
        // Unreachable hosts are paused instead of holding these until the connect timeout.
        private const val NETWORK_SLOTS=4
        private const val SPECULATIVE_SLOTS=2
        private const val REVISIT_PAUSE=5_000L
        private const val HOST_FAILURES=4
        private const val HOST_PAUSE=15_000L
        private const val HOST_PAUSE_MAX=120_000L
        private const val MAX_REDIRECTS=5
        private const val MAX_BYTES=8L*1024*1024
        private const val MAX_DIMENSION=8192
        private const val DEFAULT_WIDTH=240
        private const val DEFAULT_HEIGHT=360
        // Some image CDNs negotiate WebP, which every supported Android version decodes.
        private const val ACCEPT="image/webp,image/jpeg,image/png,image/*;q=0.8"
        private fun variant(width:Int,height:Int)="${width}x$height"
        internal fun memoryBudget(bytes:Long,minMiB:Int,maxMiB:Int)=bytes.coerceIn(minMiB*1048576L,maxMiB*1048576L).toInt()
        /** Decodes within 480×720, the former fixed bound. */
        internal fun decode(bytes:ByteArray):Bitmap?=decodeSized(bytes,480,720)?.bitmap
        /** Decodes to fit [maxWidth]×[maxHeight]: power-of-two subsampling plus an exact scale in the
         * same pass, so a 2000×3000 original never allocates more than the poster on screen needs. */
        internal fun decodeSized(bytes:ByteArray,maxWidth:Int,maxHeight:Int):Decoded?{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            val width=bounds.outWidth;val height=bounds.outHeight
            if(width !in 1..MAX_DIMENSION||height !in 1..MAX_DIMENSION)return null
            val (targetWidth,targetHeight)=ArtworkPolicy.fitted(width,height,maxWidth,maxHeight)
            val options=BitmapFactory.Options().apply{
                inSampleSize=ArtworkPolicy.sampleSize(width,height,targetWidth,targetHeight)
                val sampled=(width+inSampleSize-1)/inSampleSize
                if(sampled>targetWidth){inScaled=true;inDensity=sampled;inTargetDensity=targetWidth}
            }
            val bitmap=try{BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)}catch(_:OutOfMemoryError){null} ?: return null
            // Pixel size is the display size; a decode density would rescale it in BitmapDrawable.
            bitmap.density=Bitmap.DENSITY_NONE
            return Decoded(bitmap,width,height)
        }
    }
}

/** Draw at the actual target size, avoiding a magnified low-resolution system icon. */
private class ArtworkPlaceholder:Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val mountain=Path()
    private val icon=RectF()
    private var width=0f
    override fun onBoundsChange(bounds:Rect){
        val area=RectF(bounds)
        width=minOf(area.width(),area.height())*.38f
        icon.set(area.centerX()-width/2,area.centerY()-width*.38f,area.centerX()+width/2,area.centerY()+width*.38f)
        mountain.reset();mountain.moveTo(icon.left+width*.12f,icon.bottom-width*.13f);mountain.lineTo(icon.left+width*.36f,icon.top+width*.3f)
        mountain.lineTo(icon.left+width*.55f,icon.top+width*.47f);mountain.lineTo(icon.left+width*.71f,icon.top+width*.33f);mountain.lineTo(icon.right-width*.12f,icon.bottom-width*.13f)
    }
    override fun draw(canvas:Canvas){
        paint.color=TvUi.background;paint.style=Paint.Style.FILL;canvas.drawRect(bounds,paint)
        paint.color=TvUi.muted;paint.alpha=130;paint.style=Paint.Style.STROKE;paint.strokeWidth=(width*.035f).coerceAtLeast(1f)
        canvas.drawRoundRect(icon,width*.05f,width*.05f,paint)
        canvas.drawPath(mountain,paint);paint.style=Paint.Style.FILL
        canvas.drawCircle(icon.left+width*.25f,icon.top+width*.2f,width*.045f,paint)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
