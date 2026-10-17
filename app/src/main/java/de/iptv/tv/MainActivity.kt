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
import android.widget.ImageView
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.GridLayoutManager
import org.json.JSONArray
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
    private lateinit var channels: TvList
    private lateinit var categories: TvList
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
    private lateinit var artwork:ArtworkLoader
    private var prefetchWork:Job?=null
    private var artworkForward=true
    private var lastScrollAt=0L
    private var lastPrefetchAt=0L
    private lateinit var browserColumn:LinearLayout
    private lateinit var mediaColumn:LinearLayout
    private lateinit var seriesHeader:LinearLayout
    private lateinit var seriesTitle:TextView
    private lateinit var seriesPoster:ImageView
    private lateinit var seasonButton:Button
    private lateinit var movieHeader:LinearLayout
    private lateinit var movieScroll:ScrollView
    private lateinit var movieTitle:TextView
    private lateinit var moviePoster:ImageView
    private lateinit var moviePlay:Button
    private lateinit var movieRestart:Button
    private lateinit var movieFavorite:Button
    private lateinit var movieProgress:TextView
    private lateinit var seriesResume:Button
    private lateinit var seriesFavorite:Button
    /** History, favourites and progress; independent of catalog generations. */
    private val userLibrary=UserLibrary()
    private lateinit var libraryStore:UserLibraryStore
    private lateinit var tracker:WatchTracker
    private var userLibraryLoaded=false
    private var activeSourceId:String?=null
    /** "recent" or "favorites" replaces the provider category; null lists the catalog. */
    private var special:String?=null
    private var openedSeriesKey:String?=null
    private var openedSeriesName=""
    private var pendingSpecialFocus:Int?=null
    private var libraryRefreshWork:Job?=null
    private var shownCounts=""
    private var openedMovie:Row?=null
    private var openedSeries:Long?=null
    private var chosenSeason:Long?=null
    private var unknownSeason=false
    private var seriesSeasons=JSONArray()
    private var seasonOffset=0
    private var seasonTotal=0
    private var seriesWork:Job?=null
    private data class BrowseSnapshot(val total:Int,val focus:Long?,val index:Int)
    private var browseSnapshot:BrowseSnapshot?=null
    /** [ids] is the fixed order of a history/favourites list; channel +/- steps through it. */
    private data class PlayContext(val kind:String,val category:Long?,val query:String,val show:Long?,val season:Long?,val unknown:Boolean,val ids:List<Long>?=null)
    private var playContext:PlayContext?=null
    private val gridMode get()=kind!="live" && openedSeries==null && openedMovie==null
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
    private val channelRows = Rows(true) { item -> when{
        !item.available->status.text=getString(R.string.item_unavailable,item.title)
        item.series->openSeries(item.id);kind=="movies"->openMovie(item);else->selectChannel(item.id)} }
    private val categoryRows = Rows { item ->
        closeSeries(false,false)
        special = when(item.id){ RECENT_ROW->"recent"; FAVORITES_ROW->"favorites"; else->null }
        category = item.id.takeIf { it >= 0 }; offset = 0
        renderCategories(); search()
    }
    private companion object {
        const val RECENT_ROW=-2L
        const val FAVORITES_ROW=-3L
        /** Remembered entries missing from the current list get ids below this value. */
        const val UNAVAILABLE_BASE=-1000L
        const val STATUS="status"
        /** Category name the Rust core assigns to entries without a group. */
        const val UNCATEGORIZED="Ohne Kategorie"
        /** Key repeat scrolls every 50 ms; scroll steps closer than this mean a held D-pad. */
        const val FAST_SCROLL_MS=160L
    }

    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        artwork=ArtworkLoader(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        loader = SourceLoader(applicationContext); store = SourceStore(this); epgStore = SourceStore(this, "epg.enc")
        try { core = Core() } catch (_: Throwable) {
            setContentView(TextView(this).apply { setText(R.string.core_missing); textSize = 24f; setPadding(48,48,48,48) }); return
        }
        buildUi()
        loader.progress = { res -> scope.launch { if(busy) { val message=getString(res); status.text=message; startup.updateProgress(message) } } }
        playback = PlaybackController(this, core, scope, video, { status.text = it }, { stats ->
            playerPane.hasSelection = selectedId != null
            playerPane.update(stats)
            details.update(stats)
            details.refreshButton.isEnabled = selectedId != null && reconnectWork?.isActive != true && !busy
        })
        playback.bufferProfile = PlaybackBufferProfile.fromKey(getPreferences(MODE_PRIVATE).getString("liveBufferProfile", null))
        libraryStore = UserLibraryStore(applicationContext)
        tracker = WatchTracker(userLibrary)
        userLibrary.historyLimit = getPreferences(MODE_PRIVATE).getInt("historyLimit", UserLibrary.DEFAULT_LIMIT)
        playback.onSample = { tracker.onSample(it) }
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        startLibrary()
    }
    private fun startLibrary() {
        if (startupWork?.isActive == true) return
        chrome.visibility = View.INVISIBLE
        startup.showLoading(getString(R.string.loading_saved_source))
        startupWork = launchSafe {
            if (!userLibraryLoaded) {
                // Before the catalog opens, so the first list already shows stars and progress.
                libraryStore.load(userLibrary); userLibraryLoaded = true
                userLibrary.listener = { libraryStore.schedule(userLibrary); onLibraryChanged() }
            }
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
        toolbar.addView(label(getString(R.string.app_name).replace(" ","\n"),14f).apply { letterSpacing = 0.06f; setTypeface(typeface,android.graphics.Typeface.BOLD);contentDescription=getString(R.string.app_name) }, LinearLayout.LayoutParams(dp(if(largeText)190 else 120),-2))
        val tabs=LinearLayout(this)
        for ((key,title) in listOf("live" to R.string.tab_live,"movies" to R.string.tab_movies,"series" to R.string.tab_series,"unclassified" to R.string.tab_unclassified)) {
            val tab = button(getString(title)) { switchKind(key) }
            kindButtons[key] = tab
            tabs.addView(tab,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(6) })
            if(key=="unclassified")tab.visibility=View.GONE
        }
        toolbar.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(tabs)},LinearLayout.LayoutParams(0,-2,1f))
        settingsButton = TvIconButton(this,"settings",getString(R.string.settings)) { settingsDialog() }.apply { id = View.generateViewId() }
        toolbar.addView(settingsButton,LinearLayout.LayoutParams(dp(36),dp(36)))
        chrome.addView(toolbar,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        val body = LinearLayout(this)
        sidebar = LinearLayout(this)
        categories = list(categoryRows).apply { contentDescription = getString(R.string.categories_list) }
        channels = list(channelRows).apply { contentDescription = getString(R.string.entries_list) }
        val categoryColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5),dp(5),dp(5),0); background = TvUi.surface(TvUi.panel,dp(7).toFloat()) }
        categoryColumn.addView(label(getString(R.string.categories),12f).apply { setTextColor(TvUi.muted); setPadding(dp(4),dp(4),dp(4),dp(6)) },LinearLayout.LayoutParams(-1,-2))
        categorySearch = searchField(getString(R.string.search_categories)) { renderCategories() }
        categoryColumn.addView(categorySearch,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        categoryEmpty = label(getString(R.string.categories_none),12f).apply { visibility=View.GONE; setTextColor(TvUi.muted) }
        categoryColumn.addView(categoryEmpty)
        categoryColumn.addView(categories,LinearLayout.LayoutParams(-1,0,1f))
        browserColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5),dp(5),dp(5),0); background = TvUi.surface(TvUi.panel,dp(7).toFloat()) }
        channelHeading = label(getString(R.string.category_all),12f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END }
        channelHeading.setPadding(dp(4),dp(4),dp(4),dp(6))
        browserColumn.addView(channelHeading,LinearLayout.LayoutParams(-1,-2))
        channelSearch = searchField(getString(R.string.search_channels)) {
            if(openedSeries!=null||openedMovie!=null)closeSeries(false,false)
            query=channelSearch.text.toString(); offset=0; searchGeneration++
            searchWork?.cancel(); searchWork=scope.launch { delay(200); search() }
        }
        browserColumn.addView(channelSearch,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        channelEmpty = label(getString(R.string.entries_none),12f).apply { visibility=View.GONE; setTextColor(TvUi.muted) }
        browserColumn.addView(channelEmpty)
        clearFiltersButton = button(getString(R.string.reset_search)) {
            closeSeries(false,false)
            category=null; special=null; query=""; categorySearch.setText(""); channelSearch.setText("")
            searchWork?.cancel(); renderCategories(); search(); channelSearch.requestFocus()
        }.apply { visibility=View.GONE }
        browserColumn.addView(clearFiltersButton,LinearLayout.LayoutParams(-1,-2))
        seriesHeader=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        seriesHeader.addView(button(getString(R.string.back_series_library)){closeSeries(true,true)},LinearLayout.LayoutParams(-1,-2))
        seriesTitle=label("",15f).apply{setPadding(dp(6),dp(8),dp(6),dp(8));maxLines=3}
        seriesPoster=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        seriesHeader.addView(seriesPoster,LinearLayout.LayoutParams(-1,dp(56)))
        seriesHeader.addView(seriesTitle)
        seriesResume=button(getString(R.string.resume)){resumeSeries()}.apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END;visibility=View.GONE}
        seriesHeader.addView(seriesResume,LinearLayout.LayoutParams(-1,-2))
        // Season choice and the show's star share one row; episodes keep their vertical space.
        val seasonRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        seasonButton=button(getString(R.string.choose_season)){chooseSeason()}
        seasonRow.addView(seasonButton,LinearLayout.LayoutParams(0,-2,1f))
        seriesFavorite=button("☆"){openedSeriesKey?.let{toggleFavorite(LibrarySection.SERIES,it,openedSeriesName)}}.apply{textSize=16f}
        seriesFavorite.minWidth=dp(44);seriesFavorite.minimumWidth=dp(44)
        seasonRow.addView(seriesFavorite,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(4)})
        seriesHeader.addView(seasonRow,LinearLayout.LayoutParams(-1,-2))
        browserColumn.addView(seriesHeader,LinearLayout.LayoutParams(-1,-2))
        movieHeader=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        movieHeader.addView(button(getString(R.string.back_movie_library)){closeSeries(true,true)},LinearLayout.LayoutParams(-1,-2))
        moviePoster=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        movieHeader.addView(moviePoster,LinearLayout.LayoutParams(-1,dp(100)))
        movieTitle=label("",15f).apply{setPadding(dp(6),dp(8),dp(6),dp(8))}
        movieHeader.addView(movieTitle)
        movieProgress=label("",12f).apply{setTextColor(TvUi.muted);setPadding(dp(6),0,dp(6),dp(6));visibility=View.GONE}
        movieHeader.addView(movieProgress)
        // Saved progress resumes automatically; "from the start" ignores it.
        moviePlay=button(getString(R.string.play)){openedMovie?.let{selectChannel(it.id)}}
        movieHeader.addView(moviePlay,LinearLayout.LayoutParams(-1,-2))
        movieRestart=button(getString(R.string.play_from_start)){openedMovie?.let{selectChannel(it.id,fromStart=true)}}.apply{visibility=View.GONE}
        movieHeader.addView(movieRestart,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        movieFavorite=button("☆ "+getString(R.string.favorite_add)){openedMovie?.let{row->row.key?.let{toggleFavorite(LibrarySection.MOVIES,it,row.title)}}}
        movieHeader.addView(movieFavorite,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        movieScroll=ScrollView(this).apply{isFillViewport=false;visibility=View.GONE;addView(movieHeader)}
        browserColumn.addView(movieScroll,LinearLayout.LayoutParams(-1,0,1f))
        browserColumn.addView(channels,LinearLayout.LayoutParams(-1,0,1f))
        resultCount=label(resources.getQuantityString(R.plurals.entries_count,0,0),10f).apply { setTextColor(TvUi.muted) }
        browserColumn.addView(resultCount,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(4) })
        sidebar.addView(categoryColumn,LinearLayout.LayoutParams(dp(if(largeText)260 else 216),-1))
        sidebar.addView(browserColumn,LinearLayout.LayoutParams(dp(if(largeText)260 else 216),-1).apply { marginStart=dp(7); marginEnd=dp(12) })
        body.addView(sidebar,LinearLayout.LayoutParams(-2,-1))
        mediaColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        playerSlot=FrameLayout(this)
        playerPane=PlayerPane(this,scope,::playerAction) { target -> playback.seekTo(target) }
        video=playerPane.video
        playerSlot.addView(playerPane,FrameLayout.LayoutParams(-1,-1))
        mediaColumn.addView(playerSlot,LinearLayout.LayoutParams(-1,0,1f))
        status=label(getString(R.string.choose_channel),13f).apply {
            maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(6),dp(5),dp(6),dp(5))
            TvUi.focus(this,1f); setOnClickListener { showProgrammeDetails() }
        }
        mediaColumn.addView(status,LinearLayout.LayoutParams(-1,-2))
        epg=label(getString(R.string.epg_hint),12f).apply { maxLines=2; setTextColor(TvUi.muted); ellipsize=TextUtils.TruncateAt.END }
        epg.setPadding(dp(6),dp(4),dp(6),dp(4))
        TvUi.focus(epg,1f); epg.setOnClickListener { showProgrammeDetails() }
        mediaColumn.addView(epg,LinearLayout.LayoutParams(-1,-2))
        status.doAfterTextChanged { playerPane.updateInformation(status.text.toString(),epg.text.toString()) }
        epg.doAfterTextChanged { playerPane.updateInformation(status.text.toString(),epg.text.toString()) }
        body.addView(mediaColumn,LinearLayout.LayoutParams(0,-1,1f))
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
        // A span set after layout (layout-change listener) was dropped, leaving two giant cards.
        channels.onWidthChanged={updateGridColumns()}
        // Keep about three poster rows bound off screen; scrolling back then needs no rebind.
        channels.setItemViewCacheSize(18)
        channels.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView,dx: Int,dy: Int) {
                val layout=view.layoutManager as LinearLayoutManager
                if (layout.findLastVisibleItemPosition()>=channelRows.itemCount-12) loadNextPage()
                if(layout.findFirstVisibleItemPosition()<=12&&offset>0)loadPreviousPage()
                if(dy!=0)artworkForward=dy>0
                schedulePrefetch(dy!=0)
            }
        })
    }
    /** A held D-pad gets a one-row window that keeps up with the focus; a pause widens it, and an
     * idle grid warms the next rows of the loaded page into the disk cache at the lowest priority. */
    private fun schedulePrefetch(moved:Boolean){
        val now=android.os.SystemClock.uptimeMillis()
        val fast=moved&&now-lastScrollAt<FAST_SCROLL_MS
        if(moved)lastScrollAt=now
        if(!fast||now-lastPrefetchAt>=100){lastPrefetchAt=now;prefetchArtwork(if(fast)1 else 3,if(fast)0 else 1)}
        prefetchWork?.cancel()
        prefetchWork=scope.launch{delay(FAST_SCROLL_MS);prefetchArtwork(3,1);delay(1_200);prefetchArtwork(8,2)}
    }
    private fun prefetchArtwork(ahead:Int,behind:Int){
        val layout=channels.layoutManager as? GridLayoutManager
        if(layout==null||!gridMode||!channels.isShown){artwork.prefetch(emptyList());return}
        val columns=layout.spanCount;val rev=revision
        val positions=ArtworkPolicy.prefetchWindow(layout.findFirstVisibleItemPosition(),layout.findLastVisibleItemPosition(),columns,channelRows.itemCount,artworkForward,ahead,behind)
        // Two rows are decoded into memory; further rows are only downloaded into the disk cache.
        artwork.prefetch(positions.map{channelRows.rows[it].let{row->artworkKey(rev,row.id,row.series) to artworkAddress(rev,row.id,row.series)}},columns*2)
    }
    private fun artworkKey(rev:Long,id:Long,series:Boolean)="$rev:$id:$series"
    private fun artworkAddress(rev:Long,id:Long,series:Boolean):suspend ()->String?={
        core.call("artwork","revision" to rev,"id" to id,"series" to series).optString("url").takeIf{it.isNotEmpty()&&it!="null"}
    }
    private fun settingsDialog() {
        val choices=listOf(R.string.settings_sources,R.string.settings_add_file,R.string.settings_add_url,R.string.settings_xtream,R.string.settings_epg,R.string.settings_picture,
            R.string.settings_buffer,R.string.settings_history,R.string.settings_language,R.string.settings_diagnostics,R.string.app_info).map { getString(it) }
        val dialog=AlertDialog.Builder(this).create()
        val menu=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(20),dp(18),dp(16))
            background=TvUi.surface(TvUi.panel,dp(10).toFloat())
        }
        menu.addView(label(getString(R.string.settings),20f),LinearLayout.LayoutParams(-1,-2))
        menu.addView(label(getString(R.string.settings_section),12f).apply { setTextColor(TvUi.muted); setPadding(0,dp(10),0,dp(10)) },LinearLayout.LayoutParams(-1,-2))
        val actions=choices.mapIndexed { index,choice ->
            button(choice) {
                    dialog.dismiss()
                    when(index) { 0->chooseSources(); 1->pickDocument(false); 2->urlDialog(false); 3->xtreamDialog(); 4->epgDialog(); 5->pictureDialog(); 6->bufferDialog(); 7->historyDialog(); 8->languageDialog(); 9->diagnosticsDialog(); 10->appInfoDialog() }
            }.apply {
                menu.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(4) })
            }
        }
        menu.addView(label(getString(R.string.settings_back_hint),11f).apply { setTextColor(TvUi.muted); setPadding(0,dp(8),0,0) },LinearLayout.LayoutParams(-1,-2))
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
        AlertDialog.Builder(this).setTitle(R.string.picture_title)
            .setSingleChoiceItems(arrayOf(getString(R.string.picture_fill),getString(R.string.picture_fit)),if(fillVideo)0 else 1) { dialog,index ->
                fillVideo=index==0
                getPreferences(MODE_PRIVATE).edit().putBoolean("fillVideo",fillVideo).apply()
                dialog.dismiss()
            }.setNegativeButton(R.string.close,null).show()
    }
    private fun appInfoDialog(){
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(20))}
        content.addView(ImageView(this).apply{
            setImageResource(R.drawable.rustream_logo);scaleType=ImageView.ScaleType.FIT_CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(-1,dp(120)))
        content.addView(label(getString(R.string.app_description),16f).apply{setLineSpacing(0f,1.25f);setPadding(0,dp(16),0,dp(12))})
        content.addView(label(getString(R.string.app_version,BuildConfig.VERSION_NAME),12f).apply{setTextColor(TvUi.muted)})
        content.addView(label(getString(R.string.app_developer),12f).apply{setTextColor(TvUi.muted);setPadding(0,dp(6),0,0)})
        AlertDialog.Builder(this).setTitle(R.string.app_name).setView(ScrollView(this).apply{addView(content)})
            .setPositiveButton(R.string.close){_,_->settingsButton.requestFocus()}.show()
    }
    private fun bufferDialog() {
        val profiles = PlaybackBufferProfile.entries
        AlertDialog.Builder(this).setTitle(R.string.buffer_title)
            .setSingleChoiceItems(profiles.map { getString(it.label) }.toTypedArray(), profiles.indexOf(playback.bufferProfile)) { dialog, index ->
                playback.bufferProfile = profiles[index]
                getPreferences(MODE_PRIVATE).edit().putString("liveBufferProfile", profiles[index].key).apply()
                dialog.dismiss()
            }.setNegativeButton(R.string.close, null).show()
    }
    private fun historyDialog() {
        val choices=UserLibrary.LIMIT_CHOICES
        AlertDialog.Builder(this).setTitle(R.string.history_title)
            .setSingleChoiceItems(choices.map { resources.getQuantityString(R.plurals.entries_count,it,it) }.toTypedArray(), choices.indexOf(userLibrary.historyLimit)) { dialog, index ->
                getPreferences(MODE_PRIVATE).edit().putInt("historyLimit", choices[index]).apply()
                // Lowering trims immediately; progress and favourites stay untouched.
                userLibrary.historyLimit = choices[index]
                dialog.dismiss()
            }.setNegativeButton(R.string.close, null).show()
    }
    /** A new language rebuilds the activity: every view, dialog and status text is created in it.
     * Progress is flushed in onStop and the catalog reopens from its local copy. */
    private fun languageDialog() {
        val choices=AppLanguage.choices
        val current=AppLanguage.current(this)
        AlertDialog.Builder(this).setTitle(R.string.language_title)
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), choices.indexOfFirst { it.first==current }) { dialog, index ->
                dialog.dismiss()
                val chosen=choices[index].first
                if(chosen!=current){ AppLanguage.save(this,chosen); recreate() }
            }.setNegativeButton(R.string.close, null).show()
    }
    /** [fromStart] ignores saved progress; otherwise VOD continues where it stopped. */
    private fun selectChannel(id: Long, preserveContext:Boolean=false, fromStart:Boolean=false) {
        if(selectionWork?.isActive==true || busy) return
        if (id==selectedId && playback.player!=null && playback.phase !in setOf("idle","finished","error") && !fromStart) {
            playerPane.cancelSeek(); setFullScreen(true); return
        }
        playerPane.cancelSeek()
        val listed=if(special!=null&&openedSeries==null&&kind!="series")channelRows.rows.filter{it.available}.map{it.id} else null
        val requestContext=PlayContext(kind,category,query,openedSeries,chosenSeason,unknownSeason,listed)
        val requestRevision=revision
        selectionWork=launchSafe {
        val entry=core.call("entry","revision" to requestRevision,"id" to id)
        if(requestRevision!=revision)return@launchSafe
        val seriesId=entry.optString("series_id").takeIf{it.isNotEmpty()&&it!="null"}
        if(seriesId!=null){openSeries(id)}else{
            if(!preserveContext)playContext=requestContext
            startPlayback(requestRevision,id,entry,fromStart,(playContext?:requestContext).kind)
            markChannel(id);updateLibraryLayout();refreshGuide()
        }
    } }
    /** Selects [id] and starts tracking it for history and progress. */
    private suspend fun startPlayback(rev:Long,id:Long,entry:JSONObject,fromStart:Boolean,contextKind:String){
        val key=entry.optString("key").takeIf{it.isNotEmpty()&&it!="null"}
        val live=entry.optBoolean("live")
        val source=activeSourceId
        val resume=if(fromStart||live||key==null||source==null)null else userLibrary.progress(source,key)?.resumeSec
        val token=playback.select(rev,id,resume)
        beginTracking(token,rev,id,key,entry.optString("name"),live,contextKind)
    }
    private suspend fun beginTracking(token:Long,rev:Long,id:Long,key:String?,name:String,live:Boolean,contextKind:String){
        val source=activeSourceId
        val section=LibrarySection.of(contextKind)
        if(key==null||source==null||section==null||(section==LibrarySection.LIVE)!=live){tracker.clear();updatePlayerFavorite();return}
        val info=resolveKeys(rev,listOf(key)).first[key]
        val showKey=info?.optString("show_key")?.takeIf{it.isNotEmpty()&&it!="null"}
        // An episode is remembered under its show; a series section entry without a show is not tracked.
        if(section==LibrarySection.SERIES&&showKey==null){tracker.clear();updatePlayerFavorite();return}
        val logo=if(section==LibrarySection.LIVE)try{core.call("artwork","revision" to rev,"id" to id,"series" to false).optString("url").takeIf{it.isNotEmpty()&&it!="null"}}catch(error:CoreException){null} else null
        if(rev!=revision)return
        tracker.begin(NowPlaying(token,source,section,key,name,logo,showKey,
            info?.optString("show_name")?.takeIf{showKey!=null&&it.isNotEmpty()&&it!="null"},
            info?.takeIf{!it.isNull("season")}?.optLong("season"),info?.takeIf{!it.isNull("episode")}?.optLong("episode")))
        updatePlayerFavorite()
    }
    /** Maps stable keys to the current revision in requests that stay below the JNI size limit. */
    private suspend fun resolveKeys(rev:Long,keys:List<String>):Pair<Map<String,JSONObject>,Boolean>{
        val found=HashMap<String,JSONObject>()
        var keyed=true
        val batch=mutableListOf<String>();var bytes=0
        suspend fun send(){
            if(batch.isEmpty())return
            val data=core.call("resolve","revision" to rev,"keys" to JSONArray(batch))
            keyed=keyed&&data.optBoolean("keyed",false)
            val items=data.getJSONArray("items")
            for(i in 0 until items.length()){val item=items.getJSONObject(i);found[item.getString("key")]=item}
            batch.clear();bytes=0
        }
        for(key in keys.distinct()){
            if(key.length>1024)continue
            if(batch.size>=64||bytes+key.length>8_000)send()
            batch+=key;bytes+=key.length+4
        }
        send()
        return found to keyed
    }
    private fun updatePlayerFavorite(){
        if(!::playerPane.isInitialized)return
        val item=tracker.current;val source=activeSourceId
        playerPane.setFavorite(if(item==null||source==null||item.source!=source)null else userLibrary.isFavorite(source,item.section,item.libraryKey))
    }
    private fun toggleFavorite(section:LibrarySection,key:String,name:String){
        val source=activeSourceId ?: return
        val index=channelRows.rows.indexOfFirst{it.key==key}
        userLibrary.toggleFavorite(source,section,key,name)
        // In the favourites list the removed entry disappears; focus moves to its successor.
        if(special=="favorites"&&openedSeries==null&&openedMovie==null&&index>=0&&!userLibrary.isFavorite(source,section,key)){pendingSpecialFocus=index;search()}
    }
    private fun removeRecent(section:LibrarySection,row:Row){
        val source=activeSourceId ?: return
        val key=row.key ?: return
        val index=channelRows.rows.indexOf(row)
        userLibrary.removeRecent(source,section,key)
        if(special=="recent"){pendingSpecialFocus=index.coerceAtLeast(0);search()}
    }
    /** Coalesces bursts (start + favourite + progress) into one refresh of the visible state. */
    private fun onLibraryChanged(){
        if(!::channels.isInitialized)return
        libraryRefreshWork?.cancel()
        libraryRefreshWork=scope.launch {
            delay(120)
            if(channelRows.itemCount>0)channelRows.notifyItemRangeChanged(0,channelRows.itemCount,STATUS)
            val source=activeSourceId;val section=LibrarySection.of(kind)
            val counts=if(source==null||section==null)"" else "${userLibrary.recentCount(source,section)}/${userLibrary.favoriteCount(source,section)}/${userLibrary.historyLimit}"
            if(counts!=shownCounts){
                renderCategories()
                // A changed history list re-sorts only while the user is not inside it.
                if(special!=null&&openedSeries==null&&openedMovie==null&&!channels.hasFocus())search()
            }
            updateMovieActions();updateSeriesActions();updatePlayerFavorite()
        }
    }
    private fun updateMovieActions(){
        val row=openedMovie ?: return
        val source=activeSourceId
        val progress=if(source!=null&&row.key!=null)userLibrary.progress(source,row.key) else null
        val playing=row.id==selectedId&&playback.player!=null
        val resume=progress?.resumeSec
        moviePlay.text=when{playing->getString(R.string.open_playback);resume!=null->getString(R.string.resume_at,TvUi.clock(resume));progress?.done==true->getString(R.string.play_again);else->getString(R.string.play)}
        movieRestart.visibility=if(!playing&&resume!=null)View.VISIBLE else View.GONE
        movieProgress.text=when{
            progress==null->""
            progress.done->getString(R.string.watched_done)
            resume==null->""
            else->progress.durationSec?.let{getString(R.string.watched_of,TvUi.clock(resume),TvUi.clock(it))} ?: getString(R.string.watched_until,TvUi.clock(resume))
        }
        movieProgress.visibility=if(movieProgress.text.isEmpty())View.GONE else View.VISIBLE
        val favorite=source!=null&&row.key!=null&&userLibrary.isFavorite(source,LibrarySection.MOVIES,row.key)
        movieFavorite.visibility=if(source!=null&&row.key!=null)View.VISIBLE else View.GONE
        movieFavorite.text=if(favorite)"★ "+getString(R.string.favorite_remove) else "☆ "+getString(R.string.favorite_add)
        movieFavorite.isActivated=true;movieFavorite.isSelected=favorite
    }
    private fun updateSeriesActions(){
        if(openedSeries==null)return
        val source=activeSourceId;val key=openedSeriesKey
        val last=if(source!=null&&key!=null)userLibrary.lastEpisode(source,key) else null
        val episodeKey=last?.episodeKey
        if(source==null||episodeKey==null){seriesResume.visibility=View.GONE}else{
            val progress=userLibrary.progress(source,episodeKey)
            val label=last.episodeLabel ?: getString(R.string.last_episode)
            seriesResume.text=when{
                progress?.done==true->getString(R.string.series_rewatch,label)
                progress?.resumeSec!=null->getString(R.string.series_resume_at,label,TvUi.clock(progress.resumeSec!!))
                else->getString(R.string.series_resume,label)
            }+(last.episodeTitle?.let{"\n$it"}?:"")
            seriesResume.contentDescription=seriesResume.text.toString().replace("\n"," · ")
            seriesResume.visibility=View.VISIBLE
        }
        val favorite=source!=null&&key!=null&&userLibrary.isFavorite(source,LibrarySection.SERIES,key)
        seriesFavorite.visibility=if(source!=null&&key!=null)View.VISIBLE else View.GONE
        seriesFavorite.text=if(favorite)"★" else "☆"
        seriesFavorite.contentDescription=getString(if(favorite)R.string.series_favorite_remove else R.string.series_favorite_add)
        seriesFavorite.setTextColor(if(favorite)TvUi.accent else Color.WHITE)
    }
    private fun resumeSeries(){
        val source=activeSourceId ?: return
        val key=openedSeriesKey ?: return
        val episodeKey=userLibrary.lastEpisode(source,key)?.episodeKey ?: return
        val rev=revision
        launchSafe {
            val item=resolveKeys(rev,listOf(episodeKey)).first[episodeKey]
            if(rev!=revision||openedSeriesKey!=key)return@launchSafe
            if(item==null){status.setText(R.string.last_episode_unavailable);return@launchSafe}
            val season=item.takeIf{!it.isNull("season")}?.optLong("season")
            // Channel +/- then continues within the season of the resumed episode.
            if(season!=chosenSeason||unknownSeason!=(season==null)){chosenSeason=season;unknownSeason=season==null;updateSeasonLabel();libraryFocusId=null;search()}
            selectChannel(item.getLong("id"))
        }
    }
    private suspend fun fetchRows(rev:Long,section:String,group:Long?,text:String,start:Int,limit:Int,list:String?=null):JSONObject {
        val show=openedSeries
        if(show==null&&list!=null)return libraryRows(rev,section,list,text,start,limit)
        if(show==null)return core.call("browse","revision" to rev,"kind" to section,"category" to group,"query" to text,"offset" to start,"limit" to limit)
        val data=core.call("series_page","revision" to rev,"id" to show,"season" to chosenSeason,"unknown" to unknownSeason,"offset" to start)
        val all=data.getJSONArray("items")
        data.put("items",JSONArray().apply{repeat(minOf(limit,all.length())){put(all.getJSONObject(it))}})
        return data
    }
    /** History or favourites in their stored order, mapped to this revision one page at a time. */
    private suspend fun libraryRows(rev:Long,section:String,list:String,text:String,start:Int,limit:Int):JSONObject {
        val source=activeSourceId;val librarySection=LibrarySection.of(section)
        val all=if(source==null||librarySection==null)emptyList() else if(list=="recent")userLibrary.recent(source,librarySection) else userLibrary.favorites(source,librarySection)
        val terms=text.lowercase(java.util.Locale.ROOT).split(' ','\t').filter{it.isNotEmpty()}
        val matching=if(terms.isEmpty())all else all.filter{item->val name=item.name.lowercase(java.util.Locale.ROOT);terms.all{name.contains(it)}}
        val page=matching.drop(start).take(limit)
        val (resolved,keyed)=resolveKeys(rev,page.map{it.key})
        val items=JSONArray()
        page.forEachIndexed{i,item->
            items.put(resolved[item.key] ?: JSONObject().put("id",UNAVAILABLE_BASE-start-i).put("name",item.name).put("key",item.key)
                .put("available",false).put("item_type",if(librarySection==LibrarySection.SERIES)"series" else "entry"))
        }
        return JSONObject().put("revision",rev).put("total",matching.size).put("items",items).put("keyed",keyed||page.isEmpty())
    }
    private fun updateGridColumns(){
        val layout=channels.layoutManager as? GridLayoutManager ?: return
        val columns=(channels.width/dp(if(resources.configuration.fontScale>1.3f)146 else 108)).coerceIn(1,9)
        if(layout.spanCount!=columns)layout.spanCount=columns
        // Card cell minus end margin and padding (Rows.onCreateViewHolder); posters are 2:3.
        val poster=(channels.width-channels.paddingLeft-channels.paddingRight)/columns-dp(18)
        if(poster>0)artwork.setTargetSize(poster,poster*3/2)
    }
    private fun updateLibraryLayout(){
        if(!::browserColumn.isInitialized)return
        val large=resources.configuration.fontScale>1.3f
        val shouldGrid=gridMode
        val wasGrid=channels.layoutManager is GridLayoutManager
        if(shouldGrid!=wasGrid){
            channels.layoutManager=if(shouldGrid)TvGridLayoutManager(this,2) else TvLinearLayoutManager(this)
            channelRows.notifyDataSetChanged()
        }
        browserColumn.layoutParams=(browserColumn.layoutParams as LinearLayout.LayoutParams).apply{
            width=if(shouldGrid)0 else dp(if(large)260 else 216);weight=if(shouldGrid)1f else 0f
        }
        sidebar.layoutParams=(sidebar.layoutParams as LinearLayout.LayoutParams).apply{width=if(shouldGrid)0 else -2;weight=if(shouldGrid)1.1f else 0f}
        mediaColumn.layoutParams=(mediaColumn.layoutParams as LinearLayout.LayoutParams).apply{width=0;weight=1f}
        val hidePlayer=shouldGrid&&(kind!="unclassified"||selectedId==null)
        mediaColumn.visibility=if(hidePlayer)View.GONE else View.VISIBLE
        details.visibility=mediaColumn.visibility
        seriesHeader.visibility=if(openedSeries!=null)View.VISIBLE else View.GONE
        seriesPoster.visibility=if(large)View.GONE else View.VISIBLE
        movieHeader.visibility=if(openedMovie!=null)View.VISIBLE else View.GONE
        movieScroll.visibility=movieHeader.visibility
        updateMovieActions()
        channels.visibility=if(openedMovie!=null)View.GONE else View.VISIBLE
        resultCount.visibility=if(openedMovie!=null)View.GONE else View.VISIBLE
        channelSearch.visibility=if(!gridMode&&kind!="live")View.GONE else View.VISIBLE
        channelHeading.visibility=channelSearch.visibility
        channels.contentDescription=getString(if(openedSeries!=null)R.string.episodes_list else if(shouldGrid)(if(kind=="series")R.string.series_library else R.string.movie_library) else R.string.entries_list)
        channels.post{updateGridColumns()}
        if(!shouldGrid){prefetchWork?.cancel();artwork.prefetch(emptyList())}
    }
    private fun openMovie(row:Row){
        if(busy)return
        browseSnapshot=BrowseSnapshot(total,libraryFocusId,libraryFocusIndex)
        openedMovie=row
        movieTitle.text=listOf(row.title,row.year,row.quality).filter{it.isNotEmpty()}.joinToString("\n")
        updateMovieActions()
        val rev=revision
        artwork.bind(moviePoster,artworkKey(rev,row.id,false),artworkAddress(rev,row.id,false))
        updateLibraryLayout();moviePlay.requestFocus()
    }
    private fun openSeries(id:Long){
        if(seriesWork?.isActive==true)return
        val rev=revision
        val generation=searchGeneration
        seriesWork=launchSafe {
            var data=core.call("series_page","revision" to rev,"id" to id,"offset" to 0)
            val providerId=data.optString("series_id").takeIf{it.isNotEmpty()&&it!="null"}
            if(data.getInt("total")==0&&providerId!=null){
                val source=activeSource ?: return@launchSafe
                status.setText(R.string.loading_episodes)
                loader.episodes(source,core,rev,id,providerId)
                data=core.call("series_page","revision" to rev,"id" to id,"offset" to 0)
            }
            if(rev!=revision||generation!=searchGeneration)return@launchSafe
            if(status.text.toString()==getString(R.string.loading_episodes))status.text=resources.getQuantityString(R.plurals.episodes_available,data.getInt("total"),data.getInt("total"))
            browseSnapshot=BrowseSnapshot(total,libraryFocusId,libraryFocusIndex)
            openedSeries=id;seasonOffset=0;seriesSeasons=data.getJSONArray("seasons");seasonTotal=data.getInt("season_total")
            openedSeriesKey=data.optString("key").takeIf{it.isNotEmpty()&&it!="null"};openedSeriesName=data.getString("name")
            // Open on the season of the last watched episode when the first season page lists it.
            val lastSeason=activeSourceId?.let{source->openedSeriesKey?.let{userLibrary.lastEpisode(source,it)}}?.season
            val first=(0 until seriesSeasons.length()).map(seriesSeasons::getJSONObject)
                .firstOrNull{lastSeason!=null&&!it.isNull("season")&&it.getLong("season")==lastSeason} ?: seriesSeasons.optJSONObject(0)
            chosenSeason=if(first==null||first.isNull("season"))null else first.getLong("season")
            unknownSeason=first?.isNull("season")==true
            val summary="${data.getString("name")}\n${resources.getQuantityString(R.plurals.seasons_count,seasonTotal,seasonTotal)} · ${resources.getQuantityString(R.plurals.episodes_count,data.getInt("total"),data.getInt("total"))}"
            seriesTitle.text=if(resources.configuration.fontScale>1.3f)data.getString("name") else summary
            seriesTitle.maxLines=if(resources.configuration.fontScale>1.3f)2 else 3
            seriesTitle.ellipsize=TextUtils.TruncateAt.END
            seriesTitle.contentDescription=summary
            artwork.bind(seriesPoster,artworkKey(rev,id,true),artworkAddress(rev,id,true))
            updateSeasonLabel();updateSeriesActions();updateLibraryLayout();firstChannelFocus=true
            libraryFocusId=null;search();channels.scrollToPosition(0)
        }
    }
    private fun updateSeasonLabel(){seasonButton.text=if(unknownSeason)getString(R.string.season_unknown_menu) else chosenSeason?.let{getString(R.string.season_menu,it)}?:getString(R.string.choose_season_menu)}
    private fun chooseSeason(){
        val id=openedSeries ?: return
        fun showPage(){
            val labels=Array(seriesSeasons.length()){i->val entry=seriesSeasons.getJSONObject(i)
                "${if(entry.isNull("season"))getString(R.string.season_unknown) else getString(R.string.season,entry.getLong("season"))} (${entry.getInt("count")})"}
            val dialog=AlertDialog.Builder(this).setTitle(R.string.choose_season).setItems(labels){_,index->
                val entry=seriesSeasons.getJSONObject(index);unknownSeason=entry.isNull("season")
                chosenSeason=if(unknownSeason)null else entry.getLong("season")
                updateSeasonLabel();libraryFocusId=null;firstChannelFocus=true;search();channels.scrollToPosition(0)
            }.setNegativeButton(R.string.close,null)
            if(seasonOffset+seriesSeasons.length()<seasonTotal)dialog.setPositiveButton(R.string.seasons_more){_,_->loadSeasonPage(id,seasonOffset+128)}
            if(seasonOffset>0)dialog.setNeutralButton(R.string.seasons_previous){_,_->loadSeasonPage(id,(seasonOffset-128).coerceAtLeast(0))}
            dialog.show()
        }
        showPage()
    }
    private fun loadSeasonPage(id:Long,start:Int){val rev=revision;launchSafe{
        val data=core.call("series_page","revision" to rev,"id" to id,"offset" to 0,"season_offset" to start)
        if(revision!=rev||openedSeries!=id)return@launchSafe
        seasonOffset=start;seriesSeasons=data.getJSONArray("seasons");chooseSeason()
    }}
    private fun closeSeries(restore:Boolean,focus:Boolean){
        seriesWork?.cancel()
        if(openedSeries==null&&openedMovie==null){if(!restore)browseSnapshot=null;return}
        val generation=++searchGeneration
        // In-flight page loads belong to the old generation and will not clear their own flags.
        loadingPage=false;focusNextPage=false;selectNextPage=false
        pendingPrevious=false;pendingPreviousFocus=false;pendingPreviousSelect=false
        openedSeries=null;openedMovie=null;chosenSeason=null;unknownSeason=false;openedSeriesKey=null
        val snapshot=browseSnapshot;browseSnapshot=null
        // Episode rows belong to the closed series; never render them as library cards.
        if(!restore||snapshot==null){channelRows.replace(emptyList());total=0;offset=0}
        updateLibraryLayout()
        if(restore&&snapshot==null)search()
        if(restore&&snapshot!=null){
            offset=(snapshot.index/64)*64;total=snapshot.total;channelRows.replace(emptyList())
            libraryFocusId=snapshot.focus;libraryFocusIndex=snapshot.index;libraryFocusRevision=revision
            resultCount.text=resources.getQuantityString(if(kind=="series")R.plurals.series_count else R.plurals.movies_count,total,total);channelEmpty.visibility=if(total==0)View.VISIBLE else View.GONE
            val rev=revision;val start=offset;val section=kind;val group=category;val text=query;val list=special
            launchSafe{
                val data=fetchRows(rev,section,group,text,start,64,list)
                if(revision!=rev||searchGeneration!=generation)return@launchSafe
                val items=data.getJSONArray("items");total=data.getInt("total")
                channelRows.replace(List(items.length()){Row.from(items.getJSONObject(it))})
                if(focus)channels.post{returnToChannels()}
            }
        }
    }
    private fun navigatePlayback(direction:Int){
        val id=selectedId ?: return
        val context=playContext ?: return
        val rev=revision
        if(selectionWork?.isActive==true)return
        context.ids?.let{ids->
            val index=ids.indexOf(id)
            if(index>=0)ids.getOrNull(index+direction)?.let{selectChannel(it,true)}
            return
        }
        launchSafe {
            val next=core.call("adjacent","revision" to rev,"id" to id,"direction" to direction,
                "kind" to context.kind,"category" to context.category,"query" to context.query,
                "show" to context.show,"season" to context.season,"unknown" to context.unknown)
            if(revision==rev&&selectedId==id&&!next.isNull("id"))selectChannel(next.getLong("id"),true)
        }
    }

    private fun playerAction(action: String) {
        when(action) {
            "play" -> launchSafe {
                if(playback.player==null || playback.phase=="finished") selectedId?.let { restartSelection(it); markChannel(it) }
                else playback.togglePause()
            }
            "favorite" -> tracker.current?.let { toggleFavorite(it.section,it.libraryKey,it.seriesName ?: it.name) }
            "back" -> launchSafe { playback.seek(-30_000) }
            "forward" -> launchSafe { playback.seek(30_000) }
            "stop" -> launchSafe { playerPane.cancelSeek(); playback.stop(); channelRows.notifyDataSetChanged(); playerPane.showControls(true) }
            "reconnect" -> {
                if(reconnectWork?.isActive==true || busy || selectedId==null) return
                val restoreRefreshFocus=details.refreshButton.hasFocus()
                playerPane.cancelSeek(); details.refreshButton.isEnabled=false
                reconnectWork=launchSafe { try {
                    if(playback.player==null) selectedId?.let { restartSelection(it) } else playback.reconnect()
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
    /** Play the selection again after stop/end: a new token, saved progress unless it finished. */
    private suspend fun restartSelection(id:Long){
        val rev=revision
        val entry=core.call("entry","revision" to rev,"id" to id)
        if(rev!=revision)return
        startPlayback(rev,id,entry,false,playContext?.kind ?: kind)
    }
    override fun onStart() {
        super.onStart()
        if (::artwork.isInitialized) artwork.setActive(true)
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
        // Downloads already running finish into the caches; nothing new starts in the background.
        if (::artwork.isInitialized) artwork.setActive(false)
        if (::playback.isInitialized) {
            val previous = lifecycleWork
            // The release reports the final position; write it now instead of relying on app exit.
            lifecycleWork = scope.launch { previous?.join(); playback.suspendPlayback(); libraryStore.flush(userLibrary) }
        }
        super.onStop()
    }
    override fun onDestroy() {
        if (::playback.isInitialized) { playback.release(); if (userLibraryLoaded) libraryStore.flush(userLibrary) }
        if (::artwork.isInitialized) artwork.close()
        scope.cancel()
        if (::loader.isInitialized) loader.close()
        if (::core.isInitialized) core.close()
        super.onDestroy()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::artwork.isInitialized) artwork.trim(level)
    }
    private fun launchSafe(block: suspend () -> Unit): Job {
        return scope.launch {
            try { block() } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val details=ImportErrors.describe(error)
                lastFailure=details.json().put("exception_type",error.javaClass.simpleName)
                val message=details.message(this@MainActivity)
                status.text = message
                if (!libraryReady) {
                    chrome.visibility=View.INVISIBLE; startup.showError(message)
                } else AlertDialog.Builder(this@MainActivity).setTitle(R.string.operation_failed).setMessage(message)
                    .setNeutralButton(R.string.details){_,_->diagnosticsDialog()}
                    .setPositiveButton(R.string.source_choose){_,_->chooseSources()}.setNegativeButton(R.string.close, null).show()
            }
        }
    }
    private suspend fun importSource(source: JSONObject, add: Boolean, preferCache: Boolean = false) {
        if (busy) return
        if(!preferCache)refreshWork?.cancel()
        busy = true
        if (!libraryReady) startup.showLoading(getString(R.string.loading_source))
        playerPane.cancelSeek()
        status.setText(R.string.importing_playlist)
        try {
            val cached=if(preferCache)try{loader.restore(source,core)}catch(error:Exception){
                if(error is kotlinx.coroutines.CancellationException)throw error
                lastFailure=ImportErrors.describe(error,"restore_cache").json();null
            }else null
            val result = cached ?: loader.load(source, core)
            // Playlist replacement is explicit: stop the previous media and its watchdog together.
            playback.stop()
            // The stop above reported the last position; later samples belong to no item.
            tracker.clear()
            closeSeries(false,false);playContext=null
            revision = result.getLong("revision"); selectedId = null
            libraryFocusId=null; libraryFocusIndex=0; libraryFocusRevision=revision
            activeSource=source
            activeSourceId=loader.identity(source)
            updatePlayerFavorite()
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
            getPreferences(MODE_PRIVATE).edit().putString("activeSourceId",activeSourceId).apply()
            val warning=result.optJSONArray("warnings")?.length()?:0
            val count=result.getInt("count")
            status.text = "${resources.getQuantityString(R.plurals.entries_count,count,count)}${if(cached!=null)" · "+getString(R.string.status_cached) else ""}${if(warning>0)" · "+getString(R.string.status_warnings) else ""} · ${source.optString("name", "Playlist")}"
            if(cached!=null&&loader.shouldRefresh(source)){
                status.setText(R.string.refreshing_in_background)
                refreshWork=launchSafe {
                // Build a new generation without disturbing playback or its current revision.
                try {
                    loader.load(source,core,activate=false)
                    if(activeSource===source&&selectedId==null&&!busy)importSource(source,false,true)
                } catch(error:Exception) {
                    if(error is kotlinx.coroutines.CancellationException)throw error
                    lastFailure=ImportErrors.describe(error,"refresh_cache").json()
                    if(selectedId==null)status.setText(R.string.refresh_failed)
                }
                }
            }
        } finally { busy = false; details.refreshButton.isEnabled=selectedId!=null }
    }
    private suspend fun refreshCategories() {
        val data = core.call("categories")
        val array = data.getJSONArray("categories")
        val uncategorized = getString(R.string.category_uncategorized)
        categoryItems = List(array.length()) { i ->
            val c = array.getJSONObject(i)
            // The core names entries without a provider group "Ohne Kategorie"; show that in the UI language.
            val name = c.getString("name").let { if (it == UNCATEGORIZED) uncategorized else it }
            CategoryItem(c.getLong("id"), name, c.getString("kind"), c.getInt("count"))
        }
        val kinds = data.getJSONArray("kinds")
        for (i in 0 until kinds.length()) {
            val section = kinds.getJSONObject(i)
            val key = section.getString("id")
            val title = getString(when (key) { "live" -> R.string.tab_live; "movies" -> R.string.tab_movies; "series" -> R.string.tab_series; else -> R.string.tab_unclassified })
            kindButtons[key]?.visibility=if(key=="unclassified"&&section.getInt("count")==0)View.GONE else View.VISIBLE
            kindButtons[key]?.text = "$title (${section.getInt("count")})"
        }
        switchKind(data.getString("preferred_kind"), false)
    }
    private fun switchKind(value: String, refresh: Boolean = true) {
        closeSeries(false,false)
        kind = value; category = null; special = null; offset = 0; query = ""
        searchGeneration++
        searchWork?.cancel()
        channelSearch.setText(""); categorySearch.setText("")
        searchWork?.cancel()
        channelSearch.hint = getString(when (kind) { "movies" -> R.string.search_movies; "series" -> R.string.search_series; else -> R.string.search_channels })
        channelSearch.contentDescription = channelSearch.hint
        kindButtons.forEach { (key, tab) -> tab.isActivated = key == kind; tab.isSelected = key == kind }
        channelRows.replace(emptyList()); total = 0
        channels.scrollToPosition(0); categories.scrollToPosition(0)
        updateLibraryLayout()
        renderCategories()
        if (refresh) search()
    }
    private fun renderCategories() {
        val text = categorySearch.text.toString().trim().lowercase(java.util.Locale.ROOT)
        val matches = categoryItems.filter { it.kind == kind && it.name.lowercase(java.util.Locale.ROOT).contains(text) }
        val rows = mutableListOf<Row>()
        val section = LibrarySection.of(kind)
        val source = activeSourceId
        // Personal lists come first in Live, Movies and Series; the category search filters providers only.
        if (section != null && text.isEmpty()) {
            val recent = source?.let { userLibrary.recentCount(it, section) } ?: 0
            val favorites = source?.let { userLibrary.favoriteCount(it, section) } ?: 0
            rows += Row(RECENT_ROW, getString(R.string.recent_row, recent))
            rows += Row(FAVORITES_ROW, getString(R.string.favorites_row, favorites))
        }
        shownCounts = if (source == null || section == null) "" else "${userLibrary.recentCount(source,section)}/${userLibrary.favoriteCount(source,section)}/${userLibrary.historyLimit}"
        rows += Row(-1, getString(R.string.category_all))
        rows += matches.map { Row(it.id, "${it.name} (${it.count})") }
        categoryRows.replace(rows)
        categoryEmpty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        categoryEmpty.text = if (text.isNotEmpty()) getString(R.string.categories_no_match, categorySearch.text) else getString(R.string.categories_empty_section)
        channelHeading.text = when (special) { "recent" -> getString(R.string.recent); "favorites" -> getString(R.string.favorites); else -> categoryItems.firstOrNull { it.id == category }?.name ?: getString(R.string.category_all) }
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
        val requestSpecial = special.takeIf { openedSeries == null }
        val focusIndex = pendingSpecialFocus; pendingSpecialFocus = null
        launchSafe {
            val data = fetchRows(requestRevision,requestKind,requestCategory,requestQuery,requestOffset,64,requestSpecial)
            if (generation != searchGeneration || revision != requestRevision) return@launchSafe
            total = data.getInt("total")
            val array = data.getJSONArray("items")
            channelRows.replace(List(array.length()) { i -> val c = array.getJSONObject(i); Row.from(c) })
            channelEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE
            channelEmpty.text = when {
                requestQuery.isNotBlank() -> getString(R.string.no_results_for, requestQuery)
                requestSpecial != null && !data.optBoolean("keyed", true) -> getString(R.string.library_updating)
                requestSpecial == "recent" -> getString(when(requestKind){"live"->R.string.recent_empty_live;"movies"->R.string.recent_empty_movies;else->R.string.recent_empty_series})
                requestSpecial == "favorites" -> getString(R.string.favorites_empty)
                else -> getString(R.string.selection_empty)
            }
            clearFiltersButton.visibility = if(total==0 && (requestQuery.isNotBlank() || requestCategory!=null || categorySearch.text.isNotBlank())) View.VISIBLE else View.GONE
            if (focusIndex != null) {
                // After removing an entry: its successor, else the list's category row.
                if (array.length() > 0) { val target = focusIndex.coerceIn(0, array.length() - 1); channels.scrollToPosition(target)
                    channels.doOnNextLayout { channels.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() } }
                else categories.post { categories.findViewHolderForItemId(if (requestSpecial == "recent") RECENT_ROW else FAVORITES_ROW)?.itemView?.requestFocus() }
            }
            if (catalogPending) {
                channels.doOnNextLayout { if(generation==searchGeneration) showLibrary() }
                channels.requestLayout()
            }
            if (firstChannelFocus && array.length() > 0) {
                channels.doOnNextLayout {
                    focusFirstChannel()
                }
            }
            resultCount.text = resources.getQuantityString(if(kind=="series"&&openedSeries==null)R.plurals.series_count else if(openedSeries!=null)R.plurals.episodes_count else R.plurals.entries_count,total,total)
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
        val requestKind=kind; val requestCategory=category; val requestQuery=query; val requestSpecial=special.takeIf{openedSeries==null}
        launchSafe {
            try {
                val data=fetchRows(requestRevision,requestKind,requestCategory,requestQuery,start,64,requestSpecial)
                if(generation!=searchGeneration || revision!=requestRevision) return@launchSafe
                val array=data.getJSONArray("items")
                var localStart=channelRows.itemCount
                channelRows.append(List(array.length()) { i-> val c=array.getJSONObject(i); Row.from(c) })
                if(channelRows.itemCount>512){
                    val drop=wholeRows(channelRows.itemCount-512)
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
    /** [count] rounded up to whole grid rows. Dropping or prepending a partial row would move every
     * card by some columns, and a held D-pad could then lose the focused card off screen. */
    private fun wholeRows(count:Int):Int{
        val columns=(channels.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        return (count+columns-1)/columns*columns
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
        val requestOffset=(offset-wholeRows(64)).coerceAtLeast(0);val oldOffset=offset
        val requestKind=kind;val requestCategory=category;val requestQuery=query;val requestSpecial=special.takeIf{openedSeries==null}
        launchSafe{try{
            val data=fetchRows(requestRevision,requestKind,requestCategory,requestQuery,requestOffset,(oldOffset-requestOffset),requestSpecial)
            if(generation!=searchGeneration||revision!=requestRevision)return@launchSafe
            val array=data.getJSONArray("items")
            val layout=channels.layoutManager as LinearLayoutManager
            val first=layout.findFirstVisibleItemPosition();val top=layout.findViewByPosition(first)?.top?:0
            channelRows.prepend(List(array.length()){i->val c=array.getJSONObject(i);Row.from(c)})
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
        if(playContext?.kind!="live"){epg.text="";return}
        val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        epg.text = if (rows.length() == 0) getString(R.string.no_epg) else (0 until rows.length()).joinToString("\n") {
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
        AlertDialog.Builder(this).setTitle(R.string.saved_playlists)
            .setItems(sources.map { it.optString("name", "Playlist") }.toTypedArray()) { _, i -> launchSafe { importSource(sources[i], false) } }
            .setNeutralButton(R.string.remove) { _, _ -> removeSource() }.setNegativeButton(R.string.close, null).show()
    }
    private fun removeSource() {
        AlertDialog.Builder(this).setTitle(R.string.source_remove)
            .setItems(sources.map { it.optString("name", "Playlist") }.toTypedArray()) { _, i ->
                val removed=sources[i]
                val confirm=AlertDialog.Builder(this).setTitle(getString(R.string.source_remove_confirm,removed.optString("name","Playlist")))
                    .setMessage(R.string.source_remove_message)
                    .setNegativeButton(R.string.cancel,null)
                    .setPositiveButton(R.string.source_remove) { _,_-> launchSafe {
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
                        val removedId=loader.identity(removed)
                        if(activeSourceId==removedId){activeSourceId=null;tracker.clear();updatePlayerFavorite()}
                        userLibrary.removeSource(removedId);libraryStore.flush(userLibrary)
                    } }.create()
                confirm.show()
                confirm.getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode=true;requestFocus() }
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun urlDialog(isEpg: Boolean) {
        form(getString(if (isEpg) R.string.xmltv_address else R.string.playlist_address), listOf(getString(R.string.field_name), getString(R.string.field_url))) { values ->
            val source = JSONObject().put("kind", "url").put("name", values[0].ifBlank { if (isEpg) "EPG" else "Playlist" }).put("url", values[1])
            if (isEpg) importGuide(source) else importSource(source, true)
        }
    }
    private fun xtreamDialog() {
        form(getString(R.string.xtream_title), listOf(getString(R.string.field_name), getString(R.string.field_server), getString(R.string.field_username), getString(R.string.field_password)), 3) { v ->
            val source = JSONObject().put("kind", "xtream").put("name", v[0].ifBlank { "Xtream" }).put("server", v[1]).put("username", v[2]).put("password", v[3]).put("vod", true)
            importSource(source, true)
        }
    }
    private fun epgDialog() {
        AlertDialog.Builder(this).setTitle(R.string.epg_title)
            .setItems(arrayOf(getString(R.string.epg_open_file), getString(R.string.epg_http_address))) { _, i -> if (i == 0) pickDocument(true) else urlDialog(true) }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun form(title: String, hints: List<String>, passwordIndex: Int = -1, submit: suspend (List<String>) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val fields = hints.mapIndexed { i, hint -> EditText(this).apply {
            id=View.generateViewId();isSingleLine = true; textSize = 18f
            val fieldLabel=hint
            this.hint=when(i){0->getString(R.string.field_name_hint);1->getString(R.string.field_server_hint);else->hint}
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
            .setPositiveButton(R.string.apply,null).setNegativeButton(R.string.cancel){_,_->cancelForm()}.create()
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
            if(invalid>=0){fields[invalid].error=getString(if(invalid==1)R.string.field_invalid_url else R.string.field_required);fields[invalid].requestFocus();return@setOnClickListener}
            work=scope.launch {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                errorText.setText(R.string.source_checking);errorText.visibility=View.VISIBLE
                try { submit(values);dialog.dismiss() }
                catch(error:Exception){
                    if(error is kotlinx.coroutines.CancellationException)throw error
                    val detail=ImportErrors.describe(error);lastFailure=detail.json()
                    val message=detail.message(this@MainActivity);errorText.text=message;status.text=message
                    if(!libraryReady)startup.showError(message)
                } finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
            }
        }
    }
    private fun pickDocument(isEpg: Boolean) {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, if (isEpg) 101 else 100) }
        catch (_: Exception) { status.setText(R.string.no_file_picker) }
    }
    @Deprecated("Platform Activity document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        launchSafe {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val source = JSONObject().put("kind", "file").put("name", uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.file_default_name)).put("uri", uri.toString())
            if (requestCode == 101) importGuide(source) else importSource(source, true)
        }
    }
    private suspend fun importGuide(source: JSONObject) {
        loader.load(source, core, true)
        withContext(Dispatchers.IO) { epgStore.save(listOf(source)) }
        refreshGuide()
        status.setText(R.string.epg_imported)
    }
    private fun setFullScreen(value: Boolean) {
        if(value && selectedId==null) return
        if(value==fullScreen) return
        playerPane.cancelSeek()
        if(value) restoreFocus = currentFocus?.takeUnless { playerPane.hasFocus() }
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
        if(openedMovie!=null){moviePlay.requestFocus();return}
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
            val requestKind=kind;val requestCategory=category;val requestQuery=query;val requestSpecial=special.takeIf{openedSeries==null}
            launchSafe {
                val data=fetchRows(requestRevision,requestKind,requestCategory,requestQuery,targetOffset,64,requestSpecial)
                if(revision!=requestRevision || searchGeneration!=generation)return@launchSafe
                val array=data.getJSONArray("items")
                offset=targetOffset;channelRows.replace(List(array.length()){i->val c=array.getJSONObject(i);Row.from(c)})
                val restored=channelRows.rows.indexOfFirst { it.id==wanted }
                if(restored>=0)focusAt(restored) else channelSearch.requestFocus()
            }
        } else if(channelRows.itemCount>0)focusAt(0) else channelSearch.requestFocus()
    }
    private fun showProgrammeDetails() {
        val text=listOf(status.text.toString(),epg.text.toString()).filter { it.isNotBlank() }.joinToString("\n\n")
        val content=label(text,16f).apply { setPadding(dp(20),dp(12),dp(20),dp(12));setLineSpacing(0f,1.4f);isFocusable=true }
        AlertDialog.Builder(this).setTitle(R.string.programme_title).setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.close,null).show()
    }
    private fun handleBack() {
        playerPane.cancelSeek()
        if(playerPane.controlsVisible && playerPane.hasFocus()) playerPane.hideControls()
        else if(fullScreen) setFullScreen(false)
        else if(playerPane.hasFocus())returnToChannels()
        else if(openedSeries!=null||openedMovie!=null)closeSeries(true,true) else finish()
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
                    navigatePlayback(direction)
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
            .put("user_library",libraryStore.diagnostics(userLibrary)).put("artwork",artwork.stats())
        if(android.os.Build.VERSION.SDK_INT>=30){
            val manager=getSystemService(android.app.ActivityManager::class.java)
            val exit=manager.getHistoricalProcessExitReasons(packageName,0,1).firstOrNull()
            if(exit!=null)report.put("previous_exit",JSONObject().put("reason",exit.reason).put("time",exit.timestamp)
                .put("meaning",when(exit.reason){android.app.ApplicationExitInfo.REASON_LOW_MEMORY->getString(R.string.exit_low_memory);android.app.ApplicationExitInfo.REASON_CRASH_NATIVE->getString(R.string.exit_native_crash);android.app.ApplicationExitInfo.REASON_ANR->getString(R.string.exit_anr);android.app.ApplicationExitInfo.REASON_CRASH->getString(R.string.exit_crash);else->getString(R.string.exit_other)}))
        }else report.put("previous_exit",getString(R.string.exit_unavailable))
        val text=report.toString(2)
        val content=TextView(this@MainActivity).apply{textSize=13f;setTextIsSelectable(true);setPadding(dp(20),dp(10),dp(20),dp(10));this.text=text}
        AlertDialog.Builder(this@MainActivity).setTitle(R.string.diagnostics_title).setView(android.widget.ScrollView(this@MainActivity).apply{addView(content)})
            .setPositiveButton(R.string.copy){_,_->(getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.diagnostics_clip),text))}
            .setNegativeButton(R.string.close,null).show()
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
    private fun list(adapter: Rows) = TvList(this).apply { id = View.generateViewId(); layoutManager = TvLinearLayoutManager(this@MainActivity); this.adapter = adapter; setHasFixedSize(true); itemAnimator = null; clipToPadding=false; setPadding(dp(2),dp(2),dp(2),dp(2)) }
    /** [key] is the stable identity for history/favourites; [available] is false for a remembered
     * entry that the current list no longer contains. */
    private data class Row(val id:Long,val title:String,val series:Boolean=false,val year:String="",val quality:String="",val episode:String="",
                           val key:String?=null,val available:Boolean=true) {
        companion object {fun from(c:JSONObject)=Row(c.getLong("id"),c.getString("name"),c.optString("item_type")=="series",
            if(c.isNull("year"))"" else c.optString("year"),if(c.isNull("quality"))"" else c.optString("quality"),
            if(c.isNull("episode"))"" else "E"+c.optString("episode").padStart(2,'0'),
            c.optString("key").takeIf{it.isNotEmpty()&&it!="null"},c.optBoolean("available",true))}
    }
    /** Section of the visible list: episodes and series cards belong to Series. */
    private val listSection get()=LibrarySection.of(kind)
    private fun favoriteOf(row:Row):Boolean{
        val source=activeSourceId ?: return false
        val section=listSection ?: return false
        val key=row.key ?: return false
        return openedSeries==null&&userLibrary.isFavorite(source,section,key)
    }
    /** Short status for cards and rows: progress, last episode or the "watched" mark. */
    private fun progressLabel(row:Row):Pair<String?,Float?>{
        val source=activeSourceId ?: return null to null
        if(!row.available)return getString(R.string.unavailable_now) to null
        val key=row.key ?: return null to null
        if(row.series&&openedSeries==null){
            val last=userLibrary.lastEpisode(source,key) ?: return null to null
            val progress=last.episodeKey?.let{userLibrary.progress(source,it)}
            val position=progress?.resumeSec?.let{" · "+TvUi.clock(it)} ?: if(progress?.done==true)" · ✓" else ""
            return (last.episodeLabel ?: getString(R.string.recent))+position to progress?.takeIf{!it.done}?.fraction
        }
        if(kind=="live")return null to null
        val progress=userLibrary.progress(source,key) ?: return null to null
        if(progress.done)return getString(R.string.watched) to null
        val resume=progress.resumeSec ?: return null to null
        return (progress.durationSec?.let{"${TvUi.clock(resume)} / ${TvUi.clock(it)}"} ?: TvUi.clock(resume)) to progress.fraction
    }
    /** Long OK / Menu: favourite, history removal and the full title in one place. */
    private fun rowActions(row:Row){
        val source=activeSourceId;val section=listSection
        val actions=mutableListOf<Pair<String,()->Unit>>()
        // Episodes are never favourites themselves: the action applies to their show.
        val favoriteKey=if(openedSeries!=null)openedSeriesKey else row.key
        val favoriteName=if(openedSeries!=null)openedSeriesName else row.title
        if(source!=null&&section!=null&&favoriteKey!=null){
            val active=userLibrary.isFavorite(source,section,favoriteKey)
            val label=if(openedSeries!=null)(if(active)"★ "+getString(R.string.series_favorite_remove) else "☆ "+getString(R.string.series_favorite_add))
                else if(active)"★ "+getString(R.string.favorite_remove) else "☆ "+getString(R.string.favorite_add)
            actions+=label to {toggleFavorite(section,favoriteKey,favoriteName)}
        }
        if(special=="recent"&&openedSeries==null&&source!=null&&section!=null&&row.key!=null)
            actions+=getString(R.string.recent_remove) to {removeRecent(section,row)}
        actions+=getString(R.string.show_full_title) to {showRowTitle(row)}
        AlertDialog.Builder(this).setTitle(row.title).setItems(actions.map{it.first}.toTypedArray()){_,index->actions[index].second()}
            .setNegativeButton(R.string.close,null).show()
    }
    private data class CategoryItem(val id: Long, val name: String, val kind: String, val count: Int)
    private inner class Rows(private val channelList:Boolean=false,val select:(Row)->Unit):RecyclerView.Adapter<Rows.Holder>() {
        init{setHasStableIds(true)}
        // Lazy: adapters are created before the activity has its resources.
        private val seasonsEpisodes by lazy { getString(R.string.seasons_episodes) }
        val rows=ArrayList<Row>()
        inner class Holder(val root:LinearLayout,val text:TextView,val image:ImageView?,val metadata:TextView?,val card:Boolean,
                           val trailing:TextView?=null,val star:StarBadge?=null):RecyclerView.ViewHolder(root){
            var row:Row?=null
            /** OK pressed on this row and whether it became a long press. */
            var okDown=false;var okLong=false
        }
        override fun getItemId(position:Int)=rows[position].id
        override fun getItemViewType(position:Int)=if(channelList&&gridMode)1 else 0
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder{
            val card=viewType==1
            val root=LinearLayout(this@MainActivity).apply{
                id=View.generateViewId();orientation=if(card)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                isClickable=true;descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(6),dp(5),dp(6),dp(5));minimumHeight=dp(36);TvUi.focus(this)
                layoutParams=RecyclerView.LayoutParams(-1,-2).apply{bottomMargin=dp(if(card)8 else 2);marginEnd=dp(if(card)6 else 0)}
                accessibilityDelegate=object:View.AccessibilityDelegate(){override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.className=Button::class.java.name
                    info.isSelected=host.isActivated
                    if(android.os.Build.VERSION.SDK_INT>=30){
                        val row=(channels.findContainingViewHolder(host) as? Holder)?.row
                        val states=listOfNotNull(
                            if(host.isActivated)getString(if(channelList)R.string.state_playing else R.string.state_selected_category) else null,
                            if(channelList&&row!=null&&favoriteOf(row))getString(R.string.state_favorite) else null,
                            if(channelList&&row!=null)progressLabel(row).first else null)
                        if(states.isNotEmpty())info.stateDescription=states.joinToString(", ")
                    }
                }}
            }
            val image=if(channelList)(if(card)PosterImageView(this@MainActivity) else ImageView(this@MainActivity)).apply{
                scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(TvUi.background)
                root.addView(this,LinearLayout.LayoutParams(if(card)-1 else dp(28),dp(if(card)132 else 28)).apply{if(!card)marginEnd=dp(7)})
            }else null
            val text=label("",if(card)12f else 10f).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END}
            root.addView(text,LinearLayout.LayoutParams(if(card)-1 else 0,-2,if(card)0f else 1f))
            val metadata=if(card)label("",8f).apply{setTextColor(TvUi.muted);root.addView(this,LinearLayout.LayoutParams(-1,-2))}else null
            // List rows (channels, episodes) end with progress text and the favourite star.
            val trailing=if(channelList&&!card)label("",11f).apply{setTextColor(TvUi.muted);visibility=View.GONE;fontFeatureSettings="tnum"
                root.addView(this,LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(4)})}else null
            val star=if(channelList&&!card)StarBadge(this@MainActivity).apply{visibility=View.GONE
                root.addView(this,LinearLayout.LayoutParams(dp(18),dp(18)).apply{marginStart=dp(4)})}else null
            return Holder(root,text,image,metadata,card,trailing,star)
        }
        /** Favourite and progress only; a status payload never rebinds artwork or listeners. */
        private fun bindStatus(holder:Holder,row:Row){
            if(!channelList)return
            val favorite=favoriteOf(row)
            val (label,fraction)=progressLabel(row)
            (holder.image as? PosterImageView)?.apply{this.favorite=favorite;this.progress=fraction}
            holder.star?.apply{active=favorite;visibility=if(favorite)View.VISIBLE else View.GONE}
            holder.trailing?.apply{text=label?:"";visibility=if(label==null)View.GONE else View.VISIBLE}
            holder.metadata?.text=label ?: if(row.series)seasonsEpisodes else listOf(row.year,row.quality).filter{it.isNotEmpty()}.joinToString(" · ")
            holder.metadata?.setTextColor(if(label!=null&&row.available)TvUi.accent else TvUi.muted)
            holder.root.alpha=if(row.available)1f else 0.55f
        }
        override fun onBindViewHolder(holder:Holder,position:Int,payloads:MutableList<Any>){
            if(payloads.isNotEmpty()&&payloads.all{it==STATUS}){bindStatus(holder,rows[position]);return}
            onBindViewHolder(holder,position)
        }
        override fun getItemCount()=rows.size
        override fun onBindViewHolder(holder:Holder,position:Int){
            val row=rows[position]
            holder.row=row
            val activeCategory=when(special){"recent"->RECENT_ROW;"favorites"->FAVORITES_ROW;else->category?:-1L}
            val active=if(channelList)!row.series&&row.id==selectedId&&::playback.isInitialized&&playback.player!=null else row.id==activeCategory
            holder.root.isActivated=active
            holder.text.text="${if(active)if(channelList)"▶ " else "✓ " else ""}${if(row.episode.isNotEmpty())row.episode+" · " else ""}${row.title}"
            holder.metadata?.text=if(row.series)seasonsEpisodes else listOf(row.year,row.quality).filter{it.isNotEmpty()}.joinToString(" · ")
            holder.root.contentDescription=row.title
            bindStatus(holder,row)
            val poster=holder.image as? PosterImageView
            poster?.focusedCard=holder.root.isFocused
            poster?.onStar=row.key?.takeIf{row.available||special=="favorites"}?.let{key->{listSection?.let{toggleFavorite(it,key,row.title)};Unit}}
            TvUi.onFocus(holder.root){focused->
                holder.text.maxLines=if(focused)4 else 2
                poster?.focusedCard=focused
                if(focused&&channelList&&holder.bindingAdapterPosition>=0){libraryFocusId=row.id;libraryFocusIndex=offset+holder.bindingAdapterPosition;libraryFocusRevision=revision}
            }
            holder.root.setOnClickListener{select(row)}
            holder.root.setOnLongClickListener{if(channelList)rowActions(row) else showRowTitle(row);true}
            holder.image?.let{view->artwork.bind(view,artworkKey(revision,row.id,row.series),artworkAddress(revision,row.id,row.series))}
            holder.root.setOnKeyListener{_,key,event->
                if(key in setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)){
                    // OK acts on release, so holding it opens the actions instead of also starting playback.
                    when{
                        event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0->{holder.okDown=true;holder.okLong=false}
                        event.action==KeyEvent.ACTION_DOWN&&channelList&&holder.okDown&&!holder.okLong->{holder.okLong=true;rowActions(row)}
                        event.action==KeyEvent.ACTION_UP->{if(holder.okDown&&!holder.okLong)select(row);holder.okDown=false;holder.okLong=false}
                    }
                    return@setOnKeyListener true
                }
                if(key==KeyEvent.KEYCODE_INFO&&event.action==KeyEvent.ACTION_DOWN){showRowTitle(row);return@setOnKeyListener true}
                if(channelList&&key==KeyEvent.KEYCODE_MENU){if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0)rowActions(row);return@setOnKeyListener true}
                val index=holder.bindingAdapterPosition
                if(index<0)return@setOnKeyListener false
                // RIGHT always enters the adjacent entry list; geometry alone skips a short list.
                if(!channelList&&key==KeyEvent.KEYCODE_DPAD_RIGHT&&channels.isShown&&channels.childCount>0){
                    if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0){
                        val remembered=libraryFocusId?.let{channels.findViewHolderForItemId(it)?.itemView}
                        (remembered ?: (0 until channels.childCount).map(channels::getChildAt).firstOrNull{it.top>=0} ?: channels.getChildAt(0)).requestFocus()
                    }
                    return@setOnKeyListener true
                }
                val columns=(channels.layoutManager as? GridLayoutManager)?.spanCount?:1
                if(channelList&&key==KeyEvent.KEYCODE_DPAD_RIGHT&&(!holder.card||index%columns==columns-1||index==itemCount-1)){
                    if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0&&mediaColumn.visibility==View.VISIBLE){playerPane.hideControls();video.requestFocus()}
                    return@setOnKeyListener mediaColumn.visibility==View.VISIBLE
                }
                if(channelList&&key==KeyEvent.KEYCODE_DPAD_UP&&event.action==KeyEvent.ACTION_DOWN&&index<columns&&offset>0){loadPreviousPage(focusNew=true);return@setOnKeyListener true}
                if(channelList&&key==KeyEvent.KEYCODE_DPAD_DOWN&&event.action==KeyEvent.ACTION_DOWN&&index>=itemCount-columns&&offset+itemCount<total){loadNextPage(focusNew=true);return@setOnKeyListener true}
                false
            }
        }
        override fun onViewRecycled(holder:Holder){
            holder.image?.apply{artwork.unbind(this);setImageDrawable(null)}
            (holder.image as? PosterImageView)?.onStar=null
            holder.row=null;holder.okDown=false;holder.okLong=false
            super.onViewRecycled(holder)
        }
        fun replace(value:List<Row>){rows.clear();rows.addAll(value);notifyDataSetChanged()}
        fun append(value:List<Row>){val start=rows.size;rows.addAll(value);notifyItemRangeInserted(start,value.size)}
        fun prepend(value:List<Row>){rows.addAll(0,value);notifyItemRangeInserted(0,value.size)}
        fun dropFirst(count:Int){rows.subList(0,count).clear();notifyItemRangeRemoved(0,count)}
        fun dropLast(count:Int){val start=rows.size-count;rows.subList(start,rows.size).clear();notifyItemRangeRemoved(start,count)}
    }
    private fun showRowTitle(row:Row){AlertDialog.Builder(this).setTitle(if(row.series)R.string.row_series else R.string.row_title).setMessage(listOf(row.title,row.year,row.quality).filter{it.isNotEmpty()}.joinToString("\n")).setPositiveButton(R.string.close,null).show()}

}

