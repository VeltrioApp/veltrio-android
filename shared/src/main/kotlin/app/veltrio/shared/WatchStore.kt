package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One title in the library / watch history, keyed by (type, id). Same
 *  shape as the macOS app's WatchEntry. Times in epoch milliseconds. */
@Serializable
data class WatchEntry(
    val type: String = "",
    val contentId: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val addonId: String? = null,
    val addonName: String? = null,
    /** Movie progress, in seconds. A series keeps its episodes below. */
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val watched: Boolean = false,
    val episodes: Map<String, EpisodeProgress> = emptyMap(),
    val lastEpisodeId: String? = null,
    val inLibrary: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastWatchedAt: Long = System.currentTimeMillis(),
) {
    val key get() = "$type|$contentId"

    private val current: Pair<Double, Double>
        get() = lastEpisodeId?.takeIf { type == "series" }?.let { episodes[it] }?.let { it.position to it.duration }
            ?: (position to duration)

    /** 0…1; for a series, of the last episode started. */
    val progressFraction get() = current.let { (p, d) -> if (d > 0) (p / d).coerceIn(0.0, 1.0) else 0.0 }

    val isStarted get() = if (type == "series") lastEpisodeId != null else position > 10

    val episodeCode: String
        get() = lastEpisodeId?.takeIf { type == "series" }?.let { episodes[it] }
            ?.let { if (it.season != null && it.episode != null) "S${it.season} · E${it.episode}" else null } ?: ""

    val resumeLabel: String
        get() {
            val (pos, dur) = current
            if (dur <= 0) return tr("PAS COMMENCÉ")
            val mins = ((dur - pos).coerceAtLeast(0.0) / 60).toInt()
            return if (mins <= 1) tr("BIENTÔT FINI") else tr("%s MIN RESTANTES", mins)
        }

    val statusLabel get() = when {
        watched -> tr("VU")
        isStarted -> tr("EN COURS")
        inLibrary -> tr("ENREGISTRÉ")
        else -> ""
    }
}

