package de.iptv.tv

/** What a selection plays. Episodes are remembered under their show; the episode only as fields. */
data class NowPlaying(
    val token: Long,
    val source: String,
    val section: LibrarySection,
    val key: String,
    val name: String,
    val logo: String? = null,
    val seriesKey: String? = null,
    val seriesName: String? = null,
    val season: Long? = null,
    val episode: Long? = null,
) {
    /** Favourites and history both use the show for episodes, never the episode itself. */
    val libraryKey get() = seriesKey ?: key
}

/** One Media3 observation, always tagged with the selection that created the player. */
data class PlaybackSample(
    val token: Long,
    val positionMs: Long,
    val durationMs: Long?,
    val playing: Boolean,
    val event: Event,
) {
    enum class Event { TICK, PAUSE, ENDED, RELEASE }
}

/**
 * Turns player observations into history and progress without its own clock.
 * Only an actual playback start creates history; retries, pauses and resumes never duplicate it.
 */
class WatchTracker(private val library: UserLibrary) {
    companion object { const val SAVE_DELTA_MS = 10_000L }
    var current: NowPlaying? = null; private set
    private var started = false
    private var finished = false
    private var savedMs = -1L

    /** A new selection. The previous player has already reported its release under its own token. */
    fun begin(item: NowPlaying) {
        current = item; started = false; finished = false; savedMs = -1L
    }
    /** Stop attributing samples, e.g. when the playlist is replaced. */
    fun clear() { current = null }

    fun onSample(sample: PlaybackSample) {
        val item = current ?: return
        if (sample.token != item.token) return
        if (!started) {
            // Selection, opening details or a failed stream never count as watched.
            if (!sample.playing || sample.event != PlaybackSample.Event.TICK) return
            started = true
            library.recordStart(item.source, item.section, LibraryItem(item.libraryKey, item.seriesName ?: item.name, 0, 0, item.logo,
                episodeKey = item.key.takeIf { item.seriesKey != null }, season = item.season, episode = item.episode,
                episodeTitle = item.name.takeIf { item.seriesKey != null }))
        }
        // Live channels never store a position.
        if (item.section == LibrarySection.LIVE || finished) return
        val ended = sample.event == PlaybackSample.Event.ENDED
        val position = sample.positionMs.coerceAtLeast(0)
        val due = when (sample.event) {
            PlaybackSample.Event.TICK -> savedMs < 0 || kotlin.math.abs(position - savedMs) >= SAVE_DELTA_MS
            PlaybackSample.Event.ENDED -> true
            else -> position != savedMs
        }
        if (!due) return
        val stored = library.updateProgress(item.source, item.key, position / 1000.0, sample.durationMs?.takeIf { it > 0 }?.div(1000.0), ended)
        savedMs = position
        if (stored.done) finished = true
    }
}
