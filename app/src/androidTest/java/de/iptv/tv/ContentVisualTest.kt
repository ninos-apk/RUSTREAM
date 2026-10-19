@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Reproducible screenshots use generated posters and synthetic video, never provider content. */
@RunWith(AndroidJUnit4::class)
class ContentVisualTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun setFontScale(scale:Float){
        device.executeShellCommand("settings put system font_scale $scale")
        val deadline=SystemClock.elapsedRealtime()+5_000
        while(kotlin.math.abs(context.resources.configuration.fontScale-scale)>0.05f&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20)
        assertEquals("Font configuration applied before launching an Activity",scale,context.resources.configuration.fontScale,0.05f)
    }
    @Test fun normalAndDoubleFontLayoutsKeepRemoteActionsVisible(){
        val store=SourceStore(context);val old=store.load()
        val oldScale=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
        val video=instrumentation.context.assets.open("fixture.mp4").use{it.readBytes()}
        FixtureServer(video).use{streams->PosterServer().use{posters->
            val file=File(context.filesDir,"visual-content-${System.nanoTime()}.m3u").apply{writeText(buildString{
                append("#EXTM3U\n#EXTINF:-1 group-title=Demo tvg-logo='${posters.base}/live',Demo Live\n${streams.base}/live.ts\n")
                for((index,name) in listOf("Northern Lights [2025-4K]","The Last Voyage [2024-HD]","أبواب السينما [2025-4K]","A Longer Movie Title With Several Words [2023-HD]").withIndex())
                    append("#EXTINF:-1 group-title=Filme tvg-logo='${posters.base}/movie-$index',$name\n${streams.base}/movie/u/p/$index.mp4\n")
                for(show in 0..9){val title=if(show==0)"أبواب القدر [SUB]" else "The Story $show"
                    for((season,episode) in listOf(1 to 2,1 to 10,2 to 1))append("#EXTINF:-1 group-title='Serien · مسلسلات' tvg-logo='${posters.base}/series-$show',$title S$season E$episode\n${streams.base}/series/u/p/$show-$season-$episode.mp4\n")}
            })}
            try{
                store.save(listOf(JSONObject().put("kind","file").put("name","Demo Library").put("uri",android.net.Uri.fromFile(file).toString())))
                for(scale in listOf(1f,2f)){
                    setFontScale(scale)
                    val prefix=if(scale==1f)"normal" else "large-text"
                    ActivityScenario.launch(MainActivity::class.java).use{scenario->
                        lateinit var currentActivity:MainActivity
                        scenario.onActivity{currentActivity=it}
                        try{
                        fun onMain(block:(MainActivity)->Unit)=instrumentation.runOnMainSync{block(currentActivity)}
                        fun activate(description:String){
                            assertTrue(description,device.wait(Until.hasObject(By.desc(description)),15_000))
                            onMain{activity->assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.first{it.contentDescription==description}.requestFocus())}
                            device.pressDPadCenter()
                        }
                        fun tab(title:String){
                            assertTrue(device.wait(Until.hasObject(By.text(title)),15_000))
                            onMain{activity->assertTrue(activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<Button>().first{it.text.toString()==title}.requestFocus())}
                            device.pressDPadCenter()
                        }
                        fun capture(name:String){
                            if(name.endsWith("grid")){
                                val deadline=SystemClock.elapsedRealtime()+10_000;var ready=false
                                while(!ready&&SystemClock.elapsedRealtime()<deadline){
                                    onMain{activity->
                                        val grid=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first{it.layoutManager is androidx.recyclerview.widget.GridLayoutManager}
                                        val first=grid.getChildAt(0) as? ViewGroup
                                        val image=first?.getChildAt(0) as? android.widget.ImageView
                                        ready=image!=null&&grid.descendants.filterIsInstance<android.widget.ImageView>().all{it.drawable is android.graphics.drawable.BitmapDrawable}&&image.height>0&&image.height<=image.width*1.5f+2
                                        if(ready){
                                            val drawable=image!!.drawable
                                            val rendered=android.graphics.RectF(0f,0f,drawable.intrinsicWidth.toFloat(),drawable.intrinsicHeight.toFloat())
                                            image.imageMatrix.mapRect(rendered)
                                            assertEquals("Poster keeps its displayed 2:3 ratio",1.5f,rendered.height()/rendered.width(),0.01f)
                                            val title=first!!.getChildAt(2);val bounds=Rect()
                                            assertTrue("Card title visible: $name",title.getGlobalVisibleRect(bounds))
                                            assertEquals("Complete first card title: $name (poster=${image.height}, card=${first.height}, grid=${grid.height})",title.height,bounds.height())
                                        }
                                    }
                                    if(!ready)SystemClock.sleep(40)
                                }
                                assertTrue("Poster loaded with 2:3 ratio: $name",ready)
                            }
                            if(name=="fullscreen"){
                                val deadline=SystemClock.elapsedRealtime()+5_000;var frame=false
                                while(!frame&&SystemClock.elapsedRealtime()<deadline){
                                    instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->
                                        val color=bitmap.getPixel(bitmap.width/2,bitmap.height/3)
                                        val channels=listOf(Color.red(color),Color.green(color),Color.blue(color))
                                        frame=channels.max()-channels.min()>150
                                        bitmap.recycle()
                                    }
                                    if(!frame)SystemClock.sleep(40)
                                }
                                assertTrue("Synthetic video frame after fullscreen reparent",frame)
                            }
                            onMain{activity->
                                val focus=activity.currentFocus;assertNotNull("Remote focus: $name",focus)
                                assertTrue("Focused action remains visible: $name",focus!!.getGlobalVisibleRect(Rect()))
                                assertEquals(scale,activity.resources.configuration.fontScale,0.05f)
                                if(name!="fullscreen") {
                                    val footer=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<ConnectionDetails>().first()
                                    val refresh=Rect();val statistic=Rect();val credit=Rect()
                                    assertTrue(footer.refreshButton.getGlobalVisibleRect(refresh))
                                    assertTrue(footer.statistics.getGlobalVisibleRect(statistic))
                                    assertEquals("All statistic lines are visible",footer.statistics.height,statistic.height())
                                    assertFalse("Statistics stay out of remote navigation",footer.statistics.isFocusable)
                                    assertTrue("Refresh shares the statistics line",kotlin.math.abs(statistic.centerY()-refresh.centerY())<=1)
                                    assertEquals("Refresh is completely visible",footer.refreshButton.width,refresh.width())
                                    assertEquals("Refresh height is completely visible",footer.refreshButton.height,refresh.height())
                                    val developer=footer.descendants.filterIsInstance<android.widget.TextView>().first { it.text==activity.getString(R.string.app_developer) }
                                    assertTrue(developer.getGlobalVisibleRect(credit))
                                    assertTrue("Developer credit sits below Refresh",credit.top>=refresh.bottom)
                                    assertEquals("Refresh aligns with the trailing credit edge",credit.right,refresh.right)
                                }
                            }
                            val directory=File(context.getExternalFilesDir(null),"content-screenshots").apply{mkdirs()}
                            assertTrue(device.takeScreenshot(File(directory,"$prefix-$name.png")))
                            // UTP uninstalls the app after the suite; retain visual artifacts outside its data.
                            device.executeShellCommand("mkdir -p /sdcard/Download/iptv-content-screenshots")
                            device.executeShellCommand("cp ${directory.absolutePath}/$prefix-$name.png /sdcard/Download/iptv-content-screenshots/$prefix-$name.png")
                        }
                        assertTrue(device.wait(Until.hasObject(By.desc("Demo Live")),20_000))
                        activate("Demo Live")
                        assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Demo Live")),15_000))
                        capture("live")
                        tab("Movies (4)")
                        assertTrue(device.wait(Until.hasObject(By.desc("Northern Lights [2025-4K]")),10_000))
                        capture("movies-grid")
                        activate("Northern Lights [2025-4K]")
                        assertTrue(device.wait(Until.hasObject(By.text("Abspielen")),10_000))
                        device.pressDPadCenter()
                        assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · Northern Lights [2025-4K]")),15_000))
                        capture("movie-details")
                        tab("Series (10)")
                        assertTrue(device.wait(Until.hasObject(By.desc("أبواب القدر [SUB]")),10_000))
                        capture("series-grid")
                        activate("أبواب القدر [SUB]")
                        assertTrue(device.wait(Until.hasObject(By.text("Staffel 1 ▾")),10_000))
                        activate("أبواب القدر [SUB] S1 E2")
                        assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · أبواب القدر [SUB] S1 E2")),15_000))
                        capture("series-details")
                        onMain{activity->
                            val pane=activity.findViewById<ViewGroup>(android.R.id.content).descendants.filterIsInstance<PlayerPane>().first()
                            pane.showControls(true);assertTrue(pane.buttons.values.first{it.contentDescription=="Vollbild"}.requestFocus())
                        }
                        device.pressDPadCenter();capture("fullscreen")
                        device.pressBack()
                        assertTrue(device.wait(Until.hasObject(By.text("Staffel 1 ▾")),5_000))
                        }finally{finishTvActivity(currentActivity)}
                    }
                }
            }finally{setFontScale(oldScale);store.save(old);file.delete()}
        }}
    }
}