/** Determine the poster ratio during measurement, including the first grid layout.
 * Favourite star and progress bar are drawn here: no extra views per card while scrolling. */
private class PosterImageView(context:android.content.Context):ImageView(context){
    var favorite=false; set(value){if(field!=value){field=value;invalidate()}}
    /** Shows the outline star as a hint (and touch target) on the focused card. */
    var focusedCard=false; set(value){if(field!=value){field=value;invalidate()}}
    var progress:Float?=null; set(value){if(field!=value){field=value;invalidate()}}
    /** Touch/mouse toggle on the star corner; the D-pad uses long OK or the details instead. */
    var onStar:(()->Unit)?=null
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path=android.graphics.Path()
    private val starSize get()=TvUi.dp(context,22).toFloat()
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val width=View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec((width*1.5f).toInt(),View.MeasureSpec.EXACTLY))
    }
    override fun onDraw(canvas:android.graphics.Canvas){
        super.onDraw(canvas)
        progress?.let{fraction->
            val bar=TvUi.dp(context,4).toFloat()
            paint.style=android.graphics.Paint.Style.FILL
            paint.color=0xCC36424E.toInt();canvas.drawRect(0f,height-bar,width.toFloat(),height.toFloat(),paint)
            paint.color=TvUi.accent;canvas.drawRect(0f,height-bar,width*fraction.coerceIn(0.02f,1f),height.toFloat(),paint)
        }
        if(!favorite&&!focusedCard)return
        val size=starSize;val margin=TvUi.dp(context,4).toFloat()
        val cx=width-margin-size/2;val cy=margin+size/2
        paint.style=android.graphics.Paint.Style.FILL;paint.color=0xCC091018.toInt()
        canvas.drawCircle(cx,cy,size/2,paint)
        TvUi.starPath(path,cx,cy+size*0.03f,size*0.36f,size*0.15f)
        if(favorite){paint.color=TvUi.accent;canvas.drawPath(path,paint)}
        else{paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=size*0.07f;paint.color=Color.WHITE;canvas.drawPath(path,paint)}
    }
    override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
        // Only a visible star reacts; a tap on a hidden corner keeps opening the card.
        val toggle=onStar?.takeIf{favorite||focusedCard} ?: return super.onTouchEvent(event)
        val area=starSize+TvUi.dp(context,10)
        if(event.x<width-area||event.y>area)return super.onTouchEvent(event)
        if(event.actionMasked==android.view.MotionEvent.ACTION_UP)toggle()
        return true
    }
}
