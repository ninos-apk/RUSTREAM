package de.iptv.tv

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.SystemClock
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: the live channel list of a pushed private playlist shows its logos. */
@RunWith(AndroidJUnit4::class)
class LiveLogoReviewTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)

    @Test fun liveListShowsLogos(){
        val path=InstrumentationRegistry.getArguments().getString("privatePlaylist")
        assumeTrue("Private list not supplied",path!=null)
        val privateFile=File(checkNotNull(path))
        val store=SourceStore(context)
        val previous=store.load()
        val resultFile=File(context.getExternalFilesDir(null),"live-logo-review.json")
        try{
            store.save(listOf(JSONObject().put("kind","file").put("name","Private Testplaylist").put("uri",Uri.fromFile(privateFile).toString())))
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                var loaded=0;var images=0;var stats=JSONObject()
                val deadline=SystemClock.elapsedRealtime()+150_000
                while(SystemClock.elapsedRealtime()<deadline){
                    scenario.onActivity{activity->
                        val list=field(activity,"channels") as RecyclerView
                        val views=(0 until list.childCount).mapNotNull{(list.getChildAt(it) as? android.view.ViewGroup)?.getChildAt(0) as? ImageView}
                        images=views.size;loaded=views.count{it.drawable is BitmapDrawable}
                        stats=(field(activity,"artwork") as ArtworkLoader).stats()
                    }
                    if(images>0&&loaded*2>=images)break
                    SystemClock.sleep(500)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let{shot->
                    File(context.getExternalFilesDir(null),"live-logo-review.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}
                }
                resultFile.writeText(JSONObject().put("images",images).put("loaded",loaded).put("artwork",stats).toString(2))
                assertTrue("Live logos loaded: $loaded of $images; artwork=$stats",images>0&&loaded*2>=images)
            }
        }finally{store.save(previous)}
    }
}
