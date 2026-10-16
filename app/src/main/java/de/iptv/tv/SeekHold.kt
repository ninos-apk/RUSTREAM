package de.iptv.tv

/** Time-based seeking: remote repeat frequency never changes the distance travelled. */
class SeekHold {
    companion object {
        const val TAP_MS = 30_000L
        const val HOLD_MS = 400L
        private const val BASE_SPEED = 30_000.0
        private const val ACCELERATION = 20_000.0
        private const val MAX_SPEED = 180_000.0
    }
    var direction = 0; private set
    var targetMs = 0L; private set
    private var startMs = 0L
    private var originMs = 0L
    private var limitMs = Long.MAX_VALUE / 2
    val active get() = direction != 0

    fun begin(value: Int, nowMs: Long, positionMs: Long, durationMs: Long?): Long? {
        require(value == -1 || value == 1)
        if (direction == value) return null
        val position = if (active) targetMs else positionMs
        limitMs = durationMs?.takeIf { it > 0 } ?: (Long.MAX_VALUE / 2)
        direction = value
        startMs = nowMs
        originMs = (position + value * TAP_MS).coerceIn(0, limitMs)
        targetMs = originMs
        return targetMs
    }

    fun speed(nowMs: Long): Double {
        val seconds = ((nowMs - startMs - HOLD_MS).coerceAtLeast(0)) / 1000.0
        return (BASE_SPEED + ACCELERATION * seconds).coerceAtMost(MAX_SPEED) / 1000.0
    }

    fun advance(nowMs: Long): Long? {
        if (!active || nowMs - startMs <= HOLD_MS) return null
        val seconds = (nowMs - startMs - HOLD_MS) / 1000.0
        val ramp = ((MAX_SPEED - BASE_SPEED) / ACCELERATION).coerceAtMost(seconds)
        val distance = BASE_SPEED * ramp + 0.5 * ACCELERATION * ramp * ramp + MAX_SPEED * (seconds - ramp)
        val next = (originMs.toDouble() + direction * distance).coerceIn(0.0, limitMs.toDouble()).toLong()
        if (next == targetMs) return null
        targetMs = next
        return next
    }

    fun cancel() { direction = 0 }
}
