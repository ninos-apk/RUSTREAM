@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in visual/real-decoder check against a host-side, strictly local synthetic provider. */
@RunWith(AndroidJUnit4::class)
class UiPreviewTest {
    @Test fun importLocalDemoAndSeekRealVideo() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val url=InstrumentationRegistry.getArguments().getString("uiDemoUrl")
        assumeTrue("No local UI preview provider supplied",url?.startsWith("http://127.0.0.1:")==true)
        val device=UiDevice.getInstance(instrumentation)
        fun controller(activity:MainActivity)=MainActivity::class.java.getDeclaredField("playback").apply { isAccessible=true }.get(activity) as PlaybackController
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Quelle hinzufügen")),10_000))
            device.findObject(By.text("Quelle hinzufügen")).click()
            assertTrue(device.wait(Until.hasObject(By.text("URL hinzufügen")),5_000))
            device.findObject(By.text("URL hinzufügen")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("HTTP(S)-URL")),5_000))
            device.findObject(By.desc("Name")).text="UI Demo"
            device.findObject(By.desc("HTTP(S)-URL")).text=url
            device.findObject(By.res("android:id/button1")).click()
            SystemClock.sleep(300)
            device.executeShellCommand("screencap -p /sdcard/Download/ui-design-import.png")
            assertTrue(device.wait(Until.hasObject(By.desc("Demo Sender 001")),15_000))
            device.findObject(By.text("Movies (1)")).click()
            val title="Demo Film fuer die neue TV-Oberflaeche"
            assertTrue(device.wait(Until.hasObject(By.desc(title)),5_000))
            scenario.onActivity { activity -> activity.findViewById<ViewGroup>(android.R.id.content).descendants.first { it.contentDescription==title }.requestFocus() }
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("Wiedergabe · $title")),10_000))
            device.pressKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE)
            assertTrue(device.wait(Until.hasObject(By.text("Pausiert · $title")),5_000))
            device.pressDPadCenter()
            fun position():Long { var value=0L;scenario.onActivity { value=controller(it).player!!.currentPosition };return value }
            val before=position()
            device.pressDPadRight()
            val tapDeadline=SystemClock.elapsedRealtime()+3_000
            while(position()<before+29_000 && SystemClock.elapsedRealtime()<tapDeadline)SystemClock.sleep(25)
            val afterTap=position()
            assertTrue("Short press moves thirty seconds",afterTap-before in 29_000..31_000)
            device.executeShellCommand("screencap -p /sdcard/Download/ui-design-real-tap.png")
            val down=SystemClock.uptimeMillis()
            instrumentation.sendKeySync(KeyEvent(down,down,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,0))
            SystemClock.sleep(2500)
            instrumentation.sendKeySync(KeyEvent(down,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT,0))
            val released=position()
            assertTrue("Hold advances beyond a single extra tap",released-afterTap>60_000)
            SystemClock.sleep(450)
            assertTrue("Release stops seeking",kotlin.math.abs(position()-released)<500)
            scenario.onActivity { assertFalse(controller(it).player!!.playWhenReady) }
            android.util.Log.i("IptvUiSeek","tap_delta_ms=${afterTap-before} hold_delta_ms=${released-afterTap} released_position_ms=$released")
            device.executeShellCommand("screencap -p /sdcard/Download/ui-design-real-hold.png")
            device.pressDPadDown()
            device.executeShellCommand("screencap -p /sdcard/Download/ui-design-real-controls.png")
        }
    }
}
