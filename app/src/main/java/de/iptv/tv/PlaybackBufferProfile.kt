package de.iptv.tv

import androidx.annotation.StringRes

/** Byte targets bound read-ahead above the minimum time reserve, not the whole process. */
enum class PlaybackBufferProfile(
    val key: String, @param:StringRes val label: Int, val targetMs: Int,
    val startMs: Int, val rebufferMs: Int, val bytes: Int,
) {
    SPORT("sport", R.string.buffer_sport, 5_000, 1_000, 2_000, 16 * 1024 * 1024),
    BALANCED("balanced", R.string.buffer_balanced, 10_000, 1_500, 3_000, 32 * 1024 * 1024),
    STABLE("stable", R.string.buffer_stable, 20_000, 2_000, 5_000, 64 * 1024 * 1024);

    fun buffers(live: Boolean): PlaybackBuffers = if (live)
        PlaybackBuffers(targetMs, targetMs, startMs, rebufferMs, bytes)
    else PlaybackBuffers(60_000, 120_000, 1_500, 3_000, 120_000_000)

    companion object {
        fun fromKey(key: String?) = when (key) {
            "custom_10" -> BALANCED
            "custom_15", "custom_20" -> STABLE
            else -> entries.firstOrNull { it.key == key } ?: BALANCED
        }
    }
}

data class PlaybackBuffers(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val bytes: Int)
