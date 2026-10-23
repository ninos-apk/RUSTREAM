package de.iptv.tv

/** Detects a stalled VOD download even while the existing reserve is still playing. */
class VodRefillWatchdog {
    private var idleSince: Long? = null
    private var previousBytes = 0L
    private var previousCacheEnd = 0L

    fun reset() { idleSince = null; previousBytes = 0; previousCacheEnd = 0 }

    fun shouldRetry(
        nowMs: Long, active: Boolean, positionMs: Long, bufferedMs: Long,
        cacheEndMs: Long, durationMs: Long?, receivedBytes: Long,
    ): Boolean {
        val complete = durationMs != null && durationMs > 0 && cacheEndMs >= durationMs - 500
        val progress = receivedBytes > previousBytes || cacheEndMs > previousCacheEnd
        previousBytes = receivedBytes
        previousCacheEnd = cacheEndMs
        if (!active || complete || bufferedMs >= 60_000 || (durationMs != null && positionMs >= durationMs)) {
            idleSince = null
            return false
        }
        if (progress || idleSince == null) idleSince = nowMs
        if (nowMs - checkNotNull(idleSince) < 10_000) return false
        idleSince = nowMs
        return true
    }
}
