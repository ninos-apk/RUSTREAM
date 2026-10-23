package de.iptv.tv

/** Frame submission is independent of an audio clock that can advance with frozen video.
 * Inactive samples (pause, buffering, hidden surface or audio-only) discard the deadline. */
class LiveVideoWatchdog {
    private var previousFrames = 0L
    private var idleSince: Long? = null

    fun reset() { previousFrames = 0; idleSince = null }

    fun shouldRetry(nowMs: Long, active: Boolean, frames: Long): Boolean {
        val progressed = frames != previousFrames
        previousFrames = frames
        if (!active) { idleSince = null; return false }
        if (progressed || idleSince == null) idleSince = nowMs
        if (nowMs - checkNotNull(idleSince) < 12_000) return false
        idleSince = nowMs
        return true
    }
}
