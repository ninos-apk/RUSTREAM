package de.iptv.tv

import androidx.annotation.StringRes

/** Live read-ahead and resume thresholds; VOD retains its larger download window. */
enum class PlaybackBufferProfile(
    val key: String, @param:StringRes val label: Int, val targetMs: Int,
    val startMs: Int, val rebufferMs: Int, val memoryMiB: Int,
) {
    SPORT("sport", R.string.buffer_sport, 1_000, 250, 500, 16),
    BALANCED("balanced", R.string.buffer_balanced, 3_000, 500, 1_000, 32),
    STABLE("stable", R.string.buffer_stable, 6_000, 1_000, 2_000, 64);

    fun buffers(live: Boolean): PlaybackBuffers = if (live)
        PlaybackBuffers(targetMs, targetMs, startMs, rebufferMs, memoryMiB * 1024 * 1024)
    else PlaybackBuffers(10_000, 30_000, 1_500, 3_000, 32 * 1024 * 1024)

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

data class PlaybackBuffers(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val bytes: Int)
