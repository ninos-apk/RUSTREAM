package de.iptv.tv

/** Live read-ahead and resume thresholds; VOD retains its larger download window. */
enum class PlaybackBufferProfile(
    val key: String, val label: String, val targetMs: Int,
    val startMs: Int, val rebufferMs: Int, val memoryMiB: Int,
) {
    SPORT("sport", "Sport · 1 s / 16 MiB", 1_000, 250, 500, 16),
    BALANCED("balanced", "Ausgewogen · 3 s / 32 MiB", 3_000, 500, 1_000, 32),
    STABLE("stable", "Stabil · 6 s / 64 MiB", 6_000, 1_000, 2_000, 64);

    fun buffers(live: Boolean): PlaybackBuffers = if (live)
        PlaybackBuffers(targetMs, targetMs, startMs, rebufferMs, memoryMiB * 1024 * 1024)
    else PlaybackBuffers(10_000, 30_000, 1_500, 3_000, 32 * 1024 * 1024)

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

data class PlaybackBuffers(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val bytes: Int)
