package de.iptv.tv

import org.junit.Assert.*
import org.junit.Test
import de.iptv.tv.PlaybackSample.Event

class WatchTrackerTest {
    private val source = "src"
    private val library = UserLibrary()
    private val tracker = WatchTracker(library)
    private fun movie(token: Long, key: String = "c:film") = NowPlaying(token, source, LibrarySection.MOVIES, key, "Film")
    private fun sample(token: Long, positionMs: Long, playing: Boolean = true, event: Event = Event.TICK, durationMs: Long? = 6_000_000) =
        tracker.onSample(PlaybackSample(token, positionMs, durationMs, playing, event))

    @Test fun onlyAnActualPlaybackStartCreatesHistory() {
        tracker.begin(movie(1))
        sample(1, 0, playing = false)
        sample(1, 0, playing = false, event = Event.RELEASE)
        assertEquals("selection or a failed stream is not history", 0, library.recentCount(source, LibrarySection.MOVIES))
        assertNull(library.progress(source, "c:film"))
        sample(1, 1_000)
        assertEquals(1, library.recentCount(source, LibrarySection.MOVIES))
    }

    @Test fun retriesPausesAndResumesNeverDuplicate() {
        tracker.begin(movie(1))
        sample(1, 1_000)
        sample(1, 2_000, playing = false, event = Event.PAUSE)
        sample(1, 2_000)
        sample(1, 3_000, event = Event.RELEASE) // retry reload
        sample(1, 4_000)
        assertEquals(1, library.recentCount(source, LibrarySection.MOVIES))
    }

    @Test fun progressIsThrottledButSavedOnPauseReleaseAndEnd() {
        var writes = 0
        library.listener = { writes++ }
        tracker.begin(movie(1))
        sample(1, 1_000) // start + first position
        val afterStart = writes
        for (position in 2_000L..10_000L step 1_000) sample(1, position)
        assertEquals("ticks below ten seconds of change are not stored", afterStart, writes)
        sample(1, 11_000)
        assertEquals(11.0, library.progress(source, "c:film")!!.positionSec, 0.0)
        sample(1, 13_500, playing = false, event = Event.PAUSE)
        assertEquals(13.5, library.progress(source, "c:film")!!.positionSec, 0.0)
        val beforeRelease = writes
        sample(1, 13_500, playing = false, event = Event.RELEASE)
        assertEquals("unchanged position is not written twice", beforeRelease, writes)
        sample(1, 20_000, event = Event.RELEASE)
        assertEquals(20.0, library.progress(source, "c:film")!!.positionSec, 0.0)
        sample(1, 6_000_000, event = Event.ENDED)
        assertTrue(library.progress(source, "c:film")!!.done)
        sample(1, 0, event = Event.RELEASE)
        assertTrue("a release after the end keeps it finished", library.progress(source, "c:film")!!.done)
    }

    @Test fun staleTokensNeverWriteUnderTheNewItem() {
        tracker.begin(movie(1, "c:old"))
        sample(1, 500_000)
        tracker.begin(movie(2, "c:new"))
        sample(1, 900_000, event = Event.RELEASE)
        sample(1, 950_000)
        assertNull(library.progress(source, "c:new"))
        assertEquals(500.0, library.progress(source, "c:old")!!.positionSec, 0.0)
        sample(2, 1_000)
        assertEquals(1.0, library.progress(source, "c:new")!!.positionSec, 0.0)
    }

    @Test fun liveStoresOnlyTheChannel() {
        tracker.begin(NowPlaying(1, source, LibrarySection.LIVE, "c:live", "News", logo = "https://logo.test/n.png"))
        sample(1, 1_000, durationMs = null)
        sample(1, 60_000, durationMs = null)
        sample(1, 61_000, playing = false, event = Event.PAUSE, durationMs = null)
        val recent = library.recent(source, LibrarySection.LIVE).single()
        assertEquals("c:live", recent.key)
        assertEquals("https://logo.test/n.png", recent.logo)
        assertNull(library.progress(source, "c:live"))
    }

    @Test fun episodesUpdateTheirShowEntry() {
        fun episode(token: Long, key: String, number: Long) =
            NowPlaying(token, source, LibrarySection.SERIES, key, "Episode $number", seriesKey = "s:m3u:show", seriesName = "Show", season = 1, episode = number)
        tracker.begin(episode(1, "c:e1", 1)); sample(1, 1_000); sample(1, 30_000, event = Event.RELEASE)
        tracker.begin(episode(2, "c:e2", 2)); sample(2, 1_000)
        val entry = library.recent(source, LibrarySection.SERIES).single()
        assertEquals("s:m3u:show", entry.key)
        assertEquals("Show", entry.name)
        assertEquals("c:e2", entry.episodeKey)
        assertEquals("Episode 2", entry.episodeTitle)
        assertEquals(30.0, library.progress(source, "c:e1")!!.positionSec, 0.0)
        assertNull("history is never stored per episode", library.progress(source, "s:m3u:show"))
        assertEquals("s:m3u:show", episode(3, "c:e3", 3).libraryKey)
    }

    @Test fun clearedTrackerIgnoresEverything() {
        tracker.begin(movie(1))
        tracker.clear()
        sample(1, 5_000)
        assertEquals(0, library.recentCount(source, LibrarySection.MOVIES))
    }
}
