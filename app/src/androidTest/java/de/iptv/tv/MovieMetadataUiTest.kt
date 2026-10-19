package de.iptv.tv

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** All network responses are synthetic and local. User sources/library are restored in finally. */
@RunWith(AndroidJUnit4::class)
class MovieMetadataUiTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    @Test fun narrowRtlDetailsKeepLongTextAndControlsReachable(){
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var activity:MainActivity
            lateinit var dialog:android.app.Dialog
            lateinit var page:MovieDetailsView
            val artwork=ArtworkLoader()
            scenario.onActivity{current->
                activity=current
                val config=android.content.res.Configuration(current.resources.configuration).apply{screenWidthDp=320;fontScale=2f;setLayoutDirection(java.util.Locale("ar"))}
                val narrow=current.createConfigurationContext(config)
                page=MovieDetailsView(narrow,MetadataItem("source","local",MediaType.MOVIE,"Ein sehr langer deutscher Filmtitel mit zusätzlichen Angaben und einem zweiten Teil",2025),artwork,{},{},{},{},{})
                page.favorite(true)
                page.render(MetadataResult(tmdb=JSONObject().put("overview","Eine vollständige Beschreibung. ".repeat(80)).put("runtime",120)
                    .put("credits",JSONObject().put("cast",JSONArray().put(JSONObject().put("id",1).put("name","Ein langer vollständiger Name einer Darstellerin").put("character","Eine ausführliche Rollenbeschreibung")))),state="ready"),null)
                dialog=android.app.Dialog(current).apply{setContentView(page);show();window!!.setLayout(TvUi.dp(narrow,320),ViewGroup.LayoutParams.MATCH_PARENT)}
                page.backButton.requestFocus()
            }
            try{
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync{
                    assertEquals(View.LAYOUT_DIRECTION_RTL,page.layoutDirection)
                    assertTrue(page.width<=TvUi.dp(activity,320))
                    for(text in page.descendants.filterIsInstance<TextView>()){
                        assertTrue("Text stays within the 320dp column",text.width<=page.width)
                        val layout=text.layout
                        if(layout!=null)for(line in 0 until layout.lineCount)assertEquals("All long text remains available",0,layout.getEllipsisCount(line))
                    }
                    page.favoriteButton.requestFocus();assertTrue(page.favoriteButton.getGlobalVisibleRect(Rect()))
                    val node=page.favoriteButton.createAccessibilityNodeInfo()
                    assertEquals("android.widget.Button",node.className.toString())
                    assertFalse(node.contentDescription.isNullOrBlank());assertTrue(node.isSelected)
                    for(button in page.descendants.filterIsInstance<Button>())assertTrue("Every native button has a name",button.text.isNotBlank()||!button.contentDescription.isNullOrBlank())
                    val star=page.favoriteButton.compoundDrawablesRelative[0]
                    val bitmap=android.graphics.Bitmap.createBitmap(star.bounds.width(),star.bounds.height(),android.graphics.Bitmap.Config.ARGB_8888)
                    star.draw(android.graphics.Canvas(bitmap))
                    assertEquals("Selected favorite paints a yellow star",0xFFFFD54F.toInt(),bitmap.getPixel(bitmap.width/2,bitmap.height/2));bitmap.recycle()
                    page.playButton.requestFocus();assertTrue(page.playButton.getGlobalVisibleRect(Rect()))
                    for(button in listOf(page.playButton,page.favoriteButton)){
                        val visible=Rect();assertTrue(button.getGlobalVisibleRect(visible))
                        assertEquals("Action remains fully visible at 320dp and 200% text",button.width,visible.width())
                    }
                    assertEquals(1,(page.descendants.filterIsInstance<RecyclerView>().first().layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)
                    page.backButton.requestFocus();page.scrollTo(0,0)
                }
                val directory=File("/sdcard/Download/iptv-metadata").apply{mkdirs()}
                assertTrue(device.wait(Until.hasObject(By.textContains("Ein sehr langer deutscher Filmtitel")),5000))
                SystemClock.sleep(500) // Allow the dialog's native entrance to finish before capture.
                assertTrue(device.takeScreenshot(File(directory,"narrow-rtl-large.png")))
            }finally{instrumentation.runOnMainSync{dialog.dismiss();page.dispose()};artwork.close();finishTvActivity(activity)}
        }
    }
    @Test fun gridFetchDetailsFavoritesBackAndLargeText() {
        val sources=SourceStore(context)
        val sourceBackupFile=File(context.noBackupFilesDir,"metadata-ui-sources-backup.enc")
        val sourceBackup=SourceStore(context,sourceBackupFile.name)
        val oldSources=(if(sourceBackupFile.exists())sourceBackup.load() else sources.load()).filter{it.optString("name")!="Metadata fixture"}
        val library=SourceStore(context,UserLibraryStore.FILE)
        val libraryBackupFile=File(context.noBackupFilesDir,"metadata-ui-library-backup.enc")
        val libraryBackup=SourceStore(context,libraryBackupFile.name)
        val oldLibrary=if(libraryBackupFile.exists())libraryBackup.load() else library.load()
        sourceBackup.save(oldSources);libraryBackup.save(oldLibrary)
        val previousLanguage=AppLanguage.current(context)
        val previousFont=device.executeShellCommand("settings get system font_scale").trim()
        val file=File(context.filesDir,"metadata-ui-${System.nanoTime()}.m3u")
        ApiFixture(instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}).use{server->
            file.writeText(buildString{
                append("#EXTM3U\n#EXTINF:-1 tvg-logo=\"${server.base}/portrait.png\" group-title=Live,Demo Live\n${server.base}/live.ts\n")
                repeat(80){i->append("#EXTINF:-1 group-title=Filme,Film $i [2025-HD]\n${server.base}/movie/u/p/${100+i}.mp4\n")}
                repeat(3){i->append("#EXTINF:-1 group-title=Serien,Story S1 E${i+1}\n${server.base}/series/u/p/${900+i}.mp4\n")}
            })
            try {
                AppLanguage.save(context,"de")
                sources.save(listOf(JSONObject().put("kind","file").put("name","Metadata fixture").put("uri",android.net.Uri.fromFile(file).toString())))
                library.save(emptyList())
                for(scale in listOf(1f,2f)){
                    library.save(emptyList())
                    device.executeShellCommand("settings put system font_scale $scale")
                    SystemClock.sleep(300)
                    ActivityScenario.launch(MainActivity::class.java).use{scenario->
                        lateinit var activity:MainActivity
                        scenario.onActivity{activity=it}
                        fun onMain(action:()->Unit)=instrumentation.runOnMainSync(action)
                        fun views()=activity.findViewById<ViewGroup>(android.R.id.content).descendants
                        fun activateText(text:String){
                            assertTrue(text,device.wait(Until.hasObject(By.text(text)),20_000))
                            onMain{assertTrue(views().filterIsInstance<Button>().first{it.text.toString()==text&&it.isShown}.requestFocus())}
                            device.pressDPadCenter()
                        }
                        fun capture(name:String){
                            instrumentation.waitForIdleSync();SystemClock.sleep(100)
                            val directory=File("/sdcard/Download/iptv-metadata").apply{mkdirs()}
                            assertTrue(device.takeScreenshot(File(directory,"${if(scale==1f)"normal" else "large"}-$name.png")))
                        }
                        try {
                            assertTrue(device.wait(Until.hasObject(By.desc("Demo Live")),20_000))
                            val logoDeadline=SystemClock.elapsedRealtime()+10_000
                            var logoLoaded=false
                            while(!logoLoaded&&SystemClock.elapsedRealtime()<logoDeadline){
                                onMain{logoLoaded=views().filterIsInstance<android.widget.ImageView>().any{it.tag!=null&&it.drawable is android.graphics.drawable.BitmapDrawable}}
                                SystemClock.sleep(30)
                            }
                            assertTrue("Live TV logo is downloaded and decoded",logoLoaded)
                            val cache=MetadataCache()
                            val service=MetadataService(cache,{"synthetic-token"},base=(server.base+"/3/").toHttpUrl())
                            onMain{MainActivity::class.java.getDeclaredField("metadata").apply{isAccessible=true}.set(activity,service)}
                            activateText("Movies (80)")
                            assertTrue("Rating loads without opening a film",device.wait(Until.hasObject(By.text("TMDB 7,5/10")),15_000))
                            assertEquals("Grid never requests credits",0,server.credits.get())
                            assertTrue("Only a small visible window is fetched",server.summaries.get()<40)
                            capture("grid")
                            onMain{
                                val grid=views().filterIsInstance<RecyclerView>().first{it.layoutManager is androidx.recyclerview.widget.GridLayoutManager}
                                val card=grid.getChildAt(0)
                                assertEquals("Rating does not add a focus stop",0,(card as ViewGroup).descendants.count{it.isFocusable})
                                assertTrue(card.requestFocus())
                            }
                            device.pressDPadCenter()
                            assertTrue(device.wait(Until.hasObject(By.text("Filmdetails")),10_000))
                            onMain{
                                val play=views().filterIsInstance<Button>().first{it.text=="Abspielen"}
                                val parent=play.parent as ViewGroup;val index=parent.indexOfChild(play)
                                assertEquals("Detail button directly below Play","Filmdetails",(parent.getChildAt(index+1) as Button).text.toString())
                            }
                            activateText("Filmdetails")
                            assertTrue(device.wait(Until.hasObject(By.textContains("Vollständige Beschreibung")),15_000))
                            val creditsDeadline=SystemClock.elapsedRealtime()+10_000
                            while(server.credits.get()==0&&SystemClock.elapsedRealtime()<creditsDeadline)SystemClock.sleep(30)
                            assertTrue("Credits arrive after the cached summary",server.credits.get()>0)
                            onMain{
                                val page=views().filterIsInstance<MovieDetailsView>().first()
                                assertTrue(page.backButton.isFocused)
                                assertTrue(page.playButton.isEnabled);assertTrue(page.favoriteButton.isEnabled)
                                assertTrue(page.playButton.requestFocus())
                                assertTrue(page.playButton.getGlobalVisibleRect(Rect()))
                            }
                            onMain{
                                val page=views().filterIsInstance<MovieDetailsView>().first()
                                page.favoriteButton.performClick();page.favoriteButton.performClick()
                                assertEquals("Repeated activation during saving does not remove favorite","Aus Favoriten entfernen",page.favoriteButton.contentDescription.toString())
                                assertTrue(page.favoriteButton.isSelected)
                                assertEquals(TvUi.dp(activity,44),page.favoriteButton.width)
                                assertNotNull(page.playButton.compoundDrawablesRelative[0])
                            }
                            capture("details")
                            SystemClock.sleep(700)
                            var castMoreFocused=false
                            for(step in 0 until 40){
                                onMain{castMoreFocused=(activity.currentFocus as? Button)?.text.toString()=="Weitere Besetzung anzeigen"}
                                if(castMoreFocused)break
                                device.pressDPadDown()
                            }
                            assertTrue("Full cast can be reached with the D-pad",castMoreFocused)
                            device.pressDPadCenter()
                            onMain{assertTrue("More cast is appended",views().filterIsInstance<TextView>().any{it.text.toString().startsWith("Person 12")} )}
                            onMain{
                                val page=views().filterIsInstance<MovieDetailsView>().first()
                                val grid=page.descendants.filterIsInstance<RecyclerView>().first()
                                assertTrue("Cast uses multiple columns",(grid.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount>1)
                                for(i in 0 until grid.childCount){
                                    val card=grid.getChildAt(i) as ViewGroup
                                    val photo=card.getChildAt(0);val name=card.getChildAt(1)
                                    assertTrue("Cast label stays inside its own column",name.left>=0&&name.right<=card.width)
                                    assertTrue("Portrait and label share a center",kotlin.math.abs((photo.left+photo.right)-(name.left+name.right))<=2)
                                    val label=name as TextView
                                    val textLayout=label.layout
                                    for(line in 0 until textLayout.lineCount){
                                        val center=label.left+label.compoundPaddingLeft+(textLayout.getLineLeft(line)+textLayout.getLineRight(line))/2-label.scrollX
                                        assertEquals("Rendered cast text stays centered under its own portrait at scale $scale, card $i, line $line",(photo.left+photo.right)/2f,center,2f)
                                    }
                                }
                                grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                            }
                            val portraitDeadline=SystemClock.elapsedRealtime()+10_000
                            var portraitLoaded=false
                            while(!portraitLoaded&&SystemClock.elapsedRealtime()<portraitDeadline){
                                onMain{portraitLoaded=views().filterIsInstance<MovieDetailsView>().first().descendants.filterIsInstance<android.widget.ImageView>()
                                    .any{it.tag?.toString()?.startsWith("person:")==true&&it.drawable is android.graphics.drawable.BitmapDrawable}}
                                SystemClock.sleep(30)
                            }
                            assertTrue("Visible cast portraits load from a direct URL without image configuration",portraitLoaded)
                            capture("cast")
                            onMain{views().filterIsInstance<MovieDetailsView>().first().playButton.requestFocus()}
                            device.pressDPadCenter()
                            assertTrue("Details Play uses the real decoder",device.wait(Until.hasObject(By.text("Wiedergabe · Film 0 [2025-HD]")),15_000))
                            var returned=false
                            for(step in 0..2){
                                onMain{returned=views().filterIsInstance<MovieDetailsView>().any{it.isShown}}
                                if(returned)break
                                device.pressBack()
                            }
                            onMain{assertTrue("Player returns to the calling details route",views().filterIsInstance<MovieDetailsView>().first().isShown)}
                            device.pressBack()
                            assertTrue(device.wait(Until.hasObject(By.text("Filmdetails")),5000))
                            device.pressBack()
                            assertTrue(device.wait(Until.hasObject(By.desc("Film 0 [2025-HD]")),10000))
                            onMain{
                                val grid=views().filterIsInstance<RecyclerView>().first{it.layoutManager is androidx.recyclerview.widget.GridLayoutManager}
                                assertEquals("Original film retains focus","Film 0 [2025-HD]",grid.focusedChild!!.contentDescription.toString())
                            }
                            activateText("Series (1)")
                            assertTrue("Whole-series rating loads independently of episodes",device.wait(Until.hasObject(By.text("TMDB 8,2/10")),10000))
                            capture("series-grid")
                        } finally {finishTvActivity(activity)}
                    }
                    server.credits.set(0);server.summaries.set(0)
                }
                val restored=UserLibrary();kotlinx.coroutines.runBlocking{UserLibraryStore(context).load(restored)}
                assertTrue("Favorite was persisted",restored.snapshot().toJson().toString().contains("Film 0"))
            } finally {
                device.executeShellCommand("settings put system font_scale $previousFont")
                AppLanguage.save(context,previousLanguage);sources.save(oldSources);library.save(oldLibrary);file.delete()
                sourceBackupFile.delete();libraryBackupFile.delete()
            }
        }
    }
    private class ApiFixture(private val video:ByteArray):AutoCloseable {
        private val portrait=java.io.ByteArrayOutputStream().also{output->
            android.graphics.Bitmap.createBitmap(80,120,android.graphics.Bitmap.Config.ARGB_8888).apply{
                eraseColor(android.graphics.Color.rgb(210,160,100));compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);recycle()
            }
        }.toByteArray()
        private val socket=ServerSocket(0)
        private val workers=Executors.newCachedThreadPool()
        val base="http://127.0.0.1:${socket.localPort}"
        val summaries=AtomicInteger();val credits=AtomicInteger()
        init{workers.execute{while(!socket.isClosed)try{val client=socket.accept();workers.execute{try{client.use{connection->
            val reader=connection.getInputStream().bufferedReader();val request=reader.readLine().orEmpty()
            var range:Int?=null
            while(true){val line=reader.readLine();if(line.isNullOrEmpty())break;if(line.startsWith("Range:",true))range=line.substringAfter("bytes=").substringBefore('-').toIntOrNull()}
            val path=request.substringAfter(' ').substringBefore(' ')
            if(path=="/portrait.png"){
                connection.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${portrait.size}\r\nConnection: close\r\n\r\n".toByteArray());write(portrait);flush()}
                return@use
            }
            if(path.startsWith("/movie/")){
                val offset=range?.coerceIn(0,video.size-1)?:0
                val rangeHeader=if(range!=null)"Content-Range: bytes $offset-${video.size-1}/${video.size}\r\n" else ""
                connection.getOutputStream().apply{write("HTTP/1.1 ${if(range!=null)"206 Partial Content" else "200 OK"}\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${rangeHeader}Content-Length: ${video.size-offset}\r\nConnection: close\r\n\r\n".toByteArray());write(video,offset,video.size-offset);flush()}
                return@use
            }
            val json=when{
                path.startsWith("/3/search/")->{
                    val query=path.substringAfter("query=").substringBefore('&');val title=URLDecoder.decode(query,"UTF-8")
                    val id=if(path.contains("/tv"))900 else 100+(title.substringAfterLast(' ').toIntOrNull()?:0)
                    JSONObject().put("results",JSONArray().put(if(id==900)show() else movie(id)))
                }
                path.startsWith("/3/tv/")->{summaries.incrementAndGet();show()}
                path.startsWith("/3/movie/")->{summaries.incrementAndGet();val id=path.substringAfter("/3/movie/").substringBefore('?').toInt();movie(id).apply{
                    if(path.contains("append_to_response")){credits.incrementAndGet();put("credits",JSONObject().put("cast",JSONArray((0..15).map{i->JSONObject().put("id",i+1).put("name","Person $i").put("character","Rolle $i").put("order",i).put("profile_url","$base/portrait.png")})))}
                }}
                else->JSONObject().put("info",JSONObject().put("plot","Anbieterbeschreibung"))
            }
            val bytes=json.toString().toByteArray();connection.getOutputStream().apply{write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
        }}catch(_:Exception){/* A canceled grid request may close the socket before a reply. */}}}catch(_:Exception){}}}
        private fun movie(id:Int)=JSONObject().put("id",id).put("title","Film ${id-100}").put("original_title","Film ${id-100}").put("release_date","2025-01-01")
            .put("vote_average",7.456).put("vote_count",120).put("overview","Vollständige Beschreibung mit zusätzlichen Filmangaben.").put("runtime",100)
            .put("genres",JSONArray().put(JSONObject().put("name","Drama")))
        private fun show()=JSONObject().put("id",900).put("name","Story").put("original_name","Story").put("first_air_date","2025-01-01").put("vote_average",8.2).put("vote_count",80)
        override fun close(){socket.close();workers.shutdownNow()}
    }
}
