package de.iptv.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserLibraryTest {
    private var now = 1_000L
    private fun library() = UserLibrary { now }
    private fun item(key: String, name: String = key) = LibraryItem(key, name, 0, 0)
    private val source = "source-a"

    @Test fun historyKeepsFifteenNewestFirstWithoutDuplicates() {
        val library = library()
        for (i in 1..16) library.recordStart(source, LibrarySection.MOVIES, item("m$i"))
        val recent = library.recent(source, LibrarySection.MOVIES)
        assertEquals(15, recent.size)
        assertEquals("m16", recent.first().key)
        assertFalse("oldest entry is evicted", recent.any { it.key == "m1" })
        library.recordStart(source, LibrarySection.MOVIES, item("m5"))
        val again = library.recent(source, LibrarySection.MOVIES)
        assertEquals(15, again.size)
        assertEquals("m5", again.first().key)
        assertEquals(1, again.count { it.key == "m5" })
    }

    @Test fun equalTimestampsKeepADeterministicOrder() {
        val library = library()
        library.recordStart(source, LibrarySection.LIVE, item("a"))
        library.recordStart(source, LibrarySection.LIVE, item("b"))
        library.recordStart(source, LibrarySection.LIVE, item("a"))
        val recent = library.recent(source, LibrarySection.LIVE)
        assertEquals(listOf("a", "b"), recent.map { it.key })
        assertTrue(recent[0].seq > recent[1].seq)
        assertEquals(recent[0].at, recent[1].at)
    }

    @Test fun limitIsConfigurableAndTrimsImmediately() {
        val library = library()
        library.historyLimit = 30
        for (i in 1..25) library.recordStart(source, LibrarySection.LIVE, item("c$i"))
        assertEquals(25, library.recentCount(source, LibrarySection.LIVE))
        library.historyLimit = 5
        assertEquals(listOf("c25", "c24", "c23", "c22", "c21"), library.recent(source, LibrarySection.LIVE).map { it.key })
        library.recordStart(source, LibrarySection.LIVE, item("new"))
        assertEquals(5, library.recentCount(source, LibrarySection.LIVE))
    }

    @Test fun sectionsAndSourcesAreSeparate() {
        val library = library()
        library.recordStart(source, LibrarySection.LIVE, item("x"))
        library.recordStart("source-b", LibrarySection.LIVE, item("y"))
        library.recordStart(source, LibrarySection.MOVIES, item("z"))
        assertEquals(listOf("x"), library.recent(source, LibrarySection.LIVE).map { it.key })
        assertEquals(listOf("y"), library.recent("source-b", LibrarySection.LIVE).map { it.key })
        library.removeSource("source-b")
        assertTrue(library.recent("source-b", LibrarySection.LIVE).isEmpty())
        assertEquals(1, library.recentCount(source, LibrarySection.MOVIES))
    }

    @Test fun favouritesHistoryAndProgressAreIndependent() {
        val library = library()
        library.recordStart(source, LibrarySection.MOVIES, item("film"))
        library.updateProgress(source, "film", 600.0, 6000.0, false)
        assertTrue(library.toggleFavorite(source, LibrarySection.MOVIES, "film", "Film"))
        assertTrue(library.isFavorite(source, LibrarySection.MOVIES, "film"))
        // Removing the favourite keeps history and progress.
        assertFalse(library.toggleFavorite(source, LibrarySection.MOVIES, "film", "Film"))
        assertFalse(library.isFavorite(source, LibrarySection.MOVIES, "film"))
        assertEquals(1, library.recentCount(source, LibrarySection.MOVIES))
        assertEquals(600.0, library.progress(source, "film")!!.positionSec, 0.0)
        // Removing history keeps the favourite and progress.
        library.toggleFavorite(source, LibrarySection.MOVIES, "film", "Film")
        library.removeRecent(source, LibrarySection.MOVIES, "film")
        assertTrue(library.isFavorite(source, LibrarySection.MOVIES, "film"))
        assertNotNull(library.progress(source, "film"))
        // Eviction from history keeps progress as well.
        for (i in 1..20) library.recordStart(source, LibrarySection.MOVIES, item("other$i"))
        assertNotNull(library.progress(source, "film"))
    }

    @Test fun favouritesNeverDuplicateAndNewestComesFirst() {
        val library = library()
        library.toggleFavorite(source, LibrarySection.LIVE, "a", "A")
        library.toggleFavorite(source, LibrarySection.LIVE, "b", "B")
        library.toggleFavorite(source, LibrarySection.LIVE, "a", "A")
        library.toggleFavorite(source, LibrarySection.LIVE, "a", "A")
        assertEquals(listOf("a", "b"), library.favorites(source, LibrarySection.LIVE).map { it.key })
    }

    @Test fun aSeriesIsOneEntryWithItsLastEpisodeAndPerEpisodeProgress() {
        val library = library()
        fun episode(key: String, season: Long, number: Long) = LibraryItem("s:m3u:show", "Show", 0, 0,
            episodeKey = key, season = season, episode = number, episodeTitle = "Show S${season}E$number")
        library.recordStart(source, LibrarySection.SERIES, episode("c:e1", 1, 1))
        library.updateProgress(source, "c:e1", 1200.0, 1300.0, false)
        library.recordStart(source, LibrarySection.SERIES, episode("c:e2", 1, 2))
        library.updateProgress(source, "c:e2", 300.0, 1300.0, false)
        val recent = library.recent(source, LibrarySection.SERIES)
        assertEquals(1, recent.size)
        assertEquals("c:e2", recent[0].episodeKey)
        assertEquals("S1 · E2", recent[0].episodeLabel)
        assertEquals(1200.0, library.progress(source, "c:e1")!!.positionSec, 0.0)
        assertEquals(300.0, library.progress(source, "c:e2")!!.resumeSec!!, 0.0)
        assertEquals("c:e2", library.lastEpisode(source, "s:m3u:show")!!.episodeKey)
    }

    @Test fun completionRulesAndResumePositions() {
        val library = library()
        assertTrue(library.updateProgress(source, "ended", 10.0, 6000.0, true).done)
        // 95 % watched with two minutes or less remaining counts as finished.
        assertTrue(library.updateProgress(source, "credits", 5890.0, 6000.0, false).done)
        // 95 % of a long film can still leave several minutes: not finished.
        assertFalse(library.updateProgress(source, "long", 9600.0, 10000.0, false).done)
        assertNull("finished content starts at the beginning", library.progress(source, "ended")!!.resumeSec)
        assertNull("a few seconds are not worth resuming", library.updateProgress(source, "short", 4.0, 6000.0, false).resumeSec)
        val unknown = library.updateProgress(source, "stream", 754.5, null, false)
        assertEquals(754.5, unknown.resumeSec!!, 0.0)
        assertNull("unknown duration has no percentage", unknown.fraction)
        assertEquals(6000.0, library.updateProgress(source, "clamped", 7000.0, 6000.0, false).positionSec, 0.0)
        assertEquals(0.5f, library.updateProgress(source, "half", 3000.0, 6000.0, false).fraction!!, 0.0001f)
    }

    @Test fun progressIsBounded() {
        val library = library()
        for (i in 0 until UserLibrary.PROGRESS_LIMIT + 10) { now++; library.updateProgress(source, "p$i", 100.0, 1000.0, false) }
        assertNull(library.progress(source, "p0"))
        assertNotNull(library.progress(source, "p${UserLibrary.PROGRESS_LIMIT + 9}"))
    }

    @Test fun snapshotRoundTripRestoresEverythingInOrder() {
        val library = library()
        library.recordStart(source, LibrarySection.LIVE, LibraryItem("c:live", "News", 0, 0, logo = "https://logo.test/a.png"))
        library.recordStart(source, LibrarySection.SERIES, LibraryItem("s:xtream:7", "Show", 0, 0, episodeKey = "c:ep", season = 2, episode = 5, episodeTitle = "Pilot"))
        library.recordStart(source, LibrarySection.MOVIES, item("c:m1"))
        library.recordStart(source, LibrarySection.MOVIES, item("c:m2"))
        library.toggleFavorite(source, LibrarySection.SERIES, "s:xtream:7", "Show")
        library.updateProgress(source, "c:ep", 321.5, 2400.0, false)
        val json = JSONObject(library.snapshot().toJson().toString())
        val restored = library()
        restored.restore(json)
        assertEquals(listOf("c:m2", "c:m1"), restored.recent(source, LibrarySection.MOVIES).map { it.key })
        assertEquals("https://logo.test/a.png", restored.recent(source, LibrarySection.LIVE)[0].logo)
        val show = restored.recent(source, LibrarySection.SERIES)[0]
        assertEquals(listOf(2L, 5L, "Pilot", "c:ep"), listOf(show.season, show.episode, show.episodeTitle, show.episodeKey))
        assertTrue(restored.isFavorite(source, LibrarySection.SERIES, "s:xtream:7"))
        assertEquals(321.5, restored.progress(source, "c:ep")!!.positionSec, 0.0)
        assertEquals(2400.0, restored.progress(source, "c:ep")!!.durationSec!!, 0.0)
        // New entries after a restart still sort after the restored ones.
        restored.recordStart(source, LibrarySection.MOVIES, item("c:m3"))
        assertEquals("c:m3", restored.recent(source, LibrarySection.MOVIES)[0].key)
        assertTrue(restored.recent(source, LibrarySection.MOVIES)[0].seq > restored.recent(source, LibrarySection.MOVIES)[1].seq)
    }

    @Test fun restoreToleratesMissingAndMalformedFields() {
        val restored = library()
        restored.restore(JSONObject("""{"version":1,"profiles":{"default":{"sources":{"s":{"recent":{"movies":[{"name":"no key"},{"key":"ok"},{"key":"ok"}]},"progress":{"x":{"pos_s":5}}}}}}}"""))
        assertEquals(listOf("ok"), restored.recent("s", LibrarySection.MOVIES).map { it.key })
        assertNull(restored.progress("s", "x")!!.durationSec)
        restored.restore(JSONObject("{}"))
        assertEquals(0, restored.recentCount("s", LibrarySection.MOVIES))
    }

    @Test fun everyChangeNotifiesOnce() {
        val library = library()
        var changes = 0
        library.listener = { changes++ }
        library.recordStart(source, LibrarySection.LIVE, item("a"))
        library.toggleFavorite(source, LibrarySection.LIVE, "a", "A")
        library.updateProgress(source, "m", 1.0, null, false)
        library.removeRecent(source, LibrarySection.LIVE, "missing")
        assertEquals(3, changes)
    }
}
