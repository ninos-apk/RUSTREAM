package de.iptv.tv

import okhttp3.HttpUrl
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Decisions of the artwork pipeline without Android types, so JVM tests cover them. */
internal object ArtworkPolicy {
    enum class Failure(val permanent:Boolean) {
        /** Unusable address, refused redirect, oversized or undecodable body. */
        INVALID(true),
        /** 404/410: the provider has no such image. */
        MISSING(true),
        /** 401/403: credentials or geo-blocking. */
        DENIED(true),
        /** Timeouts, resets, unreachable hosts, 408/425/429 and 5xx. */
        TRANSIENT(false)
    }
    fun classify(code:Int)=when(code){
        404,410->Failure.MISSING
        401,403->Failure.DENIED
        408,425,429,in 500..599->Failure.TRANSIENT
        else->Failure.INVALID
    }
    /** Failures that say nothing about the image itself but about its host being unreachable. */
    fun unreachable(error:IOException)=error is UnknownHostException||error is ConnectException||error is NoRouteToHostException||
        (error is SocketTimeoutException&&error.message?.contains("connect",true)==true)

    /** Pause after [failures] consecutive transient failures. Retries only happen while a view on
     * screen still waits; the steps keep a broken host at one request per poster every few minutes. */
    fun retryDelay(failures:Int)=RETRY_STEPS[(failures-1).coerceIn(0,RETRY_STEPS.size-1)]
    private val RETRY_STEPS=longArrayOf(1_000,3_000,9_000,30_000,60_000,120_000,300_000)
    /** A missing or refused image is asked for again only after this pause, e.g. on a later visit. */
    const val PERMANENT_PAUSE=30*60_000L

    /** Disk freshness in milliseconds, or null when the response must not be stored (no-store).
     * Poster addresses practically never change their content and many image hosts send no or very
     * short lifetimes, so a lifetime is kept between one and thirty days; stale entries are still shown
     * and revalidated in the background. */
    fun freshness(noStore:Boolean,maxAgeSeconds:Int,expires:Long?,date:Long?,now:Long):Long?{
        if(noStore)return null
        val server=when{
            maxAgeSeconds>=0->maxAgeSeconds*1000L
            expires!=null->expires-(date?:now)
            else->DEFAULT_FRESH
        }
        return server.coerceIn(MIN_FRESH,MAX_FRESH)
    }
    private const val DAY=86_400_000L
    const val MIN_FRESH=DAY
    const val DEFAULT_FRESH=7*DAY
    const val MAX_FRESH=30*DAY

    /** An https image never continues over plain http; http may upgrade to https. */
    fun redirectAllowed(from:HttpUrl,to:HttpUrl)=!from.isHttps||to.isHttps

    private val TMDB_WIDTHS=intArrayOf(154,185,342,500,780)
    private val TMDB_SIZE=Regex("^w(\\d+)")
    /** TMDB serves fixed widths under /t/p/<size>/; the smallest one that still covers [width] px
     * replaces a larger one. Other hosts are never rewritten: resizing support cannot be assumed. */
    fun sized(url:HttpUrl,width:Int):HttpUrl?{
        if(url.host!="image.tmdb.org")return null
        val segments=url.pathSegments
        if(segments.size<4||segments[0]!="t"||segments[1]!="p")return null
        val current=if(segments[2]=="original")Int.MAX_VALUE else TMDB_SIZE.find(segments[2])?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val wanted=TMDB_WIDTHS.firstOrNull{it>=width} ?: return null
        return if(wanted<current)url.newBuilder().setPathSegment(2,"w$wanted").build() else null
    }

    /** Size inside [maxWidth]×[maxHeight] with the image's aspect ratio, never enlarged. */
    fun fitted(width:Int,height:Int,maxWidth:Int,maxHeight:Int):Pair<Int,Int>{
        if(width<=maxWidth&&height<=maxHeight)return width to height
        // Integer arithmetic: the bounding side is exact, the other one rounds down.
        return if(width.toLong()*maxHeight>=height.toLong()*maxWidth)maxWidth to (height.toLong()*maxWidth/width).toInt().coerceAtLeast(1)
            else (width.toLong()*maxHeight/height).toInt().coerceAtLeast(1) to maxHeight
    }
    /** Largest power-of-two subsampling that still keeps at least [targetWidth]×[targetHeight]. */
    fun sampleSize(width:Int,height:Int,targetWidth:Int,targetHeight:Int):Int{
        var sample=1
        while(width/(sample*2)>=targetWidth&&height/(sample*2)>=targetHeight)sample*=2
        return sample
    }
    /** Provider bytes already about the decoded size are stored as they are; larger originals are
     * stored re-encoded at the decoded size instead of keeping megabytes per poster. */
    fun keepOriginal(bytes:Int,width:Int,height:Int,decodedWidth:Int,decodedHeight:Int)=
        bytes<=96*1024&&width<=decodedWidth*3/2+1&&height<=decodedHeight*3/2+1
    /** Decode sizes in 16 px steps, so small layout differences share decoded and cached images. */
    fun bucket(pixels:Int)=((pixels.coerceAtLeast(16)+15)/16)*16

    /** Adapter positions to warm, nearest first: [ahead] rows in the scroll direction, then [behind]
     * rows against it. Visible positions are excluded; the views on screen request themselves. */
    fun prefetchWindow(first:Int,last:Int,columns:Int,count:Int,forward:Boolean,ahead:Int,behind:Int):List<Int>{
        if(first<0||last<first||columns<=0||count<=0)return emptyList()
        val after=(last+1)..minOf(count-1,last+maxOf(0,if(forward)ahead else behind)*columns)
        val before=(first-1) downTo maxOf(0,first-maxOf(0,if(forward)behind else ahead)*columns)
        return if(forward)after.toList()+before.toList() else before.toList()+after.toList()
    }
    /** About 2% of the cache volume, 32-160 MiB, and never more than a quarter of the free space. */
    fun diskBudget(total:Long,usable:Long):Long{
        val mib=1024L*1024
        return (total/50).coerceIn(32*mib,160*mib).coerceAtMost(maxOf(8*mib,usable/4))
    }
}
