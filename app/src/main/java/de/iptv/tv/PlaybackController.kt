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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

/** All Media3 calls stay on the main looper. Rust exclusively owns session-level retries. */
class PlaybackController(
    private val context: Context,
    private val core: Core,
    private val scope: CoroutineScope,
    private val view: PlayerView,
    private val status: (String) -> Unit,
    private val metrics: (PlaybackStats) -> Unit = {},
    private val log: DiagnosticLog = AppDiagnostics.log,
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
    private val refillWatchdog = VodRefillWatchdog()
    private val videoWatchdog = LiveVideoWatchdog()
    private var videoFrames = AtomicLong()
    private val recoveryEvents = ArrayDeque<JSONObject>()
    private var lastCoreHealth = JSONObject()
    private var lastIngressRate: Long? = null
    private var recoveryPending = false
    private var live = false
    private var bufferTargetMs = PlaybackBufferProfile.BALANCED.targetMs
    /** Increments per selection; the player created for it reports samples under this token. */
    private var selection = 0L
    private var playerSelection = 0L
    /** History/progress observer. Runs on the main thread and must not block. */
    var onSample: (PlaybackSample) -> Unit = {}

    /** Returns the selection token used for this item's samples. */
    suspend fun select(revision: Long, id: Long, resumeSec: Double? = null): Long = operations.withLock {
        val command = core.call("select", "revision" to revision, "id" to id, "resume" to resumeSec)
        selected = true; terminal = false; userPaused = false
        lastError=null
        recoveryEvents.clear()
        lastCoreHealth = JSONObject()
        recoveryPending = false
        val token = ++selection
        apply(command)
        token
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
                            val now = SystemClock.elapsedRealtime()
                            val rate = ingress.sample(now)
                            lastIngressRate = rate
                            val frames = videoFrames.get()
                            apply(core.call("poll", "attempt" to attempt, "paused" to suspended,
                                "position" to current.currentPosition.coerceAtLeast(0) / 1000.0,
                                "buffered" to current.totalBufferedDuration.coerceAtLeast(0) / 1000.0,
                                "cache_end" to current.bufferedPosition.coerceAtLeast(0) / 1000.0,
                                "buffering" to (current.playbackState == Player.STATE_BUFFERING), "ingress" to rate,
                                "video_frames" to frames.takeIf { live && current.videoFormat != null }))
                            if (player === current && videoWatchdog.shouldRetry(now,
                                    active = live && !suspended && phase == "playing" && current.isPlaying &&
                                        current.videoFormat != null && view.isShown,
                                    frames = frames)) {
                                recordRecovery("video_frame_stall", rate)
                                apply(core.call("ended", "attempt" to attempt, "error" to true))
                            }
                            if (player === current && refillWatchdog.shouldRetry(
                                    SystemClock.elapsedRealtime(),
                                    active = !live && !suspended && phase in setOf("playing", "buffering"),
                                    positionMs = current.currentPosition.coerceAtLeast(0),
                                    bufferedMs = current.totalBufferedDuration.coerceAtLeast(0),
                                    cacheEndMs = current.bufferedPosition.coerceAtLeast(0),
                                    durationMs = current.duration.takeIf { it > 0 },
                                    receivedBytes = ingress.totalBytes,
                                )) {
                                // Rust owns the backoff and preserves the position reported above.
                                recordRecovery("vod_refill_stall", rate)
                                apply(core.call("ended", "attempt" to attempt, "error" to true))
                            }
                            publishStats(rate)
                            if (player === current) sample(current, PlaybackSample.Event.TICK)
                        } else {
                            if (!terminal) apply(core.call("tick"))
                            publishStats()
                        }
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    log.failure("Playback",DiagnosticMessage.OPERATION_FAILED,ImportErrors.describe(error,"playback_control"),error,
                        "attempt" to attempt)
                    status(context.getString(R.string.playback_control_failed))
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
        log.record(DiagnosticLevel.INFO,"Playback",DiagnosticMessage.PLAYBACK_STATE,"phase" to "suspended","attempt" to attempt)
    }
    suspend fun togglePause() = operations.withLock {
        userPaused = player?.playWhenReady ?: !userPaused
        cancelRetry()
        val command=core.call("pause", "paused" to userPaused)
        player?.playWhenReady = !userPaused
        if (userPaused) player?.let { sample(it, PlaybackSample.Event.PAUSE) }
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
        val wasSelected = selected
        selected = false; terminal = false
        title = ""
        core.call("stop")
        releasePlayer()
        phase = "idle"
        metrics(PlaybackStats())
        status(context.getString(R.string.playback_stopped))
        if (wasSelected) log.record(DiagnosticLevel.INFO,"Playback",DiagnosticMessage.PLAYBACK_STATE,"phase" to "stopped","attempt" to attempt)
    }
    suspend fun seek(deltaMs: Long) = operations.withLock {
        val p = player ?: return@withLock
        if (p.isCurrentMediaItemSeekable) {
            refillWatchdog.reset()
            core.call("seek")
            val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
            p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration) else target)
            publishStats()
        }
    }
    suspend fun seekTo(positionMs: Long) = operations.withLock {
        val p = player ?: return@withLock
        if (!p.isCurrentMediaItemSeekable || live) return@withLock
        refillWatchdog.reset()
        val token = attempt
        core.call("seek")
        if (player !== p || attempt != token) return@withLock
        p.seekTo(positionMs.coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE / 2))
        publishStats()
    }
    private suspend fun apply(command: JSONObject) {
        if (command.optString("action") != "load") {
            val previousPhase = phase
            lastCoreHealth = command
            phase = command.optString("phase", phase)
            if (phase != previousPhase) log.record(DiagnosticLevel.INFO,"Playback",DiagnosticMessage.PLAYBACK_STATE,
                "phase" to phase,"attempt" to attempt,"buffered_ms" to player?.totalBufferedDuration)
            if (phase == "waiting") {
                if (previousPhase != "waiting") {
                    recordRecovery(command.optString("recovery_reason", "unknown"))
                    recoveryPending = true
                    player?.stop()
                }
                scheduleRetry(command)
            } else cancelRetry()
            if (phase == "playing" && recoveryPending) {
                recordRecovery("recovered")
                recoveryPending = false
            }
            if (phase == "finished") { terminal = true }
            val label = when (phase) {
                "playing" -> context.getString(R.string.playback_playing)
                "buffering" -> context.getString(R.string.playback_buffering)
                "connecting" -> context.getString(R.string.playback_connecting)
                "waiting" -> context.getString(R.string.playback_waiting, command.optInt("retries"))
                "paused" -> context.getString(R.string.playback_paused)
                "finished" -> context.getString(R.string.playback_finished)
                else -> context.getString(R.string.playback_idle)
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
        log.record(DiagnosticLevel.INFO,"Playback",DiagnosticMessage.PLAYBACK_STARTED,
            "attempt" to attempt,"live" to live,"profile" to bufferProfile.key)
        ingress = IngressMeter().apply { sample(SystemClock.elapsedRealtime()) }
        lastIngressRate = null
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
            .setLoadErrorHandlingPolicy(PlaybackLoadErrorPolicy(live))
        val mediaAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        allocator = mediaAllocator
        val buffers = bufferProfile.buffers(live)
        bufferTargetMs = buffers.maxMs
        val loadControl = DefaultLoadControl.Builder().setAllocator(mediaAllocator)
            .setBufferDurationsMs(buffers.minMs, buffers.maxMs, buffers.startMs, buffers.rebufferMs)
            .setTargetBufferBytes(buffers.bytes)
            // VOD must keep loading below 60 seconds even at the byte target.
            .setPrioritizeTimeOverSizeThresholds(!live).build()
        val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(media).setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        player = p
        playerSelection = selection
        val frameCounter = AtomicLong()
        videoFrames = frameCounter
        p.setVideoFrameMetadataListener { _, _, _, _ -> frameCounter.incrementAndGet() }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p || attempt != token) return
                val httpCode = generateSequence(error as Throwable) { it.cause }
                    .take(12)
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val detail=when {
                    httpCode!=null->ImportErrors.describe(ImportFailure("http_$httpCode","playback",httpCode))
                    error.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED->ImportErrors.describe(ImportFailure("decoder_init_failed","playback"))
                    error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)->ImportErrors.describe(ImportFailure("format_unsupported","playback"))
                    else->ImportErrors.describe(error,"playback")
                }
                lastError=detail.json().put("media3_code",error.errorCode).put("media3_name",error.errorCodeName)
                    .put("attempt",token).put("time_ms",System.currentTimeMillis())
                    .put("cause_types",org.json.JSONArray(generateSequence(error as Throwable){it.cause}.take(12).map{it.javaClass.simpleName}.toList()))
                log.failure("Playback",DiagnosticMessage.PLAYBACK_FAILED,detail,error,
                    "attempt" to token,"media3_code" to error.errorCode,"media3_name" to error.errorCodeName,
                    "terminal" to PlaybackRecoveryPolicy.terminal(live,httpCode,error.errorCode),
                    "buffered_ms" to p.totalBufferedDuration,"position_ms" to p.currentPosition,
                    "received_bytes" to ingress.totalBytes,"ingress_bytes_per_second" to lastIngressRate)
                // Permanent errors need user action, rather than an endless credential/codec retry loop.
                if (PlaybackRecoveryPolicy.terminal(live, httpCode, error.errorCode)) {
                    terminal = true
                    phase = "error"
                    scope.launch { operations.withLock { if (player === p && attempt == token) core.call("pause", "paused" to true) } }
                    status(detail.message(context))
                } else scope.launch { operations.withLock { if (player === p && attempt == token) {apply(core.call("ended", "attempt" to token, "error" to true));status(context.getString(R.string.playback_retrying, detail.message(context)))} } }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== p || attempt != token) return
                log.record(DiagnosticLevel.INFO,"Playback",DiagnosticMessage.PLAYBACK_STATE,
                    "attempt" to token,"player_state" to playbackState,
                    "phase" to when(playbackState) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; Player.STATE_ENDED -> "ended"; else -> "idle" },
                    "buffered_ms" to p.totalBufferedDuration)
                if (playbackState == Player.STATE_ENDED) sample(p, PlaybackSample.Event.ENDED)
                if (playbackState == Player.STATE_ENDED) scope.launch { operations.withLock { if (player === p && attempt == token) apply(core.call("ended", "attempt" to token, "error" to false)) } }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (player === p && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    refillWatchdog.reset()
                    scope.launch { operations.withLock { if (player === p && attempt == token) core.call("seek") } }
                }
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
        status(context.getString(R.string.playback_connecting_title, title))
        publishStats()
    }
    private fun cancelRetry() { retry?.cancel(); retry = null }
    private fun scheduleRetry(command: JSONObject) {
        if (retry?.isActive == true || !foreground || userPaused || terminal || !selected) return
        val token = attempt
        val waitMs = command.optLong("retry_in_ms", 1_000).coerceAtLeast(1)
        log.record(DiagnosticLevel.WARN,"Recovery",DiagnosticMessage.RETRY_SCHEDULED,
            "attempt" to token,"retry_in_ms" to waitMs,"retries" to command.optInt("retries"),
            "reason" to command.optString("recovery_reason","unknown"))
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
    /** Bounded, credential-free snapshots for distinguishing transport and renderer stalls. */
    private fun recordRecovery(reason: String, rate: Long? = lastIngressRate) {
        val current = player
        val event = JSONObject().put("reason", reason).put("attempt", attempt).put("time_ms",System.currentTimeMillis())
            .put("elapsed_ms", SystemClock.elapsedRealtime()).put("buffered_ms", current?.totalBufferedDuration)
            .put("player_state", current?.playbackState).put("position_ms", current?.currentPosition)
            .put("received_bytes", ingress.totalBytes).put("ingress_bytes_per_second", rate)
            .put("video_frames", videoFrames.get()).put("media3_code", lastError?.optInt("media3_code"))
        recoveryEvents.addLast(event)
        log.record(if(reason=="recovered") DiagnosticLevel.INFO else DiagnosticLevel.WARN,"Recovery",
            if(reason=="recovered") DiagnosticMessage.RECOVERED else DiagnosticMessage.RECOVERY,
            "reason" to reason,"attempt" to attempt,"buffered_ms" to current?.totalBufferedDuration,
            "player_state" to current?.playbackState,"position_ms" to current?.currentPosition,
            "received_bytes" to ingress.totalBytes,"ingress_bytes_per_second" to rate,"video_frames" to videoFrames.get(),
            "media3_code" to lastError?.opt("media3_code"))
        while (recoveryEvents.size > 20) recoveryEvents.removeFirst()
    }
    fun diagnostics(): JSONObject = JSONObject().put("profile", bufferProfile.key).put("live", live)
        .put("phase", phase).put("core", lastCoreHealth).put("recoveries", JSONArray(recoveryEvents.toList()))
    /** Position and duration come from Media3 only; the observer never sees a recycled player. */
    private fun sample(p: ExoPlayer, event: PlaybackSample.Event) {
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 && !live }
        try {
            onSample(PlaybackSample(playerSelection, p.currentPosition.coerceAtLeast(0), duration, p.isPlaying, event))
        } catch (error: Exception) {
            // History must never interrupt playback.
            lastError = JSONObject().put("history_error", error.javaClass.simpleName)
            log.failure("Storage",DiagnosticMessage.STORAGE_FAILED,ImportErrors.describe(error,"record_history"),error,
                "attempt" to attempt)
        }
    }
    fun releasePlayer() {
        refillWatchdog.reset()
        videoWatchdog.reset()
        cancelRetry()
        // Last position of this player, still under the token of the selection that created it.
        player?.let { sample(it, PlaybackSample.Event.RELEASE) }
        view.player = null
        view.keepScreenOn = false
        session?.release(); session = null
        player?.release(); player = null
        allocator = null
    }
    fun release() { foreground = false; ticker?.cancel(); releasePlayer() }
}

