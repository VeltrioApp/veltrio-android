package app.veltrio.shared

import app.veltrio.shared.i18n.Lang
import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Native TMDB catalog, search, and metadata service.
 *  Replaces Cinemeta so every catalog row, hero, search result, title page,
 *  and episode list is localized in the user's chosen language while resolving
 *  IMDb IDs (`external_ids.imdb_id`) for Stremio stream addons.
 *  Accepts either a TMDB v3 API key or a v4 Read Access Token. Both the key
 *  and the chosen language live under the `tmdb.` prefix in [SecretStore], so
 *  LAN TV pairing transfers them automatically. */
object TmdbService {
    const val ADDON_ID = "tmdb"
    const val TRANSPORT_URL = "tmdb://api.themoviedb.org/3"

    private const val KEY_SECRET = "tmdb.key"
    private const val LANG_SECRET = "tmdb.lang"
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
    private const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"
    private const val IMG_LOGO = "https://image.tmdb.org/t/p/w500"
    private const val IMG_STILL = "https://image.tmdb.org/t/p/w500"

    val languages: List<Pair<String, String>>
        get() = listOf(
            "fr-FR" to "Français",
            "en-US" to "English",
            "es-ES" to "Español",
            "de-DE" to "Deutsch",
            "it-IT" to "Italiano",
            "pt-BR" to "Português",
            "ja-JP" to "日本語",
        )

    data class State(
        val connected: Boolean = false,
        val language: String = "fr-FR",
        val busy: Boolean = false,
        val error: String? = null,
    )

    data class LocalizedMeta(val title: String?, val overview: String?)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val localizeCache = ConcurrentHashMap<String, LocalizedMeta>()
    private val genreCache = ConcurrentHashMap<String, Map<Int, String>>()

    val isConnected: Boolean get() = _state.value.connected
    val language: String get() = _state.value.language.ifBlank { defaultLanguage() }

    private fun defaultLanguage(): String = if (Lang.french) "fr-FR" else "en-US"

    /** Virtual addon exposing TMDB's native browsable and searchable catalogs. */
    fun virtualAddon(): AddonInfo = AddonInfo(
        id = ADDON_ID,
        version = "3.0.0",
        name = "TMDB",
        description = tr("Catalogues et fiches localisées via The Movie Database."),
        types = listOf("movie", "series"),
        resources = listOf("catalog", "meta"),
        catalogs = listOf(
            CatalogInfo("movie", "trending", tr("Films populaires"), extras = listOf("skip", "search")),
            CatalogInfo("series", "trending", tr("Séries populaires"), extras = listOf("skip", "search")),
            CatalogInfo("movie", "now_playing", tr("Au cinéma"), extras = listOf("skip")),
            CatalogInfo("series", "on_the_air", tr("Séries du moment"), extras = listOf("skip")),
            CatalogInfo("movie", "top_rated", tr("Films les mieux notés"), extras = listOf("skip")),
            CatalogInfo("series", "top_rated", tr("Séries les mieux notées"), extras = listOf("skip")),
        ),
        idPrefixes = listOf("tmdb:", "tt"),
        transportUrl = TRANSPORT_URL,
    )

