package de.iptv.tv

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandingTest {
    @get:org.junit.Rule val remoteUi=RemoteUiRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    @Test fun installedMetadataStartupAndAboutUseRustreamBranding(){
        val manager=context.packageManager
        val application=manager.getApplicationInfo(context.packageName,0)
        assertEquals("RUSTREAM IPTV PLAYER",manager.getApplicationLabel(application).toString())
        assertEquals(context.getString(R.string.app_description),application.loadDescription(manager).toString())
        assertNotNull("Installed application icon",application.loadIcon(manager))
        val banner=application.loadBanner(manager)
        assertNotNull("TV launcher banner",banner)
        assertEquals("TV banner ratio",16f/9f,banner!!.intrinsicWidth.toFloat()/banner.intrinsicHeight,0.01f)
        val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(context.packageName)
        assertNotNull("TV launcher activity resolves",manager.resolveActivity(launcher,0))
        val store=SourceStore(context);val previous=store.load()
        try{
            store.save(emptyList())
            withTvActivity{
                assertTrue(device.wait(Until.hasObject(By.text("RUSTREAM IPTV PLAYER")),15_000))
                assertTrue(device.wait(Until.hasObject(By.text("Quelle hinzufügen").focused(true)),10_000))
                device.executeShellCommand("screencap -p /sdcard/Download/rustream-startup.png")
                device.pressDPadCenter()
                assertTrue(device.wait(Until.hasObject(By.text("Einstellungen")),5_000))
                assertTrue(device.wait(Until.hasObject(By.text("Quellen verwalten").focused(true)),5_000))
                // Remote navigation scrolls the existing settings menu down to its final action.
                repeat(10){device.pressDPadDown()}
                assertTrue(device.wait(Until.hasObject(By.text("App-Info").focused(true)),5_000))
                device.pressDPadCenter()
                // The app shows its own language (AppLanguage), not necessarily the emulator system language.
                val appText=AppLanguage.wrap(context)
                assertTrue(device.wait(Until.hasObject(By.text(appText.getString(R.string.app_description))),5_000))
                assertTrue(device.hasObject(By.text(appText.getString(R.string.app_version,BuildConfig.VERSION_NAME))))
                assertTrue(device.hasObject(By.text("developed by Ninos Oshana")))
                device.executeShellCommand("screencap -p /sdcard/Download/rustream-app-info.png")
                device.pressBack()
            }
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("Android shows the installed app name",device.wait(Until.hasObject(By.text("RUSTREAM IPTV PLAYER")),10_000))
            device.executeShellCommand("screencap -p /sdcard/Download/rustream-installed-info.png")
            device.pressBack()
        }finally{store.save(previous)}
    }
    @Test fun languageSettingSwitchesTheWholeInterfaceAndBack(){
        val store=SourceStore(context);val previous=store.load()
        fun chooseLanguage(settings:String,entry:String,index:Int){
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text(settings)),5_000))
            repeat(8){device.pressDPadDown()}
            assertTrue(device.wait(Until.hasObject(By.text(entry).focused(true)),5_000))
            device.pressDPadCenter()
            assertTrue(device.wait(Until.hasObject(By.text("English")),5_000))
            // The dialog focuses the checked language; English is the second choice.
            if(index==1)device.pressDPadDown() else device.pressDPadUp()
            device.pressDPadCenter()
        }
        try{
            store.save(emptyList())
            withTvActivity{ scenario->
                assertTrue(device.wait(Until.hasObject(By.text("Quelle hinzufügen").focused(true)),10_000))
                chooseLanguage("Einstellungen","Sprache · Language",1)
                // The rebuilt activity shows every text, including the startup screen, in English.
                assertTrue(device.wait(Until.hasObject(By.text("Add source").focused(true)),10_000))
                assertTrue(device.hasObject(By.text("Your TV starts here")))
                device.executeShellCommand("screencap -p /sdcard/Download/rustream-language-en.png")
                chooseLanguage("Settings","Language · Sprache",0)
                assertTrue(device.wait(Until.hasObject(By.text("Quelle hinzufügen").focused(true)),10_000))
                assertEquals("de",AppLanguage.current(context))
                // Track the rebuilt activity so the rule finishes it, not only the replaced one.
                scenario.onTvActivity{}
            }
        }finally{
            store.save(previous)
            context.getSharedPreferences("MainActivity",android.content.Context.MODE_PRIVATE).edit().remove("language").commit()
        }
    }
}
