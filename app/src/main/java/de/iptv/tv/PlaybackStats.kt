package de.iptv.tv

import java.util.concurrent.atomic.AtomicLong

/** Transfer callbacks only publish byte counts; sampling and UI remain on the main thread. */
class IngressMeter {
    private val bytes = AtomicLong()
    private var lastBytes = 0L
    private var lastTime: Long? = null
    val hasData get() = bytes.get() > 0
    fun add(count: Int) { if (count > 0) bytes.addAndGet(count.toLong()) }
    fun sample(nowMs: Long): Long? {
        val current = bytes.get()
        val previousTime = lastTime
        if (previousTime == null) { lastTime = nowMs; lastBytes = current; return null }
        val elapsed = nowMs - previousTime
        if (elapsed <= 0) return null
        val rate = ((current - lastBytes).coerceAtLeast(0) * 1000L / elapsed)
        lastBytes = current; lastTime = nowMs
        return rate
    }
}

data class PlaybackStats(
    val phase: String = "idle",
    val bufferedMs: Long? = null,
    val bufferTargetMs: Int = 3_000,
    val ingressBytesPerSecond: Long? = null,
    val allocatedBytes: Int? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val playing: Boolean = false,
    val seekable: Boolean = false,
    val live: Boolean = false,
    val format: String = "",
)
