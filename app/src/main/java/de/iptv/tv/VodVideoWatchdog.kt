package de.iptv.tv

/** Detect a running media clock with no video submissions. Main-thread samples, once per second. */
internal class VodVideoWatchdog {
    private var lastSampleMs = -1L
    private var lastPositionMs = 0L
    private var lastFrames = 0L
    private var lastFrameMs = 0L
    private var lastFramePositionMs = 0L
    private var healthySinceMs: Long? = null
    private var attempts = 0

    // Preserve the retry limit across the buffering/discontinuity caused by our own reload.
    fun interrupt() { lastSampleMs = -1; healthySinceMs = null }

    fun shouldReload(nowMs: Long, positionMs: Long, frames: Long, active: Boolean): Boolean {
        if (!active) { interrupt(); return false }
        val continuous = lastSampleMs >= 0 && nowMs - lastSampleMs in 1..3_000 && positionMs > lastPositionMs
        val newFrames = frames > lastFrames
        lastSampleMs = nowMs
        lastPositionMs = positionMs
        lastFrames = frames
        if (!continuous || newFrames) {
            lastFrameMs = nowMs
            lastFramePositionMs = positionMs
        }
        if (continuous && newFrames) {
            val since = healthySinceMs ?: nowMs
            healthySinceMs = since
            if (nowMs - since >= 5_000) attempts = 0
        } else {
            healthySinceMs = null
        }
        if (attempts >= 2 || nowMs - lastFrameMs < 8_000 || positionMs - lastFramePositionMs < 4_000) return false
        attempts++
        interrupt()
        return true
    }
}