    fun reload() {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() }
        val lang = SecretStore.get(LANG_SECRET)?.takeIf { it.isNotBlank() } ?: defaultLanguage()
        _state.value = _state.value.copy(connected = key != null, language = lang, error = null)
    }

    fun setLanguage(lang: String) {
        val clean = lang.ifBlank { defaultLanguage() }
        SecretStore.set(LANG_SECRET, clean)
        localizeCache.clear()
        genreCache.clear()
        Core.clearCatalogCache()
        _state.value = _state.value.copy(language = clean)
    }

    suspend fun connect(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) {
            _state.value = _state.value.copy(error = tr("Saisissez une clé API ou un jeton TMDB."))
            return false
        }
        _state.value = _state.value.copy(busy = true, error = null)
        return try {
            val req = buildRequest("/configuration", key)
            withContext(Dispatchers.IO) {
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw ServiceException(tr("Clé TMDB invalide (HTTP %s).", r.code))
                }
            }
            SecretStore.set(KEY_SECRET, key)
            if (SecretStore.get(LANG_SECRET).isNullOrBlank()) {
                SecretStore.set(LANG_SECRET, defaultLanguage())
            }
            localizeCache.clear()
            genreCache.clear()
            Core.clearCatalogCache()
            _state.value = _state.value.copy(
                connected = true,
                language = SecretStore.get(LANG_SECRET) ?: defaultLanguage(),
                busy = false,
                error = null,
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("VeltrioTMDB", "TMDB connect failed", e)
            val detail = generateSequence<Throwable>(e) { it.cause }.lastOrNull()?.message ?: e.message ?: Core.humanize(e)
            _state.value = _state.value.copy(busy = false, error = detail)
            false
        }
    }

    fun disconnect() {
        SecretStore.set(KEY_SECRET, null)
        localizeCache.clear()
        genreCache.clear()
        Core.clearCatalogCache()
        _state.value = _state.value.copy(connected = false, error = null)
    }

    // -------------------- catalogs & search --------------------

    suspend fun fetchCatalog(type: String, catalogId: String, skip: Int = 0): List<PosterItem> {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lang = language
        val page = (skip / 20) + 1
        val isSeries = type.equals("series", ignoreCase = true)
        val kids = AppPrefs.current.kidsMode
        val path = if (kids) {
            val endpoint = if (isSeries) "tv" else "movie"
            val sort = if (catalogId == "top_rated") "vote_average.desc&vote_count.gte=100" else "popularity.desc"
            val cert = if (!isSeries) "&certification_country=US&certification.lte=PG" else ""
            "/discover/$endpoint?language=$lang&page=$page&include_adult=false&with_genres=10751|16&sort_by=$sort$cert"
        } else {
            when {
                !isSeries && catalogId == "trending" -> "/trending/movie/week?language=$lang&page=$page"
                isSeries && catalogId == "trending" -> "/trending/tv/week?language=$lang&page=$page"
                !isSeries && catalogId == "now_playing" -> "/movie/now_playing?language=$lang&page=$page"
                isSeries && catalogId == "on_the_air" -> "/tv/on_the_air?language=$lang&page=$page"
                !isSeries && catalogId == "top_rated" -> "/movie/top_rated?language=$lang&page=$page"
                isSeries && catalogId == "top_rated" -> "/tv/top_rated?language=$lang&page=$page"
                else -> return emptyList()
            }
        }
        val genres = genreMap(isSeries, lang, key)
        val json = Http.json(buildRequest(path, key), "TMDB")
        val addon = virtualAddon()
        return json.array("results").mapNotNull { parsePoster(it, if (isSeries) "series" else "movie", genres, addon) }
    }

    suspend fun search(query: String, type: String): List<PosterItem> {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lang = language
        val isSeries = type.equals("series", ignoreCase = true)
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(query, "UTF-8") }
        val endpoint = if (isSeries) "tv" else "movie"
        val genres = genreMap(isSeries, lang, key)
        val json = Http.json(buildRequest("/search/$endpoint?query=$encoded&language=$lang&include_adult=false", key), "TMDB")
        val addon = virtualAddon()
        val kids = AppPrefs.current.kidsMode
        return json.array("results")
            .filter { el ->
                if (!kids) true
                else {
                    val gids = el.array("genre_ids").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    (el["adult"] as? JsonPrimitive)?.content != "true" && (gids.isEmpty() || 10751 in gids || 16 in gids)
                }
            }
            .mapNotNull { parsePoster(it, if (isSeries) "series" else "movie", genres, addon) }
    }

    data class DiscoverProvider(val id: Int, val name: String, val logoUrl: String, val colorHex: Long)

    /** Popular streaming providers and quick genres for the Discover bar. */
    val discoverProviders: List<DiscoverProvider> = listOf(
        DiscoverProvider(8, "Netflix", "https://image.tmdb.org/t/p/w154/pbpMk2JmcoNnQwx5JGpXngfoWtp.jpg", 0xFFE50914),
        DiscoverProvider(350, "Apple TV+", "https://image.tmdb.org/t/p/w154/2E03IAZsX4ZaUqM7tXlctEPMGWS.jpg", 0xFF3A3A3C),
        DiscoverProvider(337, "Disney+", "https://image.tmdb.org/t/p/w154/97yvRBw1GzX7fXprcF80er19ot.jpg", 0xFF113CCF),
        DiscoverProvider(119, "Prime Video", "https://image.tmdb.org/t/p/w154/pvske1MyAoymrs5bguRfVqYiM9a.jpg", 0xFF00A8E1),
        DiscoverProvider(1899, "Max", "https://image.tmdb.org/t/p/w154/jbe4gVSfRlbPTdESXhEKpornsfu.jpg", 0xFF002BE7),
        DiscoverProvider(381, "Canal+", "https://image.tmdb.org/t/p/w154/eBXzkFEupZjKaIKY7zBUaSdCY8I.jpg", 0xFF2C2C2E),
    )

    data class DiscoverGenre(val movieId: Int, val tvId: Int, private val labelKey: String, val colorHex: Long) {
        val label: String get() = tr(labelKey)
    }

    val discoverGenres: List<DiscoverGenre> = listOf(
        DiscoverGenre(28, 10759, "Action", 0xFFD9381E),
        DiscoverGenre(878, 10765, "Sci-Fi", 0xFF0E7490),
        DiscoverGenre(35, 35, "Comédie", 0xFFD97706),
        DiscoverGenre(18, 18, "Drame", 0xFF6D28D9),
        DiscoverGenre(53, 9648, "Thriller", 0xFF374151),
        DiscoverGenre(16, 16, "Animation", 0xFFDB2777),
        DiscoverGenre(27, 9648, "Horreur", 0xFF991B1B),
        DiscoverGenre(99, 99, "Documentaire", 0xFF047857),
    )

    suspend fun discover(
        type: String,
        genreId: Int? = null,
        providerId: Int? = null,
        page: Int = 1,
        sortBy: String = "popularity.desc",
        minVotes: Int? = null,
    ): List<PosterItem> {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val lang = language
        val region = lang.substringAfter('-', "FR").uppercase()
        val isSeries = type.equals("series", ignoreCase = true)
        val endpoint = if (isSeries) "tv" else "movie"
        val kids = AppPrefs.current.kidsMode
        val params = buildList {
            add("language=$lang")
            add("sort_by=$sortBy")
            add("include_adult=false")
            add("page=$page")
            if (minVotes != null && minVotes > 0) {
                add("vote_count.gte=$minVotes")
            }
            if (kids) {
                add("with_genres=10751|16")
                if (!isSeries) add("certification_country=US&certification.lte=PG")
            } else if (genreId != null) {
                add("with_genres=$genreId")
            }
            if (providerId != null) {
                add("with_watch_providers=$providerId")
                add("watch_region=$region")
            }
        }.joinToString("&")
        val genres = genreMap(isSeries, lang, key)
        val json = Http.json(buildRequest("/discover/$endpoint?$params", key), "TMDB")
        val addon = virtualAddon()
        return json.array("results").mapNotNull { parsePoster(it, if (isSeries) "series" else "movie", genres, addon) }
    }

    /** Searches a person by name on TMDB and returns their combined movie & TV filmography. */
    suspend fun fetchPersonCredits(name: String): List<PosterItem> {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val clean = name.trim()
        if (clean.isEmpty()) return emptyList()
        val lang = language
        val encoded = withContext(Dispatchers.IO) { URLEncoder.encode(clean, "UTF-8") }
        val searchJson = Http.json(buildRequest("/search/person?query=$encoded&language=$lang&include_adult=false", key), "TMDB")
        val personId = searchJson.array("results").firstOrNull()?.long("id")?.takeIf { it > 0 } ?: return emptyList()
        val creditsJson = Http.json(buildRequest("/person/$personId/combined_credits?language=$lang", key), "TMDB")
        val movieGenres = genreMap(false, lang, key)
        val tvGenres = genreMap(true, lang, key)
        val addon = virtualAddon()
        val kids = AppPrefs.current.kidsMode
        val seen = HashSet<String>()
        val combined = creditsJson.array("cast") + creditsJson.array("crew")
        return combined
            .sortedByDescending { it.double("popularity") ?: 0.0 }
            .mapNotNull { el ->
                val mt = el.str("media_type") ?: return@mapNotNull null
                if (mt != "movie" && mt != "tv") return@mapNotNull null
                val tid = el.long("id").takeIf { it > 0 } ?: return@mapNotNull null
                val dedupKey = "$mt:$tid"
                if (!seen.add(dedupKey)) return@mapNotNull null
                if (kids) {
                    val gids = el.array("genre_ids").mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    if ((el["adult"] as? JsonPrimitive)?.content == "true") return@mapNotNull null
                    if (gids.isNotEmpty() && 10751 !in gids && 16 !in gids) return@mapNotNull null
                }
                val isSeries = mt == "tv"
                parsePoster(el, if (isSeries) "series" else "movie", if (isSeries) tvGenres else movieGenres, addon)
            }
            .take(24)
    }

    // -------------------- full details --------------------

    suspend fun fetchDetails(type: String, id: String): TitleDetails {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() }
            ?: throw ServiceException(tr("Connectez votre clé TMDB dans les réglages."))
        val lang = language
        val isSeries = type.equals("series", ignoreCase = true)
        val tmdbId = resolveTmdbId(id, isSeries, lang, key)
            ?: throw ServiceException(tr("Titre introuvable sur TMDB."))

        val mediaType = if (isSeries) "tv" else "movie"
        val langShort = lang.substringBefore('-')
        val path = "/$mediaType/$tmdbId?language=$lang&append_to_response=external_ids,credits,images,videos,recommendations&include_image_language=$langShort,en,null"
        val json = Http.json(buildRequest(path, key), "TMDB")

        val imdbId = json["external_ids"]?.str("imdb_id")?.takeIf { it.startsWith("tt") }
            ?: json.str("imdb_id")?.takeIf { it.startsWith("tt") }
            ?: id.takeIf { it.startsWith("tt") }
        val canonicalId = imdbId ?: "tmdb:$tmdbId"

        val title = (json.str("title") ?: json.str("name") ?: json.str("original_title") ?: json.str("original_name") ?: "").trim()
        val overview = json.str("overview")?.takeIf { it.isNotBlank() }
        val poster = json.str("poster_path")?.let { "$IMG_POSTER$it" }
        val backdrop = json.str("backdrop_path")?.let { "$IMG_BACKDROP$it" }
        val logo = pickLogo(json["images"], langShort)
        val rating = json.double("vote_average")?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
        val dateStr = json.str("release_date") ?: json.str("first_air_date")
        val year = dateStr?.take(4)?.takeIf { it.length == 4 }
        val runtimeMin = if (isSeries) {
            (json.array("episode_run_time").firstOrNull() as? JsonPrimitive)?.intOrNull
        } else {
            (json["runtime"] as? JsonPrimitive)?.intOrNull
        }
        val runtime = runtimeMin?.takeIf { it > 0 }?.let { m ->
            val h = m / 60
            val rem = m % 60
            when {
                h > 0 && rem > 0 -> "$h h $rem min"
                h > 0 -> "$h h"
                else -> "$m min"
            }
        }
        val genres = json.array("genres").mapNotNull { it.str("name") }
        val cast = json["credits"]?.array("cast")?.take(12)?.mapNotNull { it.str("name") }.orEmpty()
        val crew = json["credits"]?.array("crew").orEmpty()
        val directors = if (isSeries) {
            json.array("created_by").mapNotNull { it.str("name") }
        } else {
            crew.filter { it.str("job") == "Director" }.mapNotNull { it.str("name") }
        }
        val writers = crew.filter { it.str("department") == "Writing" }.mapNotNull { it.str("name") }.distinct().take(4)

        val episodes = if (isSeries) {
            fetchEpisodes(tmdbId, canonicalId, json.array("seasons"), lang, key)
        } else {
            emptyList()
        }

        val trailerUrl = pickTrailer(json["videos"], langShort)
        val addon = virtualAddon()
        val genresMap = genreMap(isSeries, lang, key)
        val recs = json["recommendations"]?.array("results")
            ?.take(12)
            ?.mapNotNull { parsePoster(it, if (isSeries) "series" else "movie", genresMap, addon) }
            .orEmpty()

        return TitleDetails(
            id = canonicalId,
            type = if (isSeries) "series" else "movie",
            name = title,
            poster = poster,
            background = backdrop,
            logo = logo,
            description = overview,
            rating = rating,
            year = year,
            runtime = runtime,
            genres = genres,
            cast = cast,
            director = directors,
            writer = writers,
            episodes = episodes,
            addon = addon,
            trailerUrl = trailerUrl,
            recommendations = recs,
        )
    }

    private fun pickTrailer(videos: JsonElement?, langShort: String): String? {
        val list = videos?.array("results").orEmpty()
            .filter { it.str("site").equals("YouTube", ignoreCase = true) && !it.str("key").isNullOrBlank() }
        if (list.isEmpty()) return null
        val chosen = list.firstOrNull { it.str("type") == "Trailer" && it.str("iso_639_1") == langShort }
            ?: list.firstOrNull { it.str("type") == "Trailer" }
            ?: list.firstOrNull { it.str("type") == "Teaser" }
            ?: list.first()
        return chosen.str("key")?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private suspend fun fetchEpisodes(
        tmdbId: String,
        canonicalId: String,
        seasonsJson: List<JsonElement>,
        lang: String,
        key: String,
    ): List<EpisodeItem> = coroutineScope {
        val seasonNums = seasonsJson.mapNotNull { (it["season_number"] as? JsonPrimitive)?.intOrNull }
            .filter { it > 0 }
            .sorted()
            .take(30)
        seasonNums.map { sNum ->
            async {
                try {
                    val sJson = Http.json(buildRequest("/tv/$tmdbId/season/$sNum?language=$lang", key), "TMDB")
                    sJson.array("episodes").mapNotNull { ep ->
                        val epNum = (ep["episode_number"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                        val epTitle = ep.str("name")?.takeIf { it.isNotBlank() } ?: tr("Épisode %s", epNum)
                        EpisodeItem(
                            id = "$canonicalId:$sNum:$epNum",
                            title = epTitle,
                            season = sNum,
                            episode = epNum,
                            thumbnail = ep.str("still_path")?.let { "$IMG_STILL$it" },
                            overview = ep.str("overview")?.takeIf { it.isNotBlank() },
                            released = ep.str("air_date"),
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun resolveTmdbId(id: String, isSeries: Boolean, lang: String, key: String): String? {
        if (id.startsWith("tmdb:")) return id.removePrefix("tmdb:")
        if (id.startsWith("tt")) {
            val json = Http.json(buildRequest("/find/$id?external_source=imdb_id&language=$lang", key), "TMDB")
            val primary = json.array(if (isSeries) "tv_results" else "movie_results").firstOrNull()
                ?: json.array(if (isSeries) "movie_results" else "tv_results").firstOrNull()
            val found = primary?.long("id")?.takeIf { it > 0 } ?: return null
            return found.toString()
        }
        return id.toLongOrNull()?.toString()
    }

    /** Returns localized title & synopsis for an IMDb id (`tt...`), or null
     *  when TMDB is not configured or has no match. */
    suspend fun localize(imdbId: String, type: String): LocalizedMeta? {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return null
        val lang = language
        if (!imdbId.startsWith("tt")) return null
        val cacheKey = "$imdbId|$type|$lang"
        localizeCache[cacheKey]?.let { return it }
        return try {
            val req = buildRequest("/find/$imdbId?external_source=imdb_id&language=$lang", key)
            val json = Http.json(req, "TMDB")
            val isSeries = type.equals("series", ignoreCase = true)
            val primary = json.array(if (isSeries) "tv_results" else "movie_results").firstOrNull()
                ?: json.array(if (isSeries) "movie_results" else "tv_results").firstOrNull()
                ?: return null
            val title = (primary.str("title") ?: primary.str("name"))?.takeIf { it.isNotBlank() }
            val overview = primary.str("overview")?.takeIf { it.isNotBlank() }
            LocalizedMeta(title, overview).also { localizeCache[cacheKey] = it }
        } catch (_: Exception) {
            null
        }
    }

    // -------------------- helpers --------------------

    private suspend fun genreMap(isSeries: Boolean, lang: String, key: String): Map<Int, String> {
        val cacheKey = "${if (isSeries) "tv" else "movie"}|$lang"
        genreCache[cacheKey]?.let { return it }
        return try {
            val endpoint = if (isSeries) "tv" else "movie"
            val json = Http.json(buildRequest("/genre/$endpoint/list?language=$lang", key), "TMDB")
            val map = json.array("genres").mapNotNull { g ->
                val gid = (g["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val name = g.str("name") ?: return@mapNotNull null
                gid to name
            }.toMap()
            map.also { genreCache[cacheKey] = it }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parsePoster(el: JsonElement, type: String, genres: Map<Int, String>, addon: AddonInfo): PosterItem? {
        val tmdbId = el.long("id").takeIf { it > 0 } ?: return null
        val title = (el.str("title") ?: el.str("name") ?: el.str("original_title") ?: el.str("original_name"))?.takeIf { it.isNotBlank() } ?: return null
        val posterPath = el.str("poster_path")
        val backdropPath = el.str("backdrop_path")
        val dateStr = el.str("release_date") ?: el.str("first_air_date")
        val year = dateStr?.take(4)?.takeIf { it.length == 4 }
        val rating = el.double("vote_average")?.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }
        val itemGenres = el.array("genre_ids").mapNotNull { (it as? JsonPrimitive)?.intOrNull?.let { id -> genres[id] } }
        return PosterItem(
            id = "tmdb:$tmdbId",
            type = type,
            title = title,
            posterUrl = posterPath?.let { "$IMG_POSTER$it" },
            backgroundUrl = backdropPath?.let { "$IMG_BACKDROP$it" },
            description = el.str("overview")?.takeIf { it.isNotBlank() },
            year = year,
            rating = rating,
            genres = itemGenres,
            addon = addon,
        )
    }

    private fun pickLogo(images: JsonElement?, langShort: String): String? {
        val logos = images?.array("logos").orEmpty()
        val chosen = logos.firstOrNull { it.str("iso_639_1") == langShort }
            ?: logos.firstOrNull { it.str("iso_639_1") == "en" }
            ?: logos.firstOrNull()
        return chosen?.str("file_path")?.let { "$IMG_LOGO$it" }
    }

    private fun buildRequest(path: String, key: String): Request {
        val isBearer = key.length > 40 || key.contains('.')
        val sep = if ('?' in path) '&' else '?'
        val url = if (isBearer) "$BASE$path" else "$BASE$path${sep}api_key=$key"
        return Request.Builder().url(url).apply {
            header("Accept", "application/json")
            if (isBearer) header("Authorization", "Bearer $key")
        }.build()
    }
}
