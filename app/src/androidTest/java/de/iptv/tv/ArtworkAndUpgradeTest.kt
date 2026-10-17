package de.iptv.tv

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class ArtworkAndUpgradeTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun artworkDecodesByContentBoundsMemoryAndRejectsBadImages(){
        for(format in listOf(Bitmap.CompressFormat.PNG,Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.WEBP)){
            val original=Bitmap.createBitmap(12,18,Bitmap.Config.ARGB_8888)
            val bytes=ByteArrayOutputStream().also{original.compress(format,90,it)}.toByteArray();original.recycle()
            val bitmap=ArtworkLoader.decode(bytes)
            assertNotNull(bitmap);assertEquals(12,bitmap!!.width);assertEquals(18,bitmap.height);bitmap.recycle()
        }
        val large=Bitmap.createBitmap(4096,1024,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().also{large.compress(Bitmap.CompressFormat.PNG,90,it)}.toByteArray();large.recycle()
        val bounded=ArtworkLoader.decode(bytes)!!
        assertTrue(bounded.width<=480);assertTrue(bounded.height<=720);bounded.recycle()
        assertNull(ArtworkLoader.decode("This is not an image".toByteArray()))
    }
    @Test fun recycledViewsRejectOldDownloadsAndFailuresKeepAPlaceholder(){
        val original=Bitmap.createBitmap(12,18,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().also{original.compress(Bitmap.CompressFormat.PNG,90,it)}.toByteArray();original.recycle()
        val socket=ServerSocket(0);val server=Executors.newSingleThreadExecutor()
        server.execute{try{socket.accept().use{client->
            client.soTimeout=5000
            val input=client.getInputStream().bufferedReader();while(input.readLine()?.isNotEmpty()==true){}
            SystemClock.sleep(250)
            client.getOutputStream().use{out->out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes)}
        }}catch(_:Exception){}}
        val loader=ArtworkLoader();lateinit var view:ImageView
        try{
            instrumentation.runOnMainSync{
                view=ImageView(context)
                loader.bind(view,"old"){"http://127.0.0.1:${socket.localPort}/no-extension"}
            }
            SystemClock.sleep(100)
            instrumentation.runOnMainSync{loader.bind(view,"new"){null}}
            SystemClock.sleep(800)
            instrumentation.runOnMainSync{
                assertEquals("new",view.tag)
                assertFalse("An old image must not replace the new placeholder",(view.drawable as? BitmapDrawable)?.bitmap?.let{it.width==12&&it.height==18}==true)
            }
        }finally{loader.close();socket.close();server.shutdownNow()}
    }
    @Test fun schemaOneUpgradeForcesFullDownloadAndOfflineFailureRetainsCatalog():Unit=runBlocking{
        val core=Core();val loader=SourceLoader(context)
        val server=FixtureServer(byteArrayOf())
        val source=JSONObject().put("kind","url").put("url","${server.base}/cached.m3u")
        try{
            loader.load(source,core);core.call("clear")
            val catalogs=CatalogStore(context)
            val old=catalogs.entry(source)!!
            val oldFile=catalogs.safeFile(old.getString("file"))!!
            SQLiteDatabase.openDatabase(oldFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->
                db.execSQL("UPDATE meta SET value='1' WHERE key='schema'")
                db.execSQL("DROP TABLE shows");db.execSQL("DROP TABLE members")
                db.execSQL("ALTER TABLE channels DROP COLUMN year");db.execSQL("ALTER TABLE channels DROP COLUMN quality")
            }
            catalogs.commit(source,oldFile,old.getString("key"),old.getJSONObject("validators"),false,schema=1)
            // A new loader represents a fresh process view of the persisted manifest.
            SourceLoader(context).use{upgrader->
                val legacy=upgrader.restore(source,core)!!
                assertEquals(1,legacy.getInt("schema"));assertTrue(upgrader.shouldRefresh(source))
                val upgraded=upgrader.load(source,core)
                assertEquals(CatalogStore.SCHEMA,upgraded.getInt("schema"))
                assertEquals("An old-schema ETag must not produce 304",0,server.conditionalRequests.get())
                assertTrue("The previous offline generation is retained",oldFile.isFile)
                val revision=upgraded.getLong("revision");server.close()
                try{upgrader.load(source,core);fail("Offline download must fail")}catch(_:java.io.IOException){}
                catch(error:ImportFailure){assertNotNull(error)}
                assertEquals(revision,core.call("categories").getLong("revision"))
                assertEquals(CatalogStore.SCHEMA,upgrader.restore(source,core)!!.getInt("schema"))
                upgrader.remove(source)
            }
        }finally{server.close();loader.close();core.close()}
    }
}
