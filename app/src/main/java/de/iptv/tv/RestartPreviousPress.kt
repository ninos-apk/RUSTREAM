package de.iptv.tv

/** Only a second distinct press within this window requests the previous episode. */
class RestartPreviousPress {
    private var restartedAt: Long? = null

    fun previous(nowMs: Long, hasPrevious: Boolean): Boolean {
        val elapsed = restartedAt?.let { nowMs - it }
        if (hasPrevious && elapsed != null && elapsed in 0..10_000L) {
            reset()
            return true
        }
        restartedAt = nowMs
        return false
    }

    fun reset() { restartedAt = null }
}
