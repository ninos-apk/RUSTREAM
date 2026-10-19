package de.iptv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.EnumMap

/** Sections with history and favourites. Unclassified entries have neither. */
enum class LibrarySection(val key: String) {
    LIVE("live"), MOVIES("movies"), SERIES("series");
    companion object { fun of(kind: String?) = entries.firstOrNull { it.key == kind } }
}

/** One remembered channel, film or show. Series entries carry their last episode only as fields. */
data class LibraryItem(
    val key: String,
    val name: String,
    val at: Long,
    val seq: Long,
    val logo: String? = null,
    val episodeKey: String? = null,
    val season: Long? = null,
    val episode: Long? = null,
    val episodeTitle: String? = null,
) {
    val episodeLabel: String? get() = when {
        season != null && episode != null -> "S$season · E$episode"
        episode != null -> "E$episode"
        season != null -> "S$season"
        else -> null
    }
}

/** Saved VOD position. Duration stays null when Media3 reports no finite length. */
data class Progress(val positionSec: Double, val durationSec: Double?, val at: Long, val done: Boolean) {
    /** Where playback continues; null starts at the beginning (finished or barely started). */
    val resumeSec: Double? get() = if (done || positionSec < UserLibrary.RESUME_MIN_SEC) null else positionSec
    /** Watched share for a progress bar; null without a known positive duration. */
    val fraction: Float? get() = durationSec?.takeIf { it > 0 }?.let { (positionSec / it).coerceIn(0.0, 1.0).toFloat() }
}

/**
 * History, favourites and progress per source, held in memory on the main thread.
 * The three stores are independent: removing one never touches the others.
 */
