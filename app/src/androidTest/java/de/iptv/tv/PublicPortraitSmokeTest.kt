package de.iptv.tv

import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in internet smoke test. Normal CI tests use local, deterministic HTTP fixtures. */
@RunWith(AndroidJUnit4::class)
class PublicPortraitSmokeTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    @Test fun castAndPortraitLoadWithNoPersonalToken()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicMetadataSmoke")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val logoReport=JSONObject()
        val probe=okhttp3.OkHttpClient.Builder().connectTimeout(5,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5,java.util.concurrent.TimeUnit.SECONDS).callTimeout(7,java.util.concurrent.TimeUnit.SECONDS).build()
        for(scheme in listOf("http","https")){
            // A verified public PNG: no playlist account path, credentials or query parameters.
            val status=try{probe.newCall(okhttp3.Request.Builder().url("$scheme://lo.tvigate.net/igatetv.png").build()).execute().use{it.code.toString()}}
                catch(error:java.io.IOException){error.javaClass.simpleName}
            logoReport.put(scheme,status)
        }
        File("/sdcard/Download/iptv-metadata").mkdirs()
        File("/sdcard/Download/iptv-metadata/public-logo-probe.json").writeText(logoReport.toString())
        val item=MetadataItem("public-smoke","arrival",MediaType.MOVIE,"Arrival",2016)
        val result=MetadataService(MetadataCache(),{null}).load(item,"en-US",true){
            JSONObject().put("attributes",JSONObject().put("tmdb_id",329865))
        }
        assertTrue("Known public movie exposes actual cast",result.cast.any{it.clean("name")=="Amy Adams"})
        assertTrue("Actual cast has portrait paths",result.cast.any{Portraits.address(it)!=null})
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            lateinit var activity:MainActivity
            lateinit var dialog:android.app.Dialog
            lateinit var page:MovieDetailsView
            val loader=ArtworkLoader(context,null)
            scenario.onActivity{
                activity=it
                page=MovieDetailsView(it,item,loader,{},{},{},{},{})
                page.favorite(true);page.render(result,null)
                dialog=android.app.Dialog(it).apply{setContentView(page);show();window!!.setLayout(-1,-1)}
            }
            try{
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync{
                    val grid=page.descendants.filterIsInstance<RecyclerView>().first()
                    grid.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
                }
                val deadline=SystemClock.elapsedRealtime()+20_000
                var loaded=false
                while(!loaded&&SystemClock.elapsedRealtime()<deadline){
                    instrumentation.runOnMainSync{loaded=page.descendants.filterIsInstance<ImageView>().any{
                        it.tag?.toString()?.startsWith("person:9273:")==true&&it.drawable is BitmapDrawable
                    }}
                    SystemClock.sleep(50)
                }
                assertTrue("Amy Adams' real portrait downloads and decodes in the emulator without a token",loaded)
                val directory=File("/sdcard/Download/iptv-metadata").apply{mkdirs()}
                assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(directory,"public-cast-no-token.png")))
            }finally{
                instrumentation.runOnMainSync{dialog.dismiss();page.dispose()};loader.close();finishTvActivity(activity)
            }
        }
    }
}
