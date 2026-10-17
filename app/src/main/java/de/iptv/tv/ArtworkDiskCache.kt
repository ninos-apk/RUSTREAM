package de.iptv.tv

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Bounded least-recently-used image files in the app's private cache directory.
 * Names are keyed HMACs of the address, so no image address reaches the disk. Writes go through a
 * temporary file and a rename; a length and CRC check turns torn or foreign files into misses.
 * Thread-safe; every method does blocking file I/O and belongs on an I/O thread. */
internal class ArtworkDiskCache(private val directory:File,salt:ByteArray,val maxBytes:Long,private val clock:()->Long=System::currentTimeMillis) {
    class Entry(val payload:ByteArray,val expires:Long,val etag:String?=null,val lastModified:String?=null)
    private val key=SecretKeySpec(salt,"HmacSHA256")
    private val lock=Any()
    // Access order: the first entries are the least recently used.
    private val files=LinkedHashMap<String,Long>(256,0.75f,true)
    private var bytes=0L
    val size get()=synchronized(lock){bytes}
    val count get()=synchronized(lock){files.size}

    /** Rebuilds the index from the directory once; leftovers of interrupted writes are deleted. */
    fun open(){
        directory.mkdirs()
        val found=ArrayList<Triple<String,Long,Long>>()
        for(file in directory.listFiles().orEmpty()){
            if(file.isFile&&file.name.endsWith(SUFFIX)&&file.name.length==NAME_LENGTH+SUFFIX.length)found+=Triple(file.name.dropLast(SUFFIX.length),file.length(),file.lastModified())
            else file.deleteRecursively()
        }
        found.sortBy{it.third}
        synchronized(lock){files.clear();bytes=0;for((name,length) in found){files[name]=length;bytes+=length}}
        trim()
    }
    // Mac instances are not thread-safe; one per thread avoids a provider lookup per name.
    private val macs=object:ThreadLocal<Mac>(){override fun initialValue():Mac=Mac.getInstance("HmacSHA256").apply{init(key)}}
    fun name(address:String,variant:String):String{
        val digest=macs.get()!!.doFinal("$variant\n$address".toByteArray())
        val name=CharArray(NAME_LENGTH)
        for(i in 0 until NAME_LENGTH/2){val b=digest[i].toInt() and 0xff;name[2*i]=HEX[b ushr 4];name[2*i+1]=HEX[b and 15]}
        return String(name)
    }
    /** Index lookup only, no file access: lets callers skip the disk for images it does not hold. */
    fun contains(name:String)=synchronized(lock){files.containsKey(name)}
    fun get(name:String):Entry?{
        if(synchronized(lock){files[name]}==null)return null
        val file=File(directory,name+SUFFIX)
        val data=try{file.readBytes()}catch(_:IOException){remove(name);return null}
        val entry=parse(data) ?: run{remove(name);return null}
        // The access order survives restarts through the file time, written at most daily per file.
        val now=clock();if(now-file.lastModified()>DAY)file.setLastModified(now)
        return entry
    }
    fun put(name:String,entry:Entry):Boolean{
        val data=serialize(entry)
        if(data.size>maxBytes/8)return false
        val target=File(directory,name+SUFFIX);val temporary=File(directory,"$name.${System.nanoTime()}.tmp")
        try{FileOutputStream(temporary).use{it.write(data)}}catch(_:IOException){temporary.delete();return false}
        // The rename and the index change together, so concurrent writers of one name agree on its size.
        synchronized(lock){
            if(!temporary.renameTo(target)){temporary.delete();return false}
            files.put(name,data.size.toLong())?.let{bytes-=it};bytes+=data.size
        }
        trim()
        return true
    }
    fun remove(name:String){
        synchronized(lock){files.remove(name)?.let{bytes-=it};File(directory,name+SUFFIX).delete()}
    }
    fun clear(){
        synchronized(lock){files.clear();bytes=0}
        directory.listFiles()?.forEach{it.deleteRecursively()}
    }
    private fun trim(){
        val victims=ArrayList<String>()
        synchronized(lock){
            if(bytes<=maxBytes)return
            // Evict to 90%: one large burst of new posters does not delete a file per write.
            val iterator=files.entries.iterator()
            while(bytes>maxBytes*9/10&&iterator.hasNext()){val (name,length)=iterator.next();iterator.remove();bytes-=length;victims+=name}
        }
        for(name in victims)File(directory,name+SUFFIX).delete()
    }
    companion object {
        private val HEX="0123456789abcdef".toCharArray()
        private const val SUFFIX=".img"
        private const val NAME_LENGTH=32
        private const val DAY=86_400_000L
        private const val MAGIC=0x52534131 // "RSA1"
        private const val HEADER=4+8+4+8+2+2
        internal fun serialize(entry:Entry):ByteArray{
            val etag=(entry.etag ?: "").toByteArray().take(512).toByteArray()
            val modified=(entry.lastModified ?: "").toByteArray().take(128).toByteArray()
            val crc=CRC32().apply{update(entry.payload)}.value
            return ByteBuffer.allocate(HEADER+etag.size+modified.size+entry.payload.size).putInt(MAGIC).putLong(entry.expires)
                .putInt(entry.payload.size).putLong(crc).putShort(etag.size.toShort()).putShort(modified.size.toShort())
                .put(etag).put(modified).put(entry.payload).array()
        }
        internal fun parse(data:ByteArray):Entry?{
            if(data.size<HEADER)return null
            val buffer=ByteBuffer.wrap(data)
            if(buffer.int!=MAGIC)return null
            val expires=buffer.long;val length=buffer.int;val crc=buffer.long
            val etagLength=buffer.short.toInt();val modifiedLength=buffer.short.toInt()
            if(length<1||etagLength<0||modifiedLength<0||HEADER+etagLength+modifiedLength+length!=data.size)return null
            val etag=String(data,HEADER,etagLength);val modified=String(data,HEADER+etagLength,modifiedLength)
            val payload=data.copyOfRange(HEADER+etagLength+modifiedLength,data.size)
            if(CRC32().apply{update(payload)}.value!=crc)return null
            return Entry(payload,expires,etag.ifEmpty{null},modified.ifEmpty{null})
        }
    }
}
