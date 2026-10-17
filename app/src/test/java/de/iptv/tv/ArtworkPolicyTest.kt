package de.iptv.tv

import de.iptv.tv.ArtworkPolicy.Failure
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ArtworkPolicyTest {
    @Test fun httpStatusSeparatesMissingDeniedAndTransient(){
        assertEquals(Failure.MISSING,ArtworkPolicy.classify(404));assertEquals(Failure.MISSING,ArtworkPolicy.classify(410))
        assertEquals(Failure.DENIED,ArtworkPolicy.classify(401));assertEquals(Failure.DENIED,ArtworkPolicy.classify(403))
        for(code in listOf(408,425,429,500,502,503,504))assertEquals("$code",Failure.TRANSIENT,ArtworkPolicy.classify(code))
        assertEquals(Failure.INVALID,ArtworkPolicy.classify(400));assertEquals(Failure.INVALID,ArtworkPolicy.classify(300))
        assertTrue(Failure.MISSING.permanent&&Failure.DENIED.permanent&&Failure.INVALID.permanent);assertFalse(Failure.TRANSIENT.permanent)
    }
    @Test fun onlyConnectionFailuresCountAgainstTheHost(){
        assertTrue(ArtworkPolicy.unreachable(UnknownHostException("x")))
        assertTrue(ArtworkPolicy.unreachable(ConnectException("refused")))
        assertTrue(ArtworkPolicy.unreachable(SocketTimeoutException("failed to connect to /10.0.0.1 after 5000ms")))
        assertFalse("A slow body is the image, not the host",ArtworkPolicy.unreachable(SocketTimeoutException("timeout")))
        assertFalse(ArtworkPolicy.unreachable(IOException("unexpected end of stream")))
    }
    @Test fun transientBackoffGrowsAndIsBounded(){
        val delays=(1..20).map(ArtworkPolicy::retryDelay)
        assertEquals(1_000L,delays[0]);assertEquals(3_000L,delays[1]);assertEquals(9_000L,delays[2])
        assertEquals(delays,delays.sorted())
        assertEquals(300_000L,delays.last());assertEquals(1_000L,ArtworkPolicy.retryDelay(0))
        assertTrue(ArtworkPolicy.PERMANENT_PAUSE>=delays.last())
    }
    @Test fun freshnessHonoursNoStoreAndBoundsServerLifetimes(){
        val now=1_000_000_000L;val day=86_400_000L
        assertNull(ArtworkPolicy.freshness(true,3600,null,null,now))
        assertEquals("Short lifetimes would download every visit",day,ArtworkPolicy.freshness(false,60,null,null,now))
        assertEquals(day,ArtworkPolicy.freshness(false,0,null,null,now))
        assertEquals(10*day,ArtworkPolicy.freshness(false,10*86_400,null,null,now))
        assertEquals(30*day,ArtworkPolicy.freshness(false,365*86_400,null,null,now))
        assertEquals("Expires relative to the server date",3*day,ArtworkPolicy.freshness(false,-1,now+3*day+5,now+5,now))
        assertEquals(day,ArtworkPolicy.freshness(false,-1,now-5,null,now))
        assertEquals(7*day,ArtworkPolicy.freshness(false,-1,null,null,now))
    }
    @Test fun redirectsMayUpgradeButNeverDowngrade(){
        val http="http://img.test/a.jpg".toHttpUrl();val https="https://img.test/a.jpg".toHttpUrl()
        assertTrue(ArtworkPolicy.redirectAllowed(http,https));assertTrue(ArtworkPolicy.redirectAllowed(http,http))
        assertTrue(ArtworkPolicy.redirectAllowed(https,https));assertFalse(ArtworkPolicy.redirectAllowed(https,http))
    }
    @Test fun onlyTmdbAddressesAreResizedAndNeverEnlarged(){
        fun sized(address:String,width:Int)=ArtworkPolicy.sized(address.toHttpUrl(),width)?.toString()
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg",sized("https://image.tmdb.org/t/p/original/abc.jpg",300))
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg",sized("https://image.tmdb.org/t/p/w600_and_h900_bestv2/abc.jpg",208))
        assertEquals("https://image.tmdb.org/t/p/w185/abc.jpg?x=1",sized("https://image.tmdb.org/t/p/w500/abc.jpg?x=1",160))
        assertNull("Already small enough",sized("https://image.tmdb.org/t/p/w185/abc.jpg",208))
        assertNull("Wider than every fixed size",sized("https://image.tmdb.org/t/p/original/abc.jpg",900))
        assertNull(sized("https://images.example/t/p/original/abc.jpg",208))
        assertNull(sized("https://image.tmdb.org/other/original/abc.jpg",208))
        assertNull(sized("https://image.tmdb.org/t/p/unknown/abc.jpg",208))
    }
    @Test fun decodeGeometryKeepsAspectAndNeverEnlarges(){
        assertEquals(202 to 304,ArtworkPolicy.fitted(2000,3000,208,304))
        assertEquals(12 to 18,ArtworkPolicy.fitted(12,18,208,304))
        assertEquals(480 to 120,ArtworkPolicy.fitted(4096,1024,480,720))
        assertEquals(8,ArtworkPolicy.sampleSize(2000,3000,202,304))
        assertEquals(1,ArtworkPolicy.sampleSize(12,18,12,18))
        assertEquals(8,ArtworkPolicy.sampleSize(4096,1024,480,120))
        // Subsampling never goes below the target, so the exact scale only ever shrinks.
        for((w,h) in listOf(600 to 900,1000 to 1500,333 to 500,4096 to 1024)){
            val (tw,th)=ArtworkPolicy.fitted(w,h,208,304);val s=ArtworkPolicy.sampleSize(w,h,tw,th)
            assertTrue(w/s>=tw&&h/s>=th)
        }
        assertEquals(208,ArtworkPolicy.bucket(196));assertEquals(208,ArtworkPolicy.bucket(208));assertEquals(16,ArtworkPolicy.bucket(1))
    }
    @Test fun onlyLargeOriginalsAreReencoded(){
        assertTrue(ArtworkPolicy.keepOriginal(40_000,300,450,208,312))
        assertFalse("Large dimensions",ArtworkPolicy.keepOriginal(40_000,2000,3000,202,304))
        assertFalse("Large file",ArtworkPolicy.keepOriginal(400_000,300,450,208,312))
    }
    @Test fun prefetchWindowFollowsTheScrollDirection(){
        val down=ArtworkPolicy.prefetchWindow(6,17,6,100,true,2,1)
        assertEquals((18..29).toList()+(5 downTo 0).toList(),down)
        val up=ArtworkPolicy.prefetchWindow(30,47,6,100,false,2,1)
        assertEquals((29 downTo 18).toList()+(48..53).toList(),up)
        assertEquals("Clamped to the loaded rows",(95..99).toList(),ArtworkPolicy.prefetchWindow(84,94,6,100,true,3,0))
        assertEquals((18..23).toList(),ArtworkPolicy.prefetchWindow(6,17,6,100,true,1,0))
        assertTrue(ArtworkPolicy.prefetchWindow(-1,-1,6,100,true,2,1).isEmpty())
        assertTrue(ArtworkPolicy.prefetchWindow(0,5,6,0,true,2,1).isEmpty())
        assertEquals("Visible positions are never included",emptyList<Int>(),ArtworkPolicy.prefetchWindow(0,5,6,100,true,2,1).filter{it in 0..5})
    }
    @Test fun diskBudgetFollowsVolumeAndFreeSpace(){
        val mib=1024L*1024;val gib=1024*mib
        assertEquals(160*mib,ArtworkPolicy.diskBudget(16*gib,8*gib))
        assertEquals(32*mib,ArtworkPolicy.diskBudget(1*gib,1*gib))
        assertEquals(25*mib,ArtworkPolicy.diskBudget(16*gib,100*mib))
        assertEquals("Floor while the volume is almost full",8*mib,ArtworkPolicy.diskBudget(16*gib,4*mib))
    }
}