@Serializable
data class EpisodeProgress(
    val season: Int? = null,
    val episode: Int? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val watched: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A title as an account reports it, before it's merged into the library.
 *  Position and duration in seconds, films only. */
data class ImportedWatchItem(
    val type: String,
    val id: String,
    val name: String,
    val poster: String? = null,
    val inLibrary: Boolean = false,
    val watched: Boolean = false,
    val position: Double? = null,
    val duration: Double? = null,
    val lastWatched: Long? = null,
)

/** The persisted library and watch history (watch.json). Every change goes
 *  through here so Home, the title page and the Collection stay in sync. */
object WatchStore {
    private const val FILE = "watch.json"
    private const val WATCHED_THRESHOLD = 0.9

    private val _entries = MutableStateFlow<Map<String, WatchEntry>>(emptyMap())
    val entries: StateFlow<Map<String, WatchEntry>> = _entries

    internal fun init() {
        _entries.value = Storage.load(FILE, ListSerializer(WatchEntry.serializer()), emptyList()).associateBy { it.key }
    }

    fun entry(type: String, id: String) = _entries.value["$type|$id"]

    fun isInLibrary(type: String, id: String) = entry(type, id)?.inLibrary == true

    /** In progress, most recent first. Only titles with a page to reopen. */
    val continueWatching: List<WatchEntry>
        get() = _entries.value.values.filter { it.type in setOf("movie", "series") && it.isStarted && !it.watched }
            .sortedByDescending { it.lastWatchedAt }

    val libraryItems: List<WatchEntry>
        get() = _entries.value.values.filter { it.type in setOf("movie", "series") && (it.inLibrary || it.isStarted || it.watched) }
            .sortedByDescending { it.lastWatchedAt }

    /** Seconds to resume at; a title within 30 s of its end starts over. */
    fun resumePosition(type: String, id: String, videoId: String? = null): Double {
        val e = entry(type, id) ?: return 0.0
        val (pos, dur) = when {
            type == "series" && videoId != null -> e.episodes[videoId]?.let { it.position to it.duration } ?: (0.0 to 0.0)
            type == "series" -> 0.0 to 0.0
            else -> e.position to e.duration
        }
        return if (dur > 0 && pos > dur - 30) 0.0 else pos
    }

    fun episode(type: String, id: String, videoId: String) = entry(type, id)?.episodes?.get(videoId)

    fun reportProgress(r: PlayRequest, position: Double, duration: Double) {
        val type = r.contentType ?: return
        val id = r.contentId ?: return
        if (duration <= 0) return
        update(type, id, r.title) { e ->
            var n = e.copy(
                poster = r.poster ?: e.poster, background = r.background ?: e.background,
                addonId = r.addon?.id ?: e.addonId, addonName = r.addon?.name ?: e.addonName,
                lastWatchedAt = System.currentTimeMillis(),
            )
            val vid = r.videoId
            if (type == "series" && vid != null) {
                val ep = (e.episodes[vid] ?: EpisodeProgress()).let {
                    it.copy(
                        season = r.season ?: it.season, episode = r.episode ?: it.episode,
                        position = position, duration = duration,
                        watched = it.watched || position / duration >= WATCHED_THRESHOLD,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                n = n.copy(episodes = e.episodes + (vid to ep), lastEpisodeId = vid)
            } else {
                n = n.copy(position = position, duration = duration, watched = e.watched || position / duration >= WATCHED_THRESHOLD)
            }
            n
        }
    }

    fun toggleLibrary(type: String, id: String, name: String, poster: String?, background: String?, addon: AddonInfo?) {
        update(type, id, name) { e ->
            val inLibrary = !e.inLibrary
            val now = System.currentTimeMillis()
            e.copy(
                poster = poster ?: e.poster, background = background ?: e.background,
                addonId = addon?.id ?: e.addonId, addonName = addon?.name ?: e.addonName,
                inLibrary = inLibrary,
                addedAt = if (inLibrary) now else e.addedAt,
                lastWatchedAt = if (inLibrary) now else e.lastWatchedAt,
            )
        }
    }

    fun markWatched(type: String, id: String, watched: Boolean) {
        val e = entry(type, id) ?: return
        put(e.copy(watched = watched, position = if (watched) maxOf(e.position, e.duration) else 0.0, lastWatchedAt = System.currentTimeMillis()))
    }

    fun remove(type: String, id: String) {
        _entries.value = _entries.value - "$type|$id"
        save()
    }

    /** Clears progress but keeps a saved title in the library. */
    fun removeFromContinueWatching(type: String, id: String) {
        val e = entry(type, id) ?: return
        if (!e.inLibrary) remove(type, id)
        else put(e.copy(position = 0.0, duration = 0.0, episodes = emptyMap(), lastEpisodeId = null))
    }

    /** Folds titles from an account (Stremio, Trakt, SIMKL) into the
     *  library. Additive only: never unsaves, unwatches or rewinds. Returns
     *  how many titles changed. */
    fun merge(items: List<ImportedWatchItem>): Int {
        var changed = 0
        val map = _entries.value.toMutableMap()
        for (item in items) {
            val key = "${item.type}|${item.id}"
            val before = map[key]
            var e = before ?: WatchEntry(type = item.type, contentId = item.id, name = item.name)
            if (e.name.isEmpty()) e = e.copy(name = item.name)
            if (e.poster == null && item.poster != null) e = e.copy(poster = item.poster)
            if (item.inLibrary && !e.inLibrary) e = e.copy(inLibrary = true, addedAt = item.lastWatched ?: System.currentTimeMillis())
            if (item.watched) e = e.copy(watched = true)
            val pos = item.position
            val dur = item.duration
            if (item.type == "movie" && pos != null && dur != null && dur > 0 && pos > e.position) e = e.copy(position = pos, duration = dur)
            item.lastWatched?.let { if (before == null || it > e.lastWatchedAt) e = e.copy(lastWatchedAt = it) }
            if (e != before) {
                map[key] = e
                changed++
            }
        }
        if (changed > 0) {
            _entries.value = map
            save()
        }
        return changed
    }

    /** Entries from another device (settings received from a phone): saved
     *  and watched marks add up, progress takes the more recent side, episode
     *  by episode. Returns how many titles changed. */
    fun mergeEntries(incoming: List<WatchEntry>): Int {
        var changed = 0
        val map = _entries.value.toMutableMap()
        for (theirs in incoming) {
            val mine = map[theirs.key]
            val merged = if (mine == null) theirs else {
                val newer = if (theirs.lastWatchedAt > mine.lastWatchedAt) theirs else mine
                val episodes = (mine.episodes.keys + theirs.episodes.keys).associateWith { id ->
                    val a = mine.episodes[id]
                    val b = theirs.episodes[id]
                    when {
                        a == null -> b!!
                        b == null -> a
                        else -> (if (b.updatedAt > a.updatedAt) b else a).copy(watched = a.watched || b.watched)
                    }
                }
                newer.copy(
                    inLibrary = mine.inLibrary || theirs.inLibrary,
                    watched = mine.watched || theirs.watched,
                    episodes = episodes,
                    addedAt = minOf(mine.addedAt, theirs.addedAt),
                    poster = newer.poster ?: mine.poster ?: theirs.poster,
                    background = newer.background ?: mine.background ?: theirs.background,
                )
            }
            if (merged != mine) {
                map[theirs.key] = merged
                changed++
            }
        }
        if (changed > 0) {
            _entries.value = map
            save()
        }
        return changed
    }

    private fun update(type: String, id: String, name: String, change: (WatchEntry) -> WatchEntry) {
        val key = "$type|$id"
        val e = _entries.value[key] ?: WatchEntry(type = type, contentId = id)
        put(change(if (name.isNotEmpty()) e.copy(name = name) else e))
    }

    private fun put(e: WatchEntry) {
        _entries.value = _entries.value + (e.key to e)
        save()
    }

    private fun save() = Storage.save(FILE, ListSerializer(WatchEntry.serializer()), _entries.value.values.toList())
}