private class PosterServer:AutoCloseable{
    private val socket=ServerSocket(0)
    private val workers=Executors.newFixedThreadPool(3)
    val base="http://127.0.0.1:${socket.localPort}"
    init{workers.execute{while(!socket.isClosed){try{val client=socket.accept();workers.execute{client.use{
        client.soTimeout=5000
        val reader=client.getInputStream().bufferedReader();val request=reader.readLine().orEmpty()
        while(reader.readLine()?.isNotEmpty()==true){}
        val label=if(request.contains("/movie"))"CINEMA" else if(request.contains("/live"))"LIVE" else "SERIES"
        val image=Bitmap.createBitmap(240,360,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image);canvas.drawColor(Color.rgb(29,65,79))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(103,222,211)}
        canvas.drawCircle(170f,115f,100f,paint);paint.color=Color.rgb(25,44,60);canvas.drawCircle(70f,210f,95f,paint)
        paint.color=Color.WHITE;paint.textSize=28f;paint.typeface=android.graphics.Typeface.DEFAULT_BOLD;canvas.drawText(label,22f,315f,paint)
        val bytes=ByteArrayOutputStream().also{image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
        client.getOutputStream().use{out->out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());out.write(bytes)}
    }}}catch(_:Exception){break}}}}
    override fun close(){socket.close();workers.shutdownNow()}
}
