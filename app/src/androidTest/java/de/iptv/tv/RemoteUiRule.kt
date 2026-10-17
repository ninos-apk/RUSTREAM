package de.iptv.tv

import androidx.test.uiautomator.Configurator
import org.junit.rules.ExternalResource

/** Playback statistics update every second, so waiting for global UI silence is unsuitable.
 * Tests wait explicitly for the state they assert after sending real remote key events.
 */
class RemoteUiRule:ExternalResource(){
    private var previous=10_000L
    override fun before(){
        val config=Configurator.getInstance()
        previous=config.waitForIdleTimeout
        config.setWaitForIdleTimeout(0)
    }
    override fun after(){Configurator.getInstance().setWaitForIdleTimeout(previous)}
}

/** Finish on the main thread before ActivityScenario.close waits for global idle.
 * A playing video keeps scheduling frames on API 36, preventing that global wait.
 */
fun finishTvActivity(activity:MainActivity){
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{activity.finish()}
    val deadline=android.os.SystemClock.elapsedRealtime()+5_000
    while(!activity.isDestroyed&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(20)
    org.junit.Assert.assertTrue("Activity releases playback on finish",activity.isDestroyed)
}

private val tvActivities=java.util.IdentityHashMap<androidx.test.core.app.ActivityScenario<MainActivity>,MainActivity>()
fun withTvActivity(block:(androidx.test.core.app.ActivityScenario<MainActivity>)->Unit){
    val scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
    lateinit var activity:MainActivity
    scenario.onActivity{activity=it}
    tvActivities[scenario]=activity
    try{
        val deadline=android.os.SystemClock.elapsedRealtime()+10_000
        var focused=false
        while(!focused&&android.os.SystemClock.elapsedRealtime()<deadline){
            scenario.onTvActivity{focused=it.hasWindowFocus()}
            if(!focused)android.os.SystemClock.sleep(20)
        }
        org.junit.Assert.assertTrue("Activity owns the remote input window",focused)
        block(scenario)
    }finally{
        try{finishTvActivity(checkNotNull(tvActivities[scenario]))}finally{tvActivities.remove(scenario);scenario.close()}
    }
}
fun androidx.test.core.app.ActivityScenario<MainActivity>.onTvActivity(block:(MainActivity)->Unit){
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync{
        val resumed=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        val activity=resumed?:checkNotNull(tvActivities[this])
        tvActivities[this]=activity
        block(activity)
    }
}
