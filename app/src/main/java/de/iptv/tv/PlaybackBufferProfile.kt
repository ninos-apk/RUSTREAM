package de.iptv.tv

import androidx.annotation.StringRes

/** Byte targets limit media read-ahead; they are not a limit on the whole player process. */
enum class PlaybackBufferProfile(
    val key: String, @param:StringRes val label: Int, val targetMs: Int,
    val startMs: Int, val rebufferMs: Int, val bytes: Int,
    val custom: Boolean = false,
) {
    SPORT("sport", R.string.buffer_sport, 1_000, 250, 500, 16 * 1024 * 1024),
    BALANCED("balanced", R.string.buffer_balanced, 3_000, 500, 1_000, 32 * 1024 * 1024),
    STABLE("stable", R.string.buffer_stable, 6_000, 1_000, 2_000, 64 * 1024 * 1024),
    CUSTOM_10("custom_10", R.string.buffer_custom_10, 10_000, 1_000, 2_000, 60_000_000, true),
    CUSTOM_15("custom_15", R.string.buffer_custom_15, 15_000, 1_000, 2_000, 90_000_000, true),
    CUSTOM_20("custom_20", R.string.buffer_custom_20, 20_000, 1_000, 2_000, 120_000_000, true);

    fun buffers(live: Boolean): PlaybackBuffers = if (live)
        PlaybackBuffers(targetMs, targetMs, startMs, rebufferMs, bytes)
    else PlaybackBuffers(60_000, 120_000, 1_500, 3_000, 120_000_000)

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

data class PlaybackBuffers(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val bytes: Int)