class UserLibrary(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val VERSION = 1
        const val DEFAULT_LIMIT = 15
        val LIMIT_CHOICES = listOf(5, 10, 15, 20, 30, 50)
        const val FAVORITE_LIMIT = 1000
        const val PROGRESS_LIMIT = 500
        const val RESUME_MIN_SEC = 10.0
        /** Credits: finished after 95 % when at most two minutes remain. */
        fun completed(positionSec: Double, durationSec: Double?, ended: Boolean) = ended ||
            (durationSec != null && durationSec > 0 && positionSec >= durationSec * 0.95 && durationSec - positionSec <= 120)
        private const val PROFILE = "default"
    }

    private class SourceData {
        val recent = EnumMap<LibrarySection, ArrayList<LibraryItem>>(LibrarySection::class.java)
        val favorites = EnumMap<LibrarySection, ArrayList<LibraryItem>>(LibrarySection::class.java)
        /** Mirrors [favorites] for O(1) lookups while binding list rows. */
        val favoriteKeys = EnumMap<LibrarySection, HashSet<String>>(LibrarySection::class.java)
        /** Insertion order is the update order, so the oldest entry is evicted first. */
        val progress = LinkedHashMap<String, Progress>()
        fun recent(section: LibrarySection) = recent.getOrPut(section) { ArrayList() }
        fun favorites(section: LibrarySection) = favorites.getOrPut(section) { ArrayList() }
        fun favoriteKeys(section: LibrarySection) = favoriteKeys.getOrPut(section) { HashSet() }
    }

    private val sources = HashMap<String, SourceData>()
    private var seq = 0L
    /** Called after every change; the store schedules a coalesced write. */
    var listener: (() -> Unit)? = null

    var historyLimit = DEFAULT_LIMIT
        set(value) {
            val limit = value.coerceIn(1, LIMIT_CHOICES.max())
            if (limit == field) return
            field = limit
            var changed = false
            for (data in sources.values) for (list in data.recent.values) if (list.size > limit) { list.subList(limit, list.size).clear(); changed = true }
            if (changed) changed()
        }

    fun recent(source: String, section: LibrarySection): List<LibraryItem> = sources[source]?.recent?.get(section)?.toList() ?: emptyList()
    fun favorites(source: String, section: LibrarySection): List<LibraryItem> = sources[source]?.favorites?.get(section)?.toList() ?: emptyList()
    fun recentCount(source: String, section: LibrarySection) = sources[source]?.recent?.get(section)?.size ?: 0
    fun favoriteCount(source: String, section: LibrarySection) = sources[source]?.favorites?.get(section)?.size ?: 0
    fun isFavorite(source: String, section: LibrarySection, key: String) = sources[source]?.favoriteKeys?.get(section)?.contains(key) == true
    fun progress(source: String, key: String): Progress? = sources[source]?.progress?.get(key)
    fun lastEpisode(source: String, seriesKey: String): LibraryItem? = sources[source]?.recent?.get(LibrarySection.SERIES)?.firstOrNull { it.key == seriesKey }

    /** An actual playback start: move to the front, never duplicate, trim the oldest. */
    fun recordStart(source: String, section: LibrarySection, item: LibraryItem) {
        val list = data(source).recent(section)
        val previous = list.firstOrNull { it.key == item.key }
        if (previous != null) list.remove(previous)
        list.add(0, item.copy(at = clock(), seq = ++seq, logo = item.logo ?: previous?.logo))
        if (list.size > historyLimit) list.subList(historyLimit, list.size).clear()
        changed()
    }

    fun removeRecent(source: String, section: LibrarySection, key: String) {
        if (sources[source]?.recent?.get(section)?.removeAll { it.key == key } == true) changed()
    }

    /** Returns the new state. Series are favoured as a whole show by the caller passing the show key. */
    fun toggleFavorite(source: String, section: LibrarySection, key: String, name: String): Boolean {
        return setFavorite(source, section, key, name, !isFavorite(source, section, key))
    }

    /** Idempotent desired state, independent of provider or TMDB identifiers. */
    fun setFavorite(source: String, section: LibrarySection, key: String, name: String, desired: Boolean): Boolean {
        if (isFavorite(source, section, key) == desired) return desired
        val data = data(source)
        val keys = data.favoriteKeys(section)
        val list = data.favorites(section)
        val active = if (keys.remove(key)) {
            list.removeAll { it.key == key }; false
        } else {
            keys.add(key); list.add(0, LibraryItem(key, name, clock(), ++seq))
            if (list.size > FAVORITE_LIMIT) list.removeAt(list.size - 1).let { keys.remove(it.key) }
            true
        }
        changed()
        return active
    }

    /** VOD progress only. Returns the stored value. */
    fun updateProgress(source: String, key: String, positionSec: Double, durationSec: Double?, ended: Boolean): Progress {
        val duration = durationSec?.takeIf { it.isFinite() && it > 0 }
        val position = positionSec.coerceAtLeast(0.0).let { if (duration != null) it.coerceAtMost(duration) else it }
        val value = Progress(position, duration, clock(), completed(position, duration, ended))
        val progress = data(source).progress
        progress.remove(key); progress[key] = value
        if (progress.size > PROGRESS_LIMIT) progress.keys.iterator().apply { next(); remove() }
        changed()
        return value
    }

    fun removeSource(source: String) { if (sources.remove(source) != null) changed() }

    private fun data(source: String) = sources.getOrPut(source) { SourceData() }
    private fun changed() = listener?.invoke()

    /** Immutable copy taken on the main thread; serialised on the writer thread. */
    fun snapshot(): Snapshot = Snapshot(seq, historyLimit, sources.mapValues { (_, data) ->
        SourceSnapshot(data.recent.mapValues { it.value.toList() }, data.favorites.mapValues { it.value.toList() }, data.progress.toList())
    })

    fun restore(json: JSONObject) {
        sources.clear()
        seq = json.optLong("seq")
        val profile = json.optJSONObject("profiles")?.optJSONObject(PROFILE) ?: return
        val all = profile.optJSONObject("sources") ?: return
        for (source in all.keys()) {
            val value = all.optJSONObject(source) ?: continue
            val data = SourceData()
            for (section in LibrarySection.entries) {
                value.optJSONObject("recent")?.optJSONArray(section.key)?.let { data.recent(section).addAll(items(it).take(LIMIT_CHOICES.max())) }
                value.optJSONObject("favorites")?.optJSONArray(section.key)?.let { array ->
                    for (item in items(array).take(FAVORITE_LIMIT)) if (data.favoriteKeys(section).add(item.key)) data.favorites(section).add(item)
                }
            }
            value.optJSONObject("progress")?.let { progress ->
                val entries = progress.keys().asSequence().mapNotNull { key -> progress.optJSONObject(key)?.let { key to it } }
                    .map { (key, p) -> key to Progress(p.optDouble("pos_s", 0.0), p.optDouble("dur_s").takeIf { it.isFinite() && it > 0 }, p.optLong("at"), p.optBoolean("done")) }
                    .sortedBy { it.second.at }.toList().takeLast(PROGRESS_LIMIT)
                for ((key, p) in entries) data.progress[key] = p
            }
            sources[source] = data
        }
        // The user may have lowered the limit while the file was written with a larger one.
        for (data in sources.values) for (list in data.recent.values) if (list.size > historyLimit) list.subList(historyLimit, list.size).clear()
    }

    private fun items(array: JSONArray): List<LibraryItem> = (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val key = o.optString("key").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        LibraryItem(key, o.optString("name"), o.optLong("at"), o.optLong("seq"),
            o.optNullString("logo"), o.optNullString("episode_key"),
            o.optNullLong("season"), o.optNullLong("episode"), o.optNullString("episode_title"))
    }.distinctBy { it.key }

    class SourceSnapshot(val recent: Map<LibrarySection, List<LibraryItem>>, val favorites: Map<LibrarySection, List<LibraryItem>>, val progress: List<Pair<String, Progress>>)
    class Snapshot(val seq: Long, val limit: Int, val sources: Map<String, SourceSnapshot>) {
        fun toJson(): JSONObject {
            val all = JSONObject()
            for ((source, data) in sources) {
                fun lists(map: Map<LibrarySection, List<LibraryItem>>) = JSONObject().apply {
                    for ((section, items) in map) if (items.isNotEmpty()) put(section.key, JSONArray(items.map(::item)))
                }
                val progress = JSONObject()
                for ((key, p) in data.progress) progress.put(key, JSONObject().put("pos_s", p.positionSec)
                    .put("dur_s", p.durationSec ?: JSONObject.NULL).put("at", p.at).put("done", p.done))
                all.put(source, JSONObject().put("recent", lists(data.recent)).put("favorites", lists(data.favorites)).put("progress", progress))
            }
            return JSONObject().put("version", VERSION).put("seq", seq).put("limit", limit)
                .put("profiles", JSONObject().put(PROFILE, JSONObject().put("sources", all)))
        }
        private fun item(i: LibraryItem) = JSONObject().put("key", i.key).put("name", i.name).put("at", i.at).put("seq", i.seq).apply {
            i.logo?.let { put("logo", it) }; i.episodeKey?.let { put("episode_key", it) }
            i.season?.let { put("season", it) }; i.episode?.let { put("episode", it) }; i.episodeTitle?.let { put("episode_title", it) }
        }
    }
}

private fun JSONObject.optNullString(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
private fun JSONObject.optNullLong(name: String): Long? = if (isNull(name) || !has(name)) null else optLong(name)
