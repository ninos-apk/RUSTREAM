package de.iptv.tv

/** Count time spent playing without frames across intermittent live refills. */
class LiveVideoWatchdog {
    private var previousFrames = 0L
    private var playingSinceMs: Long? = null
    private var stalledPlayingMs = 0L
    var lastFrameProgressMs: Long? = null
        private set

    fun reset() {
        previousFrames = 0
        playingSinceMs = null
        stalledPlayingMs = 0
        lastFrameProgressMs = null
    }

    fun shouldRetry(nowMs: Long, active: Boolean, frames: Long, buffering: Boolean = false): Boolean {
        if (!active) {
            reset()
            previousFrames = frames
            return false
        }
        val progressed = frames != previousFrames
        previousFrames = frames
        if (progressed) {
            lastFrameProgressMs = nowMs
            stalledPlayingMs = 0
        } else {
            playingSinceMs?.let { stalledPlayingMs += nowMs - it }
        }
        playingSinceMs = nowMs.takeUnless { buffering }
        if (buffering || stalledPlayingMs < 12_000) return false
        stalledPlayingMs = 0
        return true
    }
}

/** Two consecutive advancing samples on the same output reject a single poster frame. */
internal class LiveRecoveryConfirmation {
    private var lastProgress: Long? = null
    private var wasVideo = false
    var firstAdvanceMs: Long? = null
        private set

    fun reset() { lastProgress = null; firstAdvanceMs = null }

    fun confirmed(nowMs: Long, renderedFrames: Int?, radioPositionMs: Long?): Boolean {
        val video = renderedFrames != null
        val progress = renderedFrames?.toLong() ?: radioPositionMs
        val previous = lastProgress
        lastProgress = progress
        val advanced = video == wasVideo && progress != null && previous != null && progress > previous
        wasVideo = video
        if (!advanced) { firstAdvanceMs = null; return false }
        if (firstAdvanceMs != null) return true
        firstAdvanceMs = nowMs
        return false
    }
}
