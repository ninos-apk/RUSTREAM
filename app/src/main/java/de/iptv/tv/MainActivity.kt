@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.view.accessibility.AccessibilityNodeInfo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnNextLayout
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var core: Core
    private lateinit var loader: SourceLoader
    private lateinit var store: SourceStore
    private lateinit var epgStore: SourceStore
    private lateinit var playback: PlaybackController
    private lateinit var status: TextView
    private lateinit var epg: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var categorySearch: EditText
    private lateinit var channelSearch: EditText
    private lateinit var categoryEmpty: TextView
    private lateinit var channelEmpty: TextView
    private lateinit var channelHeading: TextView
    private lateinit var sidebar: LinearLayout
    private lateinit var channels: RecyclerView
    private lateinit var categories: RecyclerView
    private lateinit var video: PlayerView
    private lateinit var stage: FrameLayout
    private lateinit var chrome: LinearLayout
    private lateinit var playerSlot: FrameLayout
    private lateinit var playerPane: PlayerPane
    private lateinit var details: ConnectionDetails
    private lateinit var settingsButton: TvIconButton
    private lateinit var resultCount: TextView
    private lateinit var startup: StartupPane
    private lateinit var clearFiltersButton: Button
    private var libraryReady = false
    private var catalogPending = false
    private var guideStarted = false
    private var startupWork: Job? = null
    private var selectionWork: Job? = null
    private var reconnectWork: Job? = null
    private var libraryFocusId: Long? = null
    private var libraryFocusIndex = 0
    private var libraryFocusRevision = 0L
    private var fillVideo = true
    private var restoreFocus: View? = null
    private var loadingPage = false
    private var pendingPrevious=false
    private var pendingPreviousFocus=false
    private var pendingPreviousSelect=false
    private var focusNextPage = false
    private var selectNextPage = false
    private var sources = mutableListOf<JSONObject>()
    private var activeSource: JSONObject? = null
    private var lastFailure: JSONObject? = null
    private var revision = 0L
    private var category: Long? = null
    private var kind = "live"
    private var categoryItems = emptyList<CategoryItem>()
    private val kindButtons = mutableMapOf<String, Button>()
    private var searchWork: Job? = null
    private var refreshWork: Job? = null
    private var query = ""
    private var offset = 0
    private var total = 0
    private var searchGeneration = 0
    private var selectedId: Long? = null
    private var fullScreen = false
    private var busy = false
    private var firstChannelFocus = true
    private var lifecycleWork: Job? = null
    private val channelRows = Rows(true) { item -> selectChannel(item.id) }
    private val categoryRows = Rows { item ->
        category = item.id.takeIf { it >= 0 }; offset = 0
        renderCategories(); search()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        loader = SourceLoader(applicationContext); store = SourceStore(this); epgStore = SourceStore(this, "epg.enc")
        try { core = Core() } catch (_: Throwable) {
            setContentView(TextView(this).apply { text = "Rust-Core konnte nicht geladen werden. Passendes APK installieren."; textSize = 24f; setPadding(48,48,48,48) }); return
        }
        buildUi()
        loader.progress = { message -> scope.launch { if(busy) { status.text=message; startup.updateProgress(message) } } }
        playback = PlaybackController(this, core, scope, video, { status.text = it }, { stats ->
            playerPane.hasSelection = selectedId != null
            playerPane.update(stats)
            details.update(stats)
            details.refreshButton.isEnabled = selectedId != null && reconnectWork?.isActive != true && !busy
        })
        playback.bufferProfile = PlaybackBufferProfile.fromKey(getPreferences(MODE_PRIVATE).getString("liveBufferProfile", null))
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        startLibrary()
    }
    private fun startLibrary() {
        if (startupWork?.isActive == true) return
        chrome.visibility = View.INVISIBLE
        startup.showLoading("Gespeicherte Quelle wird geladen …")
        startupWork = launchSafe {
            sources = withContext(Dispatchers.IO) { store.load() }
            if (sources.isNotEmpty()) {
                val activeId=getPreferences(MODE_PRIVATE).getString("activeSourceId",null)
                var source=sources.first()
                for(candidate in sources) if(loader.identity(candidate)==activeId){source=candidate;break}
                importSource(source,false,true)
            } else startup.showEmpty()
        }
    }
    private fun startGuide() {
        if (guideStarted) return
        guideStarted = true
        launchSafe {
            val guides = withContext(Dispatchers.IO) { epgStore.load() }
            if (guides.isNotEmpty()) { loader.load(guides.first(), core, true); refreshGuide() }
        }
    }
    private fun showLibrary() {
        libraryReady = true; catalogPending = false
        startup.dismiss(); chrome.visibility = View.VISIBLE
        channels.post { focusFirstChannel() }
        startGuide()
    }
    private fun buildUi() {
        val largeText = resources.configuration.fontScale > 1.3f
        fillVideo = getPreferences(MODE_PRIVATE).getBoolean("fillVideo",true)
        stage = FrameLayout(this).apply { setBackgroundColor(TvUi.background) }
        chrome = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(10),dp(18),dp(6)) }
        toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        toolbar.addView(label("IPTV",20f).apply { letterSpacing = 0.12f; setTypeface(typeface,android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(dp(if(largeText)145 else 120),-2))
        for ((key,title) in listOf("live" to "Live","movies" to "Movies","series" to "Series")) {
            val tab = button(title) { switchKind(key) }
            kindButtons[key] = tab
            toolbar.addView(tab,LinearLayout.LayoutParams(dp(if(largeText)166 else 116),-2).apply { marginEnd = dp(6) })
        }
        toolbar.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        settingsButton = TvIconButton(this,"settings","Einstellungen") { settingsDialog() }.apply { id = View.generateViewId() }
        toolbar.addView(settingsButton,LinearLayout.LayoutParams(dp(36),dp(36)))
        chrome.addView(toolbar,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        val body = LinearLayout(this)
        sidebar = LinearLayout(this)
        categories = list(categoryRows).apply { contentDescription = "Kategorienliste" }
        channels = list(channelRows).apply { contentDescription = "Eintragsliste" }
        val categoryColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5),dp(5),dp(5),0); background = TvUi.surface(TvUi.panel,dp(7).toFloat()) }
        categoryColumn.addView(label("Kategorien",12f).apply { setTextColor(TvUi.muted); setPadding(dp(4),dp(4),dp(4),dp(6)) },LinearLayout.LayoutParams(-1,-2))
        categorySearch = searchField("Kategorie suchen") { renderCategories() }
        categoryColumn.addView(categorySearch,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        categoryEmpty = label("Keine Kategorien gefunden",12f).apply { visibility=View.GONE; setTextColor(TvUi.muted) }
        categoryColumn.addView(categoryEmpty)
        categoryColumn.addView(categories,LinearLayout.LayoutParams(-1,0,1f))
        val channelColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5),dp(5),dp(5),0); background = TvUi.surface(TvUi.panel,dp(7).toFloat()) }
        channelHeading = label("Alle Kategorien",12f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
        channelHeading.setPadding(dp(4),dp(4),dp(4),dp(6))
        channelColumn.addView(channelHeading,LinearLayout.LayoutParams(-1,-2))
        channelSearch = searchField("Sender suchen") {
            query=channelSearch.text.toString(); offset=0; searchGeneration++
            searchWork?.cancel(); searchWork=scope.launch { delay(200); search() }
        }
        channelColumn.addView(channelSearch,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        channelEmpty = label("Keine Einträge gefunden",12f).apply { visibility=View.GONE; setTextColor(TvUi.muted) }
        channelColumn.addView(channelEmpty)
        clearFiltersButton = button("Suche zurücksetzen") {
            category=null; query=""; categorySearch.setText(""); channelSearch.setText("")
            searchWork?.cancel(); renderCategories(); search(); channelSearch.requestFocus()
        }.apply { visibility=View.GONE }
        channelColumn.addView(clearFiltersButton,LinearLayout.LayoutParams(-1,-2))
        channelColumn.addView(channels,LinearLayout.LayoutParams(-1,0,1f))
        resultCount=label("0 Einträge",10f).apply { setTextColor(TvUi.muted) }
        channelColumn.addView(resultCount,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) })
        sidebar.addView(categoryColumn,LinearLayout.LayoutParams(dp(if(largeText)210 else 148),-1))
        sidebar.addView(channelColumn,LinearLayout.LayoutParams(dp(if(largeText)260 else 216),-1).apply { marginStart=dp(7); marginEnd=dp(12) })
        body.addView(sidebar,LinearLayout.LayoutParams(-2,-1))
        val media=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        playerSlot=FrameLayout(this)
        playerPane=PlayerPane(this,scope,::playerAction) { target -> playback.seekTo(target) }
        video=playerPane.video
        playerSlot.addView(playerPane,FrameLayout.LayoutParams(-1,-1))
        media.addView(playerSlot,LinearLayout.LayoutParams(-1,0,1f))
        status=label("Sender wählen",13f).apply {
            maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(6),dp(5),dp(6),dp(5))
            TvUi.focus(this,1f); setOnClickListener { showProgrammeDetails() }
        }
        media.addView(status,LinearLayout.LayoutParams(-1,-2))
        epg=label("Sender wählen, um das Programm anzuzeigen",12f).apply { maxLines=2; setTextColor(TvUi.muted); ellipsize=TextUtils.TruncateAt.END }
        epg.setPadding(dp(6),dp(4),dp(6),dp(4))
        TvUi.focus(epg,1f); epg.setOnClickListener { showProgrammeDetails() }
        media.addView(epg,LinearLayout.LayoutParams(-1,-2))
        status.doAfterTextChanged { playerPane.updateInformation(status.text.toString(),epg.text.toString()) }
        epg.doAfterTextChanged { playerPane.updateInformation(status.text.toString(),epg.text.toString()) }
        body.addView(media,LinearLayout.LayoutParams(0,-1,1f))
        chrome.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        details=ConnectionDetails(this) { playerAction("reconnect") }
        chrome.addView(details,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        stage.addView(chrome,FrameLayout.LayoutParams(-1,-1))
        startup=StartupPane(this,::startLibrary) { settingsDialog() }
        stage.addView(startup,FrameLayout.LayoutParams(-1,-1))
        setContentView(stage)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowInsetsControllerCompat(window,stage).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        channels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView,dx: Int,dy: Int) {
                val layout=view.layoutManager as LinearLayoutManager
                if (layout.findLastVisibleItemPosition()>=channelRows.itemCount-12) loadNextPage()
                if(layout.findFirstVisibleItemPosition()<=12&&offset>0)loadPreviousPage()
            }
        })
    }
    private fun settingsDialog() {
        val choices=arrayOf("Quellen verwalten","Datei hinzufügen","URL hinzufügen","Xtream anmelden","EPG importieren","Bilddarstellung","Live-Pufferprofil","Diagnosebericht")
        val dialog=AlertDialog.Builder(this).create()
        val menu=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(20),dp(18),dp(16))
            background=TvUi.surface(TvUi.panel,dp(10).toFloat())
        }
        menu.addView(label("Einstellungen",20f),LinearLayout.LayoutParams(-1,-2))
        menu.addView(label("Quellen & Wiedergabe",12f).apply { setTextColor(TvUi.muted); setPadding(0,dp(10),0,dp(10)) },LinearLayout.LayoutParams(-1,-2))
        val actions=choices.mapIndexed { index,choice ->
            button(choice) {
                    dialog.dismiss()
                    when(index) { 0->chooseSources(); 1->pickDocument(false); 2->urlDialog(false); 3->xtreamDialog(); 4->epgDialog(); 5->pictureDialog(); 6->bufferDialog(); 7->diagnosticsDialog() }
            }.apply {
                menu.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(4) })
            }
        }
        menu.addView(label("Zurück zum Schließen",11f).apply { setTextColor(TvUi.muted); setPadding(0,dp(8),0,0) },LinearLayout.LayoutParams(-1,-2))
        dialog.setView(ScrollView(this).apply { isFillViewport=false; addView(menu) },0,0,0,0)
        dialog.setOnDismissListener { if(startup.visibility==View.VISIBLE) startup.focusPrimary() else settingsButton.requestFocus() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(if(resources.configuration.fontScale>1.3f)460 else 310),dp(resources.configuration.screenHeightDp-36))
            setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            attributes=attributes.apply { x=dp(18); dimAmount=0.55f }
        }
        actions.first().requestFocus()
    }
    private fun pictureDialog() {
        AlertDialog.Builder(this).setTitle("Bilddarstellung im Vollbild")
            .setSingleChoiceItems(arrayOf("Bild füllen · proportional zuschneiden","Originalformat · vollständiges Bild"),if(fillVideo)0 else 1) { dialog,index ->
                fillVideo=index==0
                getPreferences(MODE_PRIVATE).edit().putBoolean("fillVideo",fillVideo).apply()
                dialog.dismiss()
            }.setNegativeButton("Schließen",null).show()
    }
    private fun bufferDialog() {
        val profiles = PlaybackBufferProfile.entries
        AlertDialog.Builder(this).setTitle("Live-Puffer · ab nächstem Verbindungsaufbau")
            .setSingleChoiceItems(profiles.map { it.label }.toTypedArray(), profiles.indexOf(playback.bufferProfile)) { dialog, index ->
                playback.bufferProfile = profiles[index]
                getPreferences(MODE_PRIVATE).edit().putString("liveBufferProfile", profiles[index].key).apply()
                dialog.dismiss()
            }.setNegativeButton("Schließen", null).show()
    }
    private fun selectChannel(id: Long) {
        if(selectionWork?.isActive==true || busy) return
        if (id==selectedId && playback.player!=null && playback.phase !in setOf("idle","finished","error")) {
            playerPane.cancelSeek(); setFullScreen(true); return
        }
        playerPane.cancelSeek()
        selectionWork=launchSafe {
        val requestRevision=revision
        val entry=core.call("entry","revision" to requestRevision,"id" to id)
        val seriesId=entry.optString("series_id").takeIf{it.isNotEmpty()&&it!="null"}
        if(seriesId!=null){
            val source=activeSource ?: return@launchSafe
            var data=core.call("episodes","revision" to requestRevision,"id" to id,"offset" to 0)
            if(data.getJSONArray("seasons").length()==0){
                status.text="Episoden werden geladen …"
                data=loader.episodes(source,core,requestRevision,id,seriesId)
            }
            if(revision!=requestRevision)return@launchSafe
            val seasons=data.getJSONArray("seasons")
            AlertDialog.Builder(this@MainActivity).setTitle(entry.getString("name"))
                .setItems(Array(seasons.length()){i->"Staffel ${seasons.getJSONObject(i).getLong("season")} (${seasons.getJSONObject(i).getInt("count")})"}){_,index->
                    showEpisodes(requestRevision,id,seasons.getJSONObject(index).getLong("season"),0)
                }.setNegativeButton("Schließen",null).show()
        }else{playback.select(requestRevision,id);markChannel(id);refreshGuide()}
    } }
    private fun showEpisodes(requestRevision:Long,id:Long,season:Long,page:Int){ launchSafe {
        val data=core.call("episodes","revision" to requestRevision,"id" to id,"season" to season,"offset" to page)
        if(revision!=requestRevision)return@launchSafe
        val rows=data.getJSONArray("items")
        val dialog=AlertDialog.Builder(this@MainActivity).setTitle("Staffel $season")
            .setItems(Array(rows.length()){rows.getJSONObject(it).getString("name")}){_,index->
                val episodeId=rows.getJSONObject(index).getLong("id")
                if(revision==requestRevision)selectChannel(episodeId)
            }.setNegativeButton("Schließen",null)
        if(rows.length()==128)dialog.setPositiveButton("Weitere Episoden"){_,_->showEpisodes(requestRevision,id,season,page+128)}
        if(page>0)dialog.setNeutralButton("Vorherige Episoden"){_,_->showEpisodes(requestRevision,id,season,(page-128).coerceAtLeast(0))}
        dialog.show()
    } }
    private fun playerAction(action: String) {
        when(action) {
            "play" -> launchSafe {
                if(playback.player==null || playback.phase=="finished") selectedId?.let { playback.select(revision,it); markChannel(it) }
                else playback.togglePause()
            }
            "back" -> launchSafe { playback.seek(-30_000) }
            "forward" -> launchSafe { playback.seek(30_000) }
            "stop" -> launchSafe { playerPane.cancelSeek(); playback.stop(); channelRows.notifyDataSetChanged(); playerPane.showControls(true) }
            "reconnect" -> {
                if(reconnectWork?.isActive==true || busy || selectedId==null) return
                val restoreRefreshFocus=details.refreshButton.hasFocus()
                playerPane.cancelSeek(); details.refreshButton.isEnabled=false
                reconnectWork=launchSafe { try {
                    if(playback.player==null) selectedId?.let { playback.select(revision,it) } else playback.reconnect()
                } finally {
                    details.refreshButton.isEnabled=selectedId!=null && !busy
                    if(restoreRefreshFocus && details.refreshButton.isEnabled && hasWindowFocus())details.refreshButton.requestFocus()
                } }
            }
            "fullscreen" -> setFullScreen(!fullScreen)
            "library" -> returnToChannels()
            "statistics" -> details.refreshButton.takeIf { it.isEnabled }?.requestFocus() ?: status.requestFocus()
        }
    }
    override fun onStart() {
        super.onStart()
        if (!::playback.isInitialized) return
        val previous = lifecycleWork
        lifecycleWork = scope.launch { previous?.join(); playback.start() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::playerPane.isInitialized) playerPane.cancelSeek()
        if (hasFocus && ::channels.isInitialized && !fullScreen && libraryReady) channels.post { focusFirstChannel() }
    }
    private fun focusFirstChannel() {
        if (!firstChannelFocus) return
        val first = channels.findViewHolderForAdapterPosition(0)?.itemView ?: return
        if (first.requestFocus()) firstChannelFocus = false
    }
    override fun onStop() {
        if (::playerPane.isInitialized) playerPane.cancelSeek()
        if (::playback.isInitialized) {
            val previous = lifecycleWork
            lifecycleWork = scope.launch { previous?.join(); playback.suspendPlayback() }
        }
        super.onStop()
    }
    override fun onDestroy() {
        if (::playback.isInitialized) playback.release()
        scope.cancel()
        if (::loader.isInitialized) loader.close()
        if (::core.isInitialized) core.close()
        super.onDestroy()
    }
    private fun launchSafe(block: suspend () -> Unit): Job {
        return scope.launch {
            try { block() } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val details=ImportErrors.describe(error)
                lastFailure=details.json().put("exception_type",error.javaClass.simpleName)
                val message=details.message
                status.text = message
                if (!libraryReady) {
                    chrome.visibility=View.INVISIBLE; startup.showError(message)
                } else AlertDialog.Builder(this@MainActivity).setTitle("Vorgang fehlgeschlagen").setMessage(message)
                    .setNeutralButton("Details"){_,_->diagnosticsDialog()}
                    .setPositiveButton("Quelle wählen"){_,_->chooseSources()}.setNegativeButton("Schließen", null).show()
            }
        }
    }
    private suspend fun importSource(source: JSONObject, add: Boolean, preferCache: Boolean = false) {
        if (busy) return
        if(!preferCache)refreshWork?.cancel()
        busy = true
        if (!libraryReady) startup.showLoading("Quelle wird geladen …")
        playerPane.cancelSeek()
        status.text = "Playlist wird importiert …"
        try {
            val cached=if(preferCache)try{loader.restore(source,core)}catch(error:Exception){
                if(error is kotlinx.coroutines.CancellationException)throw error
                lastFailure=ImportErrors.describe(error,"restore_cache").json();null
            }else null
            val result = cached ?: loader.load(source, core)
            // Playlist replacement is explicit: stop the previous media and its watchdog together.
            playback.stop()
            revision = result.getLong("revision"); selectedId = null
            libraryFocusId=null; libraryFocusIndex=0; libraryFocusRevision=revision
            activeSource=source
            searchGeneration++
            channelRows.replace(emptyList())
            firstChannelFocus = true
            refreshCategories()
            if (!libraryReady) catalogPending=true
            search()
            if (add) {
                val updated = sources.toMutableList().apply { add(source) }
                try{withContext(Dispatchers.IO) { store.save(updated) };sources=updated}
                catch(error:Exception){throw ImportFailure("source_save_failed","save_source")}
            }
            getPreferences(MODE_PRIVATE).edit().putString("activeSourceId",loader.identity(source)).apply()
            val warning=result.optJSONArray("warnings")?.length()?:0
            status.text = "${result.getInt("count")} Einträge${if(cached!=null)" · gespeichert" else ""}${if(warning>0)" · Hinweise im Diagnosebericht" else ""} · ${source.optString("name", "Playlist")}"
            if(cached!=null&&loader.shouldRefresh(source)){
                status.text="Gespeicherte Liste · Aktualisierung im Hintergrund …"
                refreshWork=launchSafe {
                // Build a new generation without disturbing playback or its current revision.
                loader.load(source,core,activate=false)
                if(activeSource===source&&selectedId==null&&!busy)importSource(source,false,true)
                }
            }
        } finally { busy = false; details.refreshButton.isEnabled=selectedId!=null }
    }
    private suspend fun refreshCategories() {
        val data = core.call("categories")
        val array = data.getJSONArray("categories")
        categoryItems = List(array.length()) { i ->
            val c = array.getJSONObject(i)
            CategoryItem(c.getLong("id"), c.getString("name"), c.getString("kind"), c.getInt("count"))
        }
        val kinds = data.getJSONArray("kinds")
        for (i in 0 until kinds.length()) {
            val section = kinds.getJSONObject(i)
            val key = section.getString("id")
            val title = when (key) { "live" -> "Live"; "movies" -> "Movies"; else -> "Series" }
            kindButtons[key]?.text = "$title (${section.getInt("count")})"
        }
        switchKind(data.getString("preferred_kind"), false)
    }
    private fun switchKind(value: String, refresh: Boolean = true) {
        kind = value; category = null; offset = 0; query = ""
        searchGeneration++
        searchWork?.cancel()
        channelSearch.setText(""); categorySearch.setText("")
        searchWork?.cancel()
        channelSearch.hint = when (kind) { "movies" -> "Filme suchen"; "series" -> "Serien / Episoden suchen"; else -> "Sender suchen" }
        channelSearch.contentDescription = channelSearch.hint
        kindButtons.forEach { (key, tab) -> tab.isActivated = key == kind; tab.isSelected = key == kind }
        channelRows.replace(emptyList()); total = 0
        channels.scrollToPosition(0); categories.scrollToPosition(0)
        renderCategories()
        if (refresh) search()
    }
    private fun renderCategories() {
        val text = categorySearch.text.toString().trim().lowercase(java.util.Locale.ROOT)
        val matches = categoryItems.filter { it.kind == kind && it.name.lowercase(java.util.Locale.ROOT).contains(text) }
        val rows = mutableListOf(Row(-1, "Alle Kategorien"))
        rows += matches.map { Row(it.id, "${it.name} (${it.count})") }
        categoryRows.replace(rows)
        categoryEmpty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        categoryEmpty.text = if (text.isNotEmpty()) "Keine Kategorien für „${categorySearch.text}“. Suche löschen, um alle Kategorien zu sehen." else "In diesem Bereich gibt es keine Kategorien."
        channelHeading.text = categoryItems.firstOrNull { it.id == category }?.name ?: "Alle Kategorien"
    }
    private fun search() {
        offset=0; loadingPage=false; focusNextPage=false; selectNextPage=false
        pendingPrevious=false;pendingPreviousFocus=false;pendingPreviousSelect=false
        val generation = ++searchGeneration
        val requestRevision = revision
        val requestKind = kind
        val requestCategory = category
        val requestQuery = query
        val requestOffset = offset
        launchSafe {
            val data = core.call("search", "revision" to requestRevision, "kind" to requestKind, "category" to requestCategory, "query" to requestQuery, "offset" to requestOffset, "limit" to 64)
            if (generation != searchGeneration || revision != requestRevision) return@launchSafe
            total = data.getInt("total")
            val array = data.getJSONArray("items")
            channelRows.replace(List(array.length()) { i -> val c = array.getJSONObject(i); Row(c.getLong("id"), c.getString("name")) })
            channelEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE
            channelEmpty.text = if(requestQuery.isNotBlank()) "Keine Treffer für „$requestQuery“." else "Keine Einträge in dieser Auswahl."
            clearFiltersButton.visibility = if(total==0 && (requestQuery.isNotBlank() || requestCategory!=null || categorySearch.text.isNotBlank())) View.VISIBLE else View.GONE
            if (catalogPending) {
                channels.doOnNextLayout { if(generation==searchGeneration) showLibrary() }
                channels.requestLayout()
            }
            if (firstChannelFocus && array.length() > 0) {
                channels.doOnNextLayout {
                    focusFirstChannel()
                }
            }
            resultCount.text = if(total==1) "1 Eintrag" else "$total Einträge"
        }
    }
    private fun loadNextPage(focusNew: Boolean = false, selectNew: Boolean = false) {
        if(searchWork?.isActive==true || offset+channelRows.itemCount>=total || channelRows.itemCount==0) return
        focusNextPage=focusNextPage || focusNew; selectNextPage=selectNextPage || selectNew
        if(loadingPage) return
        loadingPage=true
        val generation=searchGeneration
        val requestRevision=revision
        val start=offset+channelRows.itemCount
        val requestKind=kind; val requestCategory=category; val requestQuery=query
        launchSafe {
            try {
                val data=core.call("search","revision" to requestRevision,"kind" to requestKind,"category" to requestCategory,"query" to requestQuery,"offset" to start,"limit" to 64)
                if(generation!=searchGeneration || revision!=requestRevision) return@launchSafe
                val array=data.getJSONArray("items")
                var localStart=channelRows.itemCount
                channelRows.append(List(array.length()) { i-> val c=array.getJSONObject(i); Row(c.getLong("id"),c.getString("name")) })
                if(channelRows.itemCount>512){
                    val drop=channelRows.itemCount-512
                    val layout=channels.layoutManager as LinearLayoutManager
                    val first=layout.findFirstVisibleItemPosition()
                    val top=layout.findViewByPosition(first)?.top?:0
                    channelRows.dropFirst(drop);offset+=drop;localStart-=drop
                    layout.scrollToPositionWithOffset((first-drop).coerceAtLeast(0),top)
                }
                if(focusNextPage) {
                    channels.scrollToPosition(localStart)
                    channels.doOnNextLayout { channels.findViewHolderForAdapterPosition(localStart)?.itemView?.requestFocus() }
                }
                if(selectNextPage) channelRows.rows.getOrNull(localStart)?.let { selectChannel(it.id) }
            } finally { if(generation==searchGeneration) { loadingPage=false; focusNextPage=false; selectNextPage=false;resumePreviousPage() } }
        }
    }
    private fun resumePreviousPage(){
        if(!pendingPrevious)return
        val focus=pendingPreviousFocus;val select=pendingPreviousSelect
        pendingPrevious=false;pendingPreviousFocus=false;pendingPreviousSelect=false
        loadPreviousPage(select,focus)
    }
    private fun loadPreviousPage(selectNew:Boolean=false,focusNew:Boolean=false){
        if(offset==0)return
        if(loadingPage){pendingPrevious=true;pendingPreviousFocus=pendingPreviousFocus||focusNew;pendingPreviousSelect=pendingPreviousSelect||selectNew;return}
        loadingPage=true
        val generation=searchGeneration;val requestRevision=revision
        val requestOffset=(offset-64).coerceAtLeast(0);val oldOffset=offset
        val requestKind=kind;val requestCategory=category;val requestQuery=query
        launchSafe{try{
            val data=core.call("search","revision" to requestRevision,"kind" to requestKind,"category" to requestCategory,"query" to requestQuery,"offset" to requestOffset,"limit" to (oldOffset-requestOffset))
            if(generation!=searchGeneration||revision!=requestRevision)return@launchSafe
            val array=data.getJSONArray("items")
            val layout=channels.layoutManager as LinearLayoutManager
            val first=layout.findFirstVisibleItemPosition();val top=layout.findViewByPosition(first)?.top?:0
            channelRows.prepend(List(array.length()){i->val c=array.getJSONObject(i);Row(c.getLong("id"),c.getString("name"))})
            offset=requestOffset
            if(channelRows.itemCount>512)channelRows.dropLast(channelRows.itemCount-512)
            layout.scrollToPositionWithOffset(first+array.length(),top)
            if(selectNew)channelRows.rows.getOrNull(array.length()-1)?.let{selectChannel(it.id)}
            if(focusNew&&array.length()>0){
                val index=array.length()-1;channels.scrollToPosition(index)
                channels.doOnNextLayout{channels.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()}
            }
        }finally{if(generation==searchGeneration){loadingPage=false;resumePreviousPage()}}}
    }
    private suspend fun refreshGuide() {
        val id = selectedId ?: return
        val result = core.call("epg", "revision" to revision, "id" to id, "now" to Instant.now().epochSecond)
        val rows = result.getJSONArray("programmes")
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        epg.text = if (rows.length() == 0) "Kein EPG für diesen Kanal" else (0 until rows.length()).joinToString("\n") {
            val p = rows.getJSONObject(it)
            "${formatter.format(Instant.ofEpochSecond(p.getLong("start")))}  ${p.getString("title")}"
        }
    }
    private fun markChannel(id: Long?) {
        val previous = channelRows.rows.indexOfFirst { it.id == selectedId }
        selectedId = id
        playerPane.hasSelection=id!=null
        details.refreshButton.isEnabled=id!=null && !busy
        val next = channelRows.rows.indexOfFirst { it.id == id }
        if (previous >= 0) channelRows.notifyItemChanged(previous)
        if (next >= 0 && next != previous) channelRows.notifyItemChanged(next)
    }
    private fun chooseSources() {
        if (sources.isEmpty()) { settingsDialog(); return }
        AlertDialog.Builder(this).setTitle("Gespeicherte Playlists")
            .setItems(sources.map { it.optString("name", "Playlist") }.toTypedArray()) { _, i -> launchSafe { importSource(sources[i], false) } }
            .setNeutralButton("Entfernen") { _, _ -> removeSource() }.setNegativeButton("Schließen", null).show()
    }
    private fun removeSource() {
        AlertDialog.Builder(this).setTitle("Quelle entfernen")
            .setItems(sources.map { it.optString("name", "Playlist") }.toTypedArray()) { _, i ->
                val removed=sources[i]
                val confirm=AlertDialog.Builder(this).setTitle("Quelle „${removed.optString("name","Playlist")}“ entfernen?")
                    .setMessage("Die gespeicherte Quelle, ihre Anmeldung und ihr lokaler Katalog werden entfernt. Du kannst sie anschließend erneut hinzufügen.")
                    .setNegativeButton("Abbrechen",null)
                    .setPositiveButton("Quelle entfernen") { _,_-> launchSafe {
                        refreshWork?.cancel()
                        val updated=sources.toMutableList().apply { remove(removed) }
                        withContext(Dispatchers.IO) { store.save(updated) }; sources=updated
                        if(activeSource?.let{loader.identity(it)}==loader.identity(removed)){
                            playerPane.cancelSeek();playback.stop();core.call("clear");activeSource=null;selectedId=null
                            getPreferences(MODE_PRIVATE).edit().remove("activeSourceId").apply()
                            revision=core.call("categories").getLong("revision");refreshCategories();search()
                            playerPane.hasSelection=false;details.refreshButton.isEnabled=false
                            libraryReady=false;chrome.visibility=View.INVISIBLE;startup.showEmpty()
                        }
                        loader.remove(removed)
                    } }.create()
                confirm.show()
                confirm.getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode=true;requestFocus() }
            }.setNegativeButton("Abbrechen", null).show()
    }
    private fun urlDialog(isEpg: Boolean) {
        form(if (isEpg) "XMLTV-Adresse" else "Playlist-Adresse", listOf("Name", "HTTP(S)-URL")) { values ->
            val source = JSONObject().put("kind", "url").put("name", values[0].ifBlank { if (isEpg) "EPG" else "Playlist" }).put("url", values[1])
            if (isEpg) importGuide(source) else importSource(source, true)
        }
    }
    private fun xtreamDialog() {
        form("Xtream Codes · Live, Filme und Serien", listOf("Name", "Server: https://anbieter.example", "Benutzername", "Passwort"), 3) { v ->
            val source = JSONObject().put("kind", "xtream").put("name", v[0].ifBlank { "Xtream" }).put("server", v[1]).put("username", v[2]).put("password", v[3]).put("vod", true)
            importSource(source, true)
        }
    }
    private fun epgDialog() {
        AlertDialog.Builder(this).setTitle("XMLTV-Programmführer")
            .setItems(arrayOf("Datei öffnen", "HTTP(S)-Adresse")) { _, i -> if (i == 0) pickDocument(true) else urlDialog(true) }.setNegativeButton("Abbrechen", null).show()
    }
    private fun form(title: String, hints: List<String>, passwordIndex: Int = -1, submit: suspend (List<String>) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val fields = hints.mapIndexed { i, hint -> EditText(this).apply {
            id=View.generateViewId();isSingleLine = true; textSize = 18f
            val fieldLabel=when { i==1->if(passwordIndex>=0)"Serveradresse" else "HTTP(S)-URL"; else->hint }
            this.hint=when(i){0->"z. B. Fernsehen";1->"https://anbieter.example";else->hint}
            contentDescription=fieldLabel
            inputType = if (i == passwordIndex) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else if(i==1) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
            layout.addView(label(fieldLabel,13f).apply { setTextColor(TvUi.muted) },LinearLayout.LayoutParams(-1,-2))
            (layout.getChildAt(layout.childCount-1) as TextView).labelFor=id
            layout.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin=dp(8) })
        } }
        val errorText=label("",13f).apply { setTextColor(TvUi.warning);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=View.GONE;setLineSpacing(0f,1.4f) }
        layout.addView(errorText)
        var work:Job?=null
        fun cancelForm() {
            work?.cancel()
            if(!libraryReady){chrome.visibility=View.INVISIBLE;startup.showEmpty()}
        }
        val dialog=AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Übernehmen",null).setNegativeButton("Abbrechen"){_,_->cancelForm()}.create()
        dialog.setOnCancelListener { cancelForm() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(work?.isActive==true || busy) return@setOnClickListener
            fields.forEach { it.error=null }
            val values=fields.mapIndexed { i,field->if(i==passwordIndex)field.text.toString() else field.text.toString().trim() }
            val invalid=when {
                values[1].toHttpUrlOrNull()==null->1
                passwordIndex>=0 && values[2].isBlank()->2
                passwordIndex>=0 && values[passwordIndex].isBlank()->passwordIndex
                else->-1
            }
            if(invalid>=0){fields[invalid].error=if(invalid==1)"Eine gültige HTTP(S)-Adresse eingeben." else "Dieses Feld ausfüllen.";fields[invalid].requestFocus();return@setOnClickListener}
            work=scope.launch {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                errorText.text="Quelle wird geprüft und geladen …";errorText.visibility=View.VISIBLE
                try { submit(values);dialog.dismiss() }
                catch(error:Exception){
                    if(error is kotlinx.coroutines.CancellationException)throw error
                    val detail=ImportErrors.describe(error);lastFailure=detail.json()
                    errorText.text=detail.message;status.text=detail.message
                    if(!libraryReady)startup.showError(detail.message)
                } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
            }
        }
    }
    private fun pickDocument(isEpg: Boolean) {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, if (isEpg) 101 else 100) }
        catch (_: Exception) { status.text = "Keine Dateiauswahl installiert. Bitte URL-Import verwenden." }
    }
    @Deprecated("Platform Activity document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        launchSafe {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val source = JSONObject().put("kind", "file").put("name", uri.lastPathSegment?.substringAfterLast('/') ?: "Datei").put("uri", uri.toString())
            if (requestCode == 101) importGuide(source) else importSource(source, true)
        }
    }
    private suspend fun importGuide(source: JSONObject) {
        loader.load(source, core, true)
        withContext(Dispatchers.IO) { epgStore.save(listOf(source)) }
        refreshGuide()
        status.text = "EPG importiert"
    }
    private fun setFullScreen(value: Boolean) {
        if(value && selectedId==null) return
        if(value==fullScreen) return
        playerPane.cancelSeek()
        if(value) restoreFocus = currentFocus?.takeUnless { playerPane.hasFocus() || channels.hasFocus() }
        fullScreen = value
        (playerPane.parent as ViewGroup).removeView(playerPane)
        if(value) {
            chrome.visibility=View.GONE
            stage.addView(playerPane,FrameLayout.LayoutParams(-1,-1))
        } else {
            playerSlot.addView(playerPane,FrameLayout.LayoutParams(-1,-1))
            chrome.visibility=View.VISIBLE
        }
        playerPane.setFullscreen(value,fillVideo)
        WindowInsetsControllerCompat(window,stage).hide(WindowInsetsCompat.Type.systemBars())
        if(!value) {
            if(restoreFocus?.takeIf { it.isAttachedToWindow && it.visibility==View.VISIBLE }?.requestFocus()!=true) returnToChannels()
        }
    }
    private fun returnToChannels() {
        playerPane.cancelSeek()
        if(fullScreen){setFullScreen(false);return}
        playerPane.hideControls()
        val wanted=libraryFocusId ?: selectedId
        val index=channelRows.rows.indexOfFirst { it.id==wanted }
        fun focusAt(position:Int) {
            val holder=channels.findViewHolderForAdapterPosition(position)
            if(holder!=null){holder.itemView.requestFocus();return}
            channels.doOnNextLayout { channels.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: channelSearch.requestFocus() }
            channels.scrollToPosition(position);channels.requestLayout()
        }
        if(index>=0){focusAt(index);return}
        if(wanted!=null && libraryFocusRevision==revision && total>0) {
            loadingPage=false;pendingPrevious=false;pendingPreviousFocus=false;pendingPreviousSelect=false
            val generation=++searchGeneration;val requestRevision=revision
            val targetOffset=(libraryFocusIndex.coerceIn(0,total-1)/64)*64
            val requestKind=kind;val requestCategory=category;val requestQuery=query
            launchSafe {
                val data=core.call("search","revision" to requestRevision,"kind" to requestKind,"category" to requestCategory,"query" to requestQuery,"offset" to targetOffset,"limit" to 64)
                if(revision!=requestRevision || searchGeneration!=generation)return@launchSafe
                val array=data.getJSONArray("items")
                offset=targetOffset;channelRows.replace(List(array.length()){i->val c=array.getJSONObject(i);Row(c.getLong("id"),c.getString("name"))})
                val restored=channelRows.rows.indexOfFirst { it.id==wanted }
                if(restored>=0)focusAt(restored) else channelSearch.requestFocus()
            }
        } else if(channelRows.itemCount>0)focusAt(0) else channelSearch.requestFocus()
    }
    private fun showProgrammeDetails() {
        val text=listOf(status.text.toString(),epg.text.toString()).filter { it.isNotBlank() }.joinToString("\n\n")
        val content=label(text,16f).apply { setPadding(dp(20),dp(12),dp(20),dp(12));setLineSpacing(0f,1.4f);isFocusable=true }
        AlertDialog.Builder(this).setTitle("Programm und Wiedergabe").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("Schließen",null).show()
    }
    private fun handleBack() {
        playerPane.cancelSeek()
        if(playerPane.controlsVisible && playerPane.hasFocus()) playerPane.hideControls()
        else if(fullScreen) setFullScreen(false)
        else if(playerPane.hasFocus())returnToChannels() else finish()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (::playback.isInitialized && event.action == KeyEvent.ACTION_DOWN && currentFocus !is EditText) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { playerAction("play"); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (playback.player?.playWhenReady != true) playerAction("play"); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (playback.player?.playWhenReady == true) playerAction("play"); return true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { playerAction("stop"); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { launchSafe { playback.seek(30_000) }; return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { launchSafe { playback.seek(-30_000) }; return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if(event.repeatCount>0)return true
                    playerPane.cancelSeek()
                    val direction = if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1
                    val index = channelRows.rows.indexOfFirst { it.id == selectedId }
                    if(direction==1 && index==channelRows.itemCount-1 && offset+channelRows.itemCount<total) { loadNextPage(selectNew=true); return true }
                    if(direction == -1 && index==0&&offset>0){loadPreviousPage(selectNew=true);return true}
                    channelRows.rows.getOrNull((index + direction + channelRows.rows.size).let { if (channelRows.rows.isEmpty()) 0 else it % channelRows.rows.size })?.let { row -> launchSafe { playback.select(revision, row.id); markChannel(row.id); refreshGuide() } }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun diagnosticsDialog(){launchSafe {
        val report=JSONObject().put("app_version",BuildConfig.VERSION_NAME).put("version_code",BuildConfig.VERSION_CODE)
            .put("build_id",BuildConfig.CORE_BUILD_ID)
            .put("core",core.call("info")).put("import",loader.lastReport).put("last_error",lastFailure?:JSONObject.NULL)
            .put("playback_error",playback.lastError?:JSONObject.NULL)
        if(android.os.Build.VERSION.SDK_INT>=30){
            val manager=getSystemService(android.app.ActivityManager::class.java)
            val exit=manager.getHistoricalProcessExitReasons(packageName,0,1).firstOrNull()
            if(exit!=null)report.put("previous_exit",JSONObject().put("reason",exit.reason).put("time",exit.timestamp)
                .put("meaning",when(exit.reason){android.app.ApplicationExitInfo.REASON_LOW_MEMORY->"Android hat die App wegen Arbeitsspeicherdruck beendet";android.app.ApplicationExitInfo.REASON_CRASH_NATIVE->"Nativer Absturz";android.app.ApplicationExitInfo.REASON_ANR->"Die App reagierte nicht";android.app.ApplicationExitInfo.REASON_CRASH->"App-Absturz";else->"Anderer oder unbekannter Beendigungsgrund"}))
        }else report.put("previous_exit","Auf dieser Android-Version nicht verfügbar")
        val text=report.toString(2)
        val content=TextView(this@MainActivity).apply{textSize=13f;setTextIsSelectable(true);setPadding(dp(20),dp(10),dp(20),dp(10));this.text=text}
        AlertDialog.Builder(this@MainActivity).setTitle("Diagnosebericht").setView(android.widget.ScrollView(this@MainActivity).apply{addView(content)})
            .setPositiveButton("Kopieren"){_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("IPTV Diagnose",text))}
            .setNegativeButton("Schließen",null).show()
    }}
    private fun searchField(hint: String, changed: () -> Unit) = EditText(this).apply {
        id=View.generateViewId(); this.hint = hint; contentDescription = hint; isSingleLine = true; textSize = 12f
        minHeight=dp(34)
        setTextColor(Color.WHITE); setHintTextColor(TvUi.muted); setPadding(dp(8),dp(6),dp(8),dp(6))
        background=TvUi.focusBackground(this@MainActivity)
        inputType = InputType.TYPE_CLASS_TEXT
        filters = arrayOf(android.text.InputFilter.LengthFilter(256))
        doAfterTextChanged { changed() }
    }
    private fun label(value: String, size: Float) = TvUi.text(this,value,size)
    private fun button(value: String, click: () -> Unit) = TvUi.button(this,value,click)
    private fun list(adapter: Rows) = RecyclerView(this).apply { id = View.generateViewId(); layoutManager = LinearLayoutManager(this@MainActivity); this.adapter = adapter; setHasFixedSize(true); itemAnimator = null; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)) }
    private data class Row(val id: Long, val title: String)
    private data class CategoryItem(val id: Long, val name: String, val kind: String, val count: Int)
    private inner class Rows(private val channelList: Boolean = false, val select: (Row) -> Unit) : RecyclerView.Adapter<Rows.Holder>() {
        init { setHasStableIds(true) }
        val rows = ArrayList<Row>()
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
        private fun updateTextMotion(text:TextView,focused:Boolean) {
            val animate=TvUi.animationsEnabled(this@MainActivity)
            text.isSingleLine=animate || !focused
            text.maxLines=if(!animate && focused)Int.MAX_VALUE else 1
            text.ellipsize=if(animate)TextUtils.TruncateAt.MARQUEE else if(focused)null else TextUtils.TruncateAt.END
            text.isSelected=focused && animate
        }
        override fun getItemId(position: Int)=rows[position].id
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(label("",13f).apply {
            isClickable = true; isSingleLine=true; ellipsize=TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit=-1
            setPadding(dp(9),dp(5),dp(9),dp(5));minHeight=dp(36)
            TvUi.focus(this,1.015f) { focused -> isSelected=focused }
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }
            accessibilityDelegate=object:View.AccessibilityDelegate(){
                override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){
                    super.onInitializeAccessibilityNodeInfo(host,info)
                    info.isSelected=host.isActivated
                    if(android.os.Build.VERSION.SDK_INT>=30 && host.isActivated) info.stateDescription=if(channelList)"Wird wiedergegeben" else "Ausgewählte Kategorie"
                }
            }
        })
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            val active = if (channelList) row.id == selectedId && ::playback.isInitialized && playback.player!=null else row.id == (category ?: -1L)
            holder.text.isActivated = active
            TvUi.focus(holder.text,1f) { focused ->
                updateTextMotion(holder.text,focused)
                if(focused && channelList && holder.bindingAdapterPosition>=0){
                    libraryFocusId=row.id;libraryFocusIndex=offset+holder.bindingAdapterPosition;libraryFocusRevision=revision
                }
            }
            updateTextMotion(holder.text,holder.text.hasFocus())
            holder.text.text = if (active) "▶ ${row.title}" else row.title
            holder.text.contentDescription = row.title
            holder.text.setOnClickListener { select(row) }
            holder.text.setOnKeyListener { _,key,event ->
                if(key in setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)){
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)select(row)
                    return@setOnKeyListener true
                }
                if(channelList && key==KeyEvent.KEYCODE_DPAD_RIGHT){
                    if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0){
                        libraryFocusId=row.id;libraryFocusIndex=offset+holder.bindingAdapterPosition;libraryFocusRevision=revision
                        playerPane.hideControls();video.requestFocus()
                    };return@setOnKeyListener true
                }
                if(channelList && key==KeyEvent.KEYCODE_DPAD_UP && event.action==KeyEvent.ACTION_DOWN && holder.bindingAdapterPosition==0 && offset>0){loadPreviousPage(focusNew=true);return@setOnKeyListener true}
                if(channelList && key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_DOWN && holder.bindingAdapterPosition==itemCount-1 && offset+itemCount<total) { loadNextPage(focusNew=true); true } else false
            }
        }
        fun replace(value: List<Row>) { rows.clear();rows.addAll(value);notifyDataSetChanged() }
        fun append(value: List<Row>) { val start=rows.size;rows.addAll(value);notifyItemRangeInserted(start,value.size) }
        fun prepend(value:List<Row>){rows.addAll(0,value);notifyItemRangeInserted(0,value.size)}
        fun dropFirst(count:Int){rows.subList(0,count).clear();notifyItemRangeRemoved(0,count)}
        fun dropLast(count:Int){val start=rows.size-count;rows.subList(start,rows.size).clear();notifyItemRangeRemoved(start,count)}
    }
}

