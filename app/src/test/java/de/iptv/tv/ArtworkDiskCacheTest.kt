package de.iptv.tv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArtworkDiskCacheTest {
    @get:Rule val folder=TemporaryFolder()
    private val salt=ByteArray(32){it.toByte()}
    private fun cache(max:Long=1024*1024,directory:File=File(folder.root,"artwork"))=ArtworkDiskCache(directory,salt,max).also{it.open()}
    private fun entry(size:Int,fill:Int=1)=ArtworkDiskCache.Entry(ByteArray(size){fill.toByte()},123_456L,"\"v1\"","Tue, 01 Sep 2026 10:00:00 GMT")

    @Test fun entriesRoundTripWithValidators(){
        val cache=cache();val name=cache.name("https://img.test/a.jpg","208x304")
        assertTrue(cache.put(name,entry(500,7)))
        val read=cache.get(name)!!
        assertArrayEquals(ByteArray(500){7},read.payload);assertEquals(123_456L,read.expires)
        assertEquals("\"v1\"",read.etag);assertEquals("Tue, 01 Sep 2026 10:00:00 GMT",read.lastModified)
        assertNull(cache.get(cache.name("https://img.test/other.jpg","208x304")))
        assertTrue(cache.contains(name));assertFalse(cache.contains(cache.name("https://img.test/other.jpg","208x304")))
        // A new instance on the same directory finds the entry again.
        assertArrayEquals(ByteArray(500){7},cache().get(name)!!.payload)
    }
    @Test fun namesRevealNoAddressAndDependOnKeyAndSize(){
        val cache=cache();val address="http://provider.test:8080/images/user/secret/123.jpg"
        val name=cache.name(address,"208x304")
        assertTrue(name.matches(Regex("[0-9a-f]{32}")))
        assertEquals(name,cache.name(address,"208x304"))
        assertNotEquals(name,cache.name(address,"240x368"))
        assertNotEquals(name,ArtworkDiskCache(File(folder.root,"other"),ByteArray(32){9},1024).name(address,"208x304"))
        cache.put(name,entry(100))
        val stored=File(folder.root,"artwork").listFiles()!!.single()
        for(part in listOf("provider","secret","123"))assertFalse(stored.name.contains(part))
        assertFalse(String(stored.readBytes(),Charsets.ISO_8859_1).contains("provider.test"))
    }
    @Test fun leastRecentlyUsedEntriesAreEvictedToNinetyPercent(){
        val cache=cache(max=20_000)
        val names=(0 until 19).map{cache.name("https://img.test/$it.jpg","v")}
        names.take(18).forEach{assertTrue(cache.put(it,entry(1_000)))}
        assertEquals("Below the budget nothing is evicted",18,cache.count)
        assertNotNull("Reading refreshes the order",cache.get(names[0]))
        cache.put(names[18],entry(1_000))
        assertTrue(cache.size<=18_000)
        assertNotNull(cache.get(names[0]));assertNull("Oldest unused entry evicted",cache.get(names[1]))
        assertNotNull(cache.get(names[18]))
        assertEquals(cache.count,File(folder.root,"artwork").listFiles()!!.size)
        assertFalse("Single entries above an eighth of the budget are not stored",cache.put(names[1],entry(3_000)))
    }
    @Test fun tornForeignAndTamperedFilesAreMissesAndRemoved(){
        val directory=File(folder.root,"artwork");val cache=cache(directory=directory)
        val good=cache.name("a","v");val torn=cache.name("b","v");val flipped=cache.name("c","v")
        listOf(good,torn,flipped).forEach{cache.put(it,entry(1_000))}
        File(directory,"$torn.img").apply{writeBytes(readBytes().copyOf(600))}
        File(directory,"$flipped.img").apply{val data=readBytes();data[data.size-1]=9;writeBytes(data)}
        assertNull(cache.get(torn));assertNull(cache.get(flipped));assertNotNull(cache.get(good))
        assertFalse(File(directory,"$torn.img").exists());assertFalse(File(directory,"$flipped.img").exists())
        assertNull(ArtworkDiskCache.parse(ByteArray(10)));assertNull(ArtworkDiskCache.parse("not an entry at all, just text".toByteArray()))
    }
    @Test fun openRemovesInterruptedWritesAndRebuildsTheIndex(){
        val directory=File(folder.root,"artwork");val first=cache(directory=directory)
        val old=first.name("old","v");val recent=first.name("recent","v")
        first.put(old,entry(4_000));first.put(recent,entry(4_000))
        File(directory,"$old.img").setLastModified(1_000_000_000_000L);File(directory,"$recent.img").setLastModified(1_900_000_000_000L)
        File(directory,"$old.123.tmp").writeBytes(ByteArray(50));File(directory,"unknown.bin").writeBytes(ByteArray(50))
        val reopened=cache(max=6_000,directory=directory)
        assertEquals(listOf("$recent.img"),directory.listFiles()!!.map{it.name})
        assertEquals(1,reopened.count);assertNotNull(reopened.get(recent))
    }
    @Test fun concurrentReadersAndWritersKeepTheIndexConsistent(){
        val cache=cache(max=200_000);val pool=Executors.newFixedThreadPool(4)
        val names=(0 until 64).map{cache.name("https://img.test/$it.jpg","v")}
        repeat(400){i->pool.execute{val name=names[i%names.size];if(i%3==0)cache.get(name) else cache.put(name,entry(1_000+i))}}
        pool.shutdown();assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS))
        val files=File(folder.root,"artwork").listFiles()!!.filter{it.name.endsWith(".img")}
        assertEquals(cache.count,files.size);assertEquals(cache.size,files.sumOf{it.length()})
        cache.clear();assertEquals(0,cache.count);assertEquals(0L,cache.size)
    }
}
