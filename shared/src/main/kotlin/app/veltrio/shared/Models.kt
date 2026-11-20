package app.veltrio.shared

import kotlinx.serialization.Serializable
import uniffi.veltrio.Manifest
import uniffi.veltrio.Meta
import uniffi.veltrio.MetaPreview
import uniffi.veltrio.StreamSource
import uniffi.veltrio.Stream as CoreStream

// The core's records aren't serializable, so the app keeps its own shapes:
// what the screens show and what goes to disk.

/** An installed Stremio addon, as saved in addons.json. */
@Serializable
data class AddonInfo(
    val id: String = "",
    val version: String = "",
    val name: String = "",
    val description: String? = null,
    val logo: String? = null,
    val types: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val catalogs: List<CatalogInfo> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val transportUrl: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun serves(resource: String, type: String) =
        resource in resources && (types.isEmpty() || type in types)

    companion object {
        fun from(m: Manifest) = AddonInfo(
            id = m.id, version = m.version, name = m.name, description = m.description, logo = m.logo,
            types = m.types, resources = m.resources,
            catalogs = m.catalogs.map { c ->
                CatalogInfo(
                    type = c.type, id = c.id, name = c.name,
                    extras = c.extra.map { it.name },
                    requiredExtras = c.extra.filter { it.isRequired }.map { it.name },
                )
            },
            idPrefixes = m.idPrefixes, transportUrl = m.transportUrl,
        )
    }
}

@Serializable
data class CatalogInfo(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val extras: List<String> = emptyList(),
    val requiredExtras: List<String> = emptyList(),
) {
    /** Catalogs that need an extra (search, genre…) can't be listed alone. */
    val isBrowsable get() = requiredExtras.isEmpty()
    val isSearchable get() = "search" in extras
}

/** A poster: a catalog or search result. */
data class PosterItem(
    val id: String,
    val type: String,
    val title: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val description: String?,
    val year: String?,
    val rating: String?,
    val genres: List<String>,
    /** The addon that surfaced it, to open its page with. */
    val addon: AddonInfo,
) {
    val subtitle get() = listOfNotNull(year, rating?.let { "★ $it" }).filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        fun from(m: MetaPreview, addon: AddonInfo) = PosterItem(
            m.id, m.type, m.name, m.poster, m.background, m.description, m.releaseInfo, m.imdbRating, m.genres, addon,
        )
    }
}

/** A row on the home page or in search. */
data class CatalogRow(
    val title: String,
    val subtitle: String,
    val number: String,
    val addon: AddonInfo,
    val catalog: CatalogInfo?,
    val items: List<PosterItem>,
) {
    val canSeeAll get() = catalog != null && items.size >= 10
}

/** A title's full page. */
data class TitleDetails(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val rating: String?,
    val year: String?,
    val runtime: String?,
    val genres: List<String>,
    val cast: List<String>,
    val director: List<String>,
    val writer: List<String>,
    val episodes: List<EpisodeItem>,
    val addon: AddonInfo,
) {
    val isSeries get() = episodes.isNotEmpty()

    companion object {
        fun from(m: Meta, addon: AddonInfo) = TitleDetails(
            id = m.id, type = m.type, name = m.name, poster = m.poster, background = m.background, logo = m.logo,
            description = m.description, rating = m.imdbRating, year = m.releaseInfo, runtime = m.runtime,
            genres = m.genres, cast = m.cast, director = m.director, writer = m.writer,
            episodes = m.videos.map { v ->
                EpisodeItem(
                    id = v.id,
                    title = v.title.ifBlank { "Épisode ${v.episode ?: ""}".trim() },
                    season = v.season, episode = v.episode, thumbnail = v.thumbnail,
                    overview = v.overview, released = v.released,
                )
            }.sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season ?: 0 }, { it.episode ?: 0 })),
            addon = addon,
        )
    }
}

data class EpisodeItem(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val thumbnail: String?,
    val overview: String?,
    val released: String?,
) {
    val code get() = if (season != null && episode != null) "S$season · E$episode" else ""
    val releasedDate get() = released?.take(10) ?: ""
}

/** A playable stream from an addon, with what the list shows. */
class StreamItem(
    val stream: CoreStream,
    val addonName: String,
    val score: Int = 0,
    /** Set for a copy on the user's media server or IPTV provider ("JELLYFIN",
     *  "IPTV"), or "HORS LIGNE" for a download; null for an addon stream. */
    val origin: String? = null,
    /** A copy downloaded for offline playback: played from disk. */
    val localPath: String? = null,
) {
    val name get() = (stream.name ?: addonName).replace('\n', ' ')
    val title get() = (stream.title ?: stream.description ?: "").trim()

    val infoHash get() = (stream.source as? StreamSource.Torrent)?.infoHash

    val kind: String
        get() = origin ?: when (stream.source) {
            is StreamSource.Torrent -> cacheLabel ?: "TORRENT"
            is StreamSource.Url -> "DIRECT"
            is StreamSource.YouTube -> "YOUTUBE"
            else -> "EXTERNE"
        }

    val quality: String
        get() {
            val hay = "${stream.name} ${stream.title}".uppercase()
            return when {
                "2160P" in hay || "4K" in hay -> "4K"
                "1080P" in hay -> "1080p"
                "720P" in hay -> "720p"
                "480P" in hay -> "480p"
                else -> ""
            }
        }

    /** Stremio's behaviorHints.bingeGroup: the same release across episodes. */
    val bingeGroup: String?
        get() = stream.behaviorHintsJson?.let {
            try {
                Storage.json.parseToJsonElement(it).let { e ->
                    (e as? kotlinx.serialization.json.JsonObject)?.get("bingeGroup")
                        ?.let { g -> (g as? kotlinx.serialization.json.JsonPrimitive)?.content }
                }
            } catch (_: Exception) {
                null
            }
        }

    /** The debrid service that has this torrent cached, if any (the ⚡ badge). */
    val cachedOn get() = infoHash?.let { DebridService.cachedOn(it) }
    val isCached get() = cachedOn != null
    val cacheLabel get() = cachedOn?.let { "⚡ ${it.short}" }
}

/** What the player is asked to open. */
data class PlayRequest(
    val title: String,
    val subtitle: String? = null,
    /** A ready URL (live channel, server copy, file), or null to resolve [stream]. */
    val url: String? = null,
    val stream: StreamItem? = null,
    // Progress tracking; null for live TV.
    val contentType: String? = null,
    val contentId: String? = null,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val background: String? = null,
    val addon: AddonInfo? = null,
    val isLive: Boolean = false,
    /** Series: what comes next ("S1E3 · Titre") and how to get its request. */
    val nextLabel: String? = null,
    val next: (suspend () -> PlayRequest?)? = null,
)
