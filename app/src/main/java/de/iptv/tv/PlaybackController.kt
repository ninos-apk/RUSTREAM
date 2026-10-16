@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package de.iptv.tv

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** All Media3 calls stay on the main looper. Rust exclusively owns session-level retries. */
class PlaybackController(
    private val context: Context,
    private val core: Core,
    private val scope: CoroutineScope,
    private val view: PlayerView,
    private val status: (String) -> Unit,
    private val metrics: (PlaybackStats) -> Unit = {},
) {
    var player: ExoPlayer? = null; private set
    private var session: MediaSession? = null
    private var attempt = -1L
    private var selected = false
    private var foreground = false
    private var userPaused = false
    private var terminal = false
    var bufferProfile = PlaybackBufferProfile.BALANCED
    private val operations = Mutex()
    private var ticker: Job? = null
    private var retry: Job? = null
    var title: String = ""; private set
    var phase: String = "idle"; private set
    var lastError: JSONObject? = null; private set
    private var allocator: DefaultAllocator? = null
    private var ingress = IngressMeter()
    private var live = false
    private var bufferTargetMs = 3_000

    suspend fun select(revision: Long, id: Long) = operations.withLock {
        val command = core.call("select", "revision" to revision, "id" to id)
        selected = true; terminal = false; userPaused = false
        lastError=null
        apply(command)
    }
    suspend fun start() = operations.withLock {
        foreground = true
        if (selected && !terminal) apply(core.call("reconnect"))
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                try {
                    if (!selected) { metrics(PlaybackStats()); delay(1_000); continue }
                    operations.withLock {
                        val current = player
                        if (current != null && !terminal) {
                            // Unknown throughput stays null. Cache-end advances are genuine ingress evidence.
                            val suspended = !current.playWhenReady || current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                            userPaused = !current.playWhenReady
                            val rate = ingress.sample(SystemClock.elapsedRealtime())
                            apply(core.call("poll", "attempt" to attempt, "paused" to suspended,
                                "position" to current.currentPosition.coerceAtLeast(0) / 1000.0,
                                "buffered" to current.totalBufferedDuration.coerceAtLeast(0) / 1000.0,
                                "cache_end" to current.bufferedPosition.coerceAtLeast(0) / 1000.0,
                                "buffering" to (current.playbackState == Player.STATE_BUFFERING), "ingress" to rate))
                            publishStats(rate)
                        } else {
                            if (!terminal) apply(core.call("tick"))
                            publishStats()
                        }
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    status("Wiedergabesteuerung fehlgeschlagen")
                }
                delay(1_000)
            }
        }
    }
    suspend fun suspendPlayback() = operations.withLock {
        foreground = false
        ticker?.cancel(); ticker = null
        cancelRetry()
        core.call("pause", "paused" to true)
        releasePlayer()
        phase = "idle"
        metrics(PlaybackStats())
    }
    suspend fun togglePause() = operations.withLock {
        userPaused = player?.playWhenReady ?: !userPaused
        cancelRetry()
        val command=core.call("pause", "paused" to userPaused)
        player?.playWhenReady = !userPaused
        if (!userPaused && selected && !terminal && player?.playbackState == Player.STATE_IDLE) {
            // A pause during backoff leaves a stopped player. Resume needs a fresh load.
            apply(core.call("reconnect"))
        } else apply(command)
        publishStats()
    }
    suspend fun reconnect() = operations.withLock {
        if (selected) { terminal = false; userPaused = false; apply(core.call("reconnect")) }
    }
    suspend fun stop() = operations.withLock {
        selected = false; terminal = false
        title = ""
        core.call("stop")
        releasePlayer()
        phase = "idle"
        metrics(PlaybackStats())
        status("Gestoppt")
    }
    suspend fun seek(deltaMs: Long) = operations.withLock {
        val p = player ?: return@withLock
        if (p.isCurrentMediaItemSeekable) {
            core.call("seek")
            val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
            p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration) else target)
            publishStats()
        }
    }
    suspend fun seekTo(positionMs: Long) = operations.withLock {
        val p = player ?: return@withLock
        if (!p.isCurrentMediaItemSeekable || live) return@withLock
        val token = attempt
        core.call("seek")
        if (player !== p || attempt != token) return@withLock
        p.seekTo(positionMs.coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE / 2))
        publishStats()
    }
    private suspend fun apply(command: JSONObject) {
        if (command.optString("action") != "load") {
            val previousPhase = phase
            phase = command.optString("phase", phase)
            if (phase == "waiting") {
                if (previousPhase != "waiting") player?.stop()
                scheduleRetry(command)
            } else cancelRetry()
            if (phase == "finished") { terminal = true }
            val label = when (phase) {
                "playing" -> "Wiedergabe"
                "buffering" -> "Puffer wird gefüllt"
                "connecting" -> "Verbinde …"
                "waiting" -> "Verbindung unterbrochen · Versuch ${command.optInt("retries")}"
                "paused" -> "Pausiert"
                "finished" -> "Wiedergabe beendet"
                else -> "Kanal auswählen"
            }
            status(if (title.isEmpty()) label else "$label · $title")
            return
        }
        if (!foreground) { return }
        releasePlayer()
        attempt = command.getLong("attempt")
        val token = attempt
        title = command.getString("name")
        phase = "connecting"
        live = command.getBoolean("live")
        ingress = IngressMeter().apply { sample(SystemClock.elapsedRealtime()) }
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false).setUserAgent(command.optString("user_agent", "IPTV-TV/0.1"))
        if (!command.isNull("referrer")) http.setDefaultRequestProperties(mapOf("Referer" to command.getString("referrer")))
        val meter = ingress
        val data = DefaultDataSource.Factory(context, http).setTransferListener(object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if (isNetwork) meter.add(bytesTransferred) }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        })
        val media = DefaultMediaSourceFactory(context).setDataSourceFactory(data)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
            })
        val mediaAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        allocator = mediaAllocator
        val buffers = bufferProfile.buffers(live)
        bufferTargetMs = buffers.maxMs
        val loadControl = DefaultLoadControl.Builder().setAllocator(mediaAllocator)
            .setBufferDurationsMs(buffers.minMs, buffers.maxMs, buffers.startMs, buffers.rebufferMs)
            .setTargetBufferBytes(buffers.bytes)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val p = ExoPlayer.Builder(context).setMediaSourceFactory(media).setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p || attempt != token) return
                val httpCode = generateSequence(error as Throwable) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val detail=when {
                    httpCode!=null->ImportErrors.describe(ImportFailure("http_$httpCode","playback",httpCode))
                    error.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED->ImportErrors.describe(ImportFailure("decoder_init_failed","playback"))
                    error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)->ImportErrors.describe(ImportFailure("format_unsupported","playback"))
                    else->ImportErrors.describe(error,"playback")
                }
                lastError=detail.json().put("media3_code",error.errorCode).put("cause_types",org.json.JSONArray(generateSequence(error as Throwable){it.cause}.take(12).map{it.javaClass.simpleName}.toList()))
                // Permanent errors need user action, rather than an endless credential/codec retry loop.
                if (httpCode in setOf(401, 403, 404, 410) || error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) {
                    terminal = true
                    phase = "error"
                    scope.launch { operations.withLock { if (player === p && attempt == token) core.call("pause", "paused" to true) } }
                    status(detail.message)
                } else scope.launch { operations.withLock { if (player === p && attempt == token) {apply(core.call("ended", "attempt" to token, "error" to true));status("${detail.message} · Neuer Versuch wird vorbereitet.")} } }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== p || attempt != token) return
                if (playbackState == Player.STATE_ENDED) scope.launch { operations.withLock { if (player === p && attempt == token) apply(core.call("ended", "attempt" to token, "error" to false)) } }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player === p && reason == Player.DISCONTINUITY_REASON_SEEK) scope.launch { operations.withLock { if (player === p && attempt == token) core.call("seek") } }
            }
        })
        session = MediaSession.Builder(context, p).build()
        view.player = p
        view.keepScreenOn = true
        p.setMediaItem(MediaItem.Builder().setUri(command.getString("url")).setMediaId(token.toString()).build())
        if (!command.isNull("resume")) p.seekTo((command.getDouble("resume") * 1_000).toLong())
        p.prepare()
        core.call("pause", "paused" to userPaused)
        if (player !== p || attempt != token) { return }
        p.playWhenReady = !userPaused
        status("Verbinde · $title")
        publishStats()
    }
    private fun cancelRetry() { retry?.cancel(); retry = null }
    private fun scheduleRetry(command: JSONObject) {
        if (retry?.isActive == true || !foreground || userPaused || terminal || !selected) return
        val token = attempt
        val waitMs = command.optLong("retry_in_ms", 1_000).coerceAtLeast(1)
        retry = scope.launch {
            delay(waitMs)
            operations.withLock {
                retry = null
                if (foreground && selected && !terminal && !userPaused && attempt == token) apply(core.call("tick"))
            }
        }
    }
    private fun publishStats(rate: Long? = ingress.sample(SystemClock.elapsedRealtime())) {
        val p = player
        val format = p?.videoFormat
        metrics(PlaybackStats(
            phase = if (p?.playbackState == Player.STATE_BUFFERING && phase == "playing") "buffering" else phase,
            bufferedMs = p?.totalBufferedDuration?.coerceAtLeast(0),
            bufferTargetMs = bufferTargetMs,
            ingressBytesPerSecond = rate.takeIf { ingress.hasData },
            allocatedBytes = allocator?.totalBytesAllocated,
            positionMs = p?.currentPosition?.coerceAtLeast(0) ?: 0,
            durationMs = p?.duration?.takeIf { it > 0 },
            playing = p?.playWhenReady == true && p.playbackState != Player.STATE_ENDED,
            seekable = p?.isCurrentMediaItemSeekable == true,
            live = live,
            format = if (format != null && format.width > 0 && format.height > 0) "${format.width} × ${format.height}" else "",
        ))
    }
    fun releasePlayer() {
        cancelRetry()
        view.player = null
        view.keepScreenOn = false
        session?.release(); session = null
        player?.release(); player = null
        allocator = null
    }
    fun release() { foreground = false; ticker?.cancel(); releasePlayer() }
}

