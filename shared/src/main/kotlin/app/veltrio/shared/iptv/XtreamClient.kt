package app.veltrio.shared.iptv

import app.veltrio.shared.i18n.tr
import app.veltrio.shared.Http
import app.veltrio.shared.Storage
import app.veltrio.shared.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Xtream Codes, through the provider's player_api.php, mapped to the same
 *  ChannelItem as M3U entries. Same behaviour as the macOS client:
 *  per-row tolerant decoding (panels mix strings and numbers), auth
 *  failures told apart from empty lists. */
object XtreamClient {
    class AuthException : Exception(tr("Identifiants Xtream refusés : vérifiez l'utilisateur et le mot de passe."))

    // -------------------- account --------------------

    /** Checks the account; returns the expiry if the panel gives one. */
    suspend fun verify(account: XtreamAccount): Instant? {
        val user = get(account, null)["user_info"] as? JsonObject ?: throw AuthException()
        if (text(user, "auth") == "0") throw AuthException()
        text(user, "status")?.let { if (it.lowercase() !in setOf("active", "trial")) throw IllegalStateException("Compte Xtream $it.") }
        return text(user, "exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
    }

    // -------------------- catalogues --------------------

    suspend fun live(account: XtreamAccount): List<ChannelItem> = coroutineScope {
        val categories = async { categories(get(account, "get_live_categories")) }
        val ext = account.output.ifEmpty { "ts" }
        val rows = rows(get(account, "get_live_streams"))
        val cats = categories.await()
        rows.mapNotNull { s ->
            val id = text(s, "stream_id")?.ifEmpty { null } ?: return@mapNotNull null
            val name = text(s, "name")?.ifEmpty { null } ?: tr("Chaîne %s", id)
            ChannelItem(
                id = "xtream-$id", name = name,
                streamUrl = "${account.baseUrl}/live/${seg(account.username)}/${seg(account.password)}/$id.$ext",
                logo = nonempty(text(s, "stream_icon")),
                group = cats[text(s, "category_id") ?: ""] ?: "Sans groupe",
                tvgId = nonempty(text(s, "epg_channel_id")), tvgName = name, xtreamId = id,
                archiveDays = if (text(s, "tv_archive") == "1") maxOf(text(s, "tv_archive_duration")?.toIntOrNull() ?: 1, 1) else 0,
            )
        }
    }

    suspend fun movies(account: XtreamAccount): List<ChannelItem> {
        val categories = categoriesFor(account, "get_vod_categories")
        val rows = rows(get(account, "get_vod_streams"))
        remember(account, "get_vod_streams", rows)
        return rows.mapNotNull { movie(account, it, categories) }
    }

    suspend fun series(account: XtreamAccount): List<ChannelItem> {
        val categories = categoriesFor(account, "get_series_categories")
        val rows = rows(get(account, "get_series"))
        remember(account, "get_series", rows)
        return rows.mapNotNull { series(it, categories) }
    }

    private fun movie(account: XtreamAccount, s: JsonElement, categories: Map<String, String>): ChannelItem? {
        val id = text(s, "stream_id")?.ifEmpty { null } ?: return null
        val ext = nonempty(text(s, "container_extension")) ?: "mp4"
        return ChannelItem(
            id = "xvod-$id", name = text(s, "name")?.ifEmpty { null } ?: "Film $id",
            streamUrl = "${account.baseUrl}/movie/${seg(account.username)}/${seg(account.password)}/$id.$ext",
            logo = nonempty(text(s, "stream_icon")), group = categories[text(s, "category_id") ?: ""] ?: tr("Films"),
            kind = ChannelKind.Movie, xtreamId = id, rating = rating(text(s, "rating")), year = nonempty(text(s, "year")),
        )
    }

    private fun series(s: JsonElement, categories: Map<String, String>): ChannelItem? {
        val id = text(s, "series_id")?.ifEmpty { null } ?: return null
        return ChannelItem(
            id = "xseries-$id", name = text(s, "name")?.ifEmpty { null } ?: tr("Série %s", id),
            logo = nonempty(text(s, "cover")), group = categories[text(s, "category_id") ?: ""] ?: tr("Séries"),
            kind = ChannelKind.Series, xtreamId = id, rating = rating(text(s, "rating")),
            year = nonempty(text(s, "releaseDate"))?.take(4),
        )
    }

    // -------------------- search --------------------

    /** Films then series matching the query, from the provider. Panels take
     *  a search= parameter on the list endpoints; many ignore it and send
     *  everything, so rows are also matched here, and such a full answer is
     *  kept for 15 minutes so the next search filters it locally. */
    suspend fun search(account: XtreamAccount, rawQuery: String, limit: Int = 40): List<ChannelItem> = coroutineScope {
        val query = rawQuery.trim().ifEmpty { return@coroutineScope emptyList() }
        val films = async { searchRows(account, "get_vod_streams", query) }
        val series = async { searchRows(account, "get_series", query) }
        val vodCategories = async { runCatching { categoriesFor(account, "get_vod_categories") }.getOrDefault(emptyMap()) }
        val seriesCategories = async { runCatching { categoriesFor(account, "get_series_categories") }.getOrDefault(emptyMap()) }
        val result = films.await().filter { titleMatches(text(it, "name"), query) }.take(limit).mapNotNull { movie(account, it, vodCategories.await()) } +
            series.await().filter { titleMatches(text(it, "name"), query) }.take(limit).mapNotNull { series(it, seriesCategories.await()) }
        result.take(limit)
    }

    private suspend fun searchRows(account: XtreamAccount, action: String, query: String): List<JsonElement> {
        remembered(account, action)?.let { return it }
        return try {
            rows(get(account, action, "&search=${enc(query)}")).also { if (it.size >= 500) remember(account, action, it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Every word of the query in the name, ignoring case and accents. */
    fun titleMatches(name: String?, query: String): Boolean {
        if (name.isNullOrEmpty()) return false
        val haystack = fold(name)
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in haystack }
    }

    // Full catalogues seen this session, per account and action, 15 minutes.
    private val catalogues = ConcurrentHashMap<String, Pair<Long, List<JsonElement>>>()
    private val categoryCache = ConcurrentHashMap<String, Map<String, String>>()

    private fun key(a: XtreamAccount, action: String) = "${a.baseUrl}|${a.username}|$action"
    private fun remember(a: XtreamAccount, action: String, rows: List<JsonElement>) {
        catalogues[key(a, action)] = System.currentTimeMillis() to rows
    }
    private fun remembered(a: XtreamAccount, action: String) =
        catalogues[key(a, action)]?.takeIf { System.currentTimeMillis() - it.first < 15 * 60_000 }?.second

    private suspend fun categoriesFor(account: XtreamAccount, action: String): Map<String, String> =
        categoryCache[key(account, action)] ?: categories(get(account, action)).also { categoryCache[key(account, action)] = it }

    suspend fun seriesInfo(account: XtreamAccount, seriesId: String): XtreamSeries {
        val json = get(account, "get_series_info", "&series_id=${enc(seriesId)}") as? JsonObject ?: return XtreamSeries(tr("Série %s", seriesId))
        val info = json["info"] as? JsonObject
        val episodes = mutableListOf<XtreamEpisode>()
        // An object keyed by season, or (some panels) an array of arrays.
        val groups: List<Pair<String, JsonElement>> = when (val bySeason = json["episodes"]) {
            is JsonObject -> bySeason.entries.map { it.key to it.value }
            is JsonArray -> bySeason.mapIndexed { i, v -> (i + 1).toString() to v }
            else -> emptyList()
        }
        for ((key, list) in groups) {
            (list as? JsonArray)?.forEachIndexed { index, e ->
                val id = text(e, "id")?.ifEmpty { null } ?: return@forEachIndexed
                val season = text(e, "season")?.toIntOrNull() ?: key.toIntOrNull() ?: 1
                val number = text(e, "episode_num")?.toIntOrNull() ?: (index + 1)
                val epInfo = (e as? JsonObject)?.get("info") as? JsonObject
                val ext = nonempty(text(e, "container_extension")) ?: "mp4"
                episodes += XtreamEpisode(
                    id = id, season = season, number = number,
                    title = nonempty(text(e, "title")) ?: tr("Épisode %s", number),
                    plot = epInfo?.let { nonempty(text(it, "plot")) }, image = epInfo?.let { nonempty(text(it, "movie_image")) },
                    duration = epInfo?.let { nonempty(text(it, "duration")) },
                    streamUrl = "${account.baseUrl}/series/${seg(account.username)}/${seg(account.password)}/$id.$ext",
                )
            }
        }
        val backdrop = when (val bp = info?.get("backdrop_path")) {
            is JsonArray -> bp.mapNotNull { (it as? JsonPrimitive)?.content?.ifEmpty { null } }.firstOrNull()
            is JsonPrimitive -> nonempty(bp.content)
            else -> null
        }
        return XtreamSeries(
            name = info?.let { nonempty(text(it, "name")) } ?: tr("Série %s", seriesId),
            plot = info?.let { nonempty(text(it, "plot")) }, cover = info?.let { nonempty(text(it, "cover")) },
            backdrop = backdrop, genre = info?.let { nonempty(text(it, "genre")) },
            released = info?.let { nonempty(text(it, "releaseDate")) }, rating = info?.let { rating(text(it, "rating")) },
            episodes = episodes.sortedWith(compareBy({ it.season }, { it.number })),
        )
    }

    // -------------------- guide & catch-up --------------------

    /** Now and next few programmes of a live stream. */
    suspend fun shortEpg(account: XtreamAccount, streamId: String): List<XtreamProgramme> =
        runCatching { programmes(get(account, "get_short_epg", "&stream_id=$streamId&limit=6")) }.getOrDefault(emptyList())

    /** The past programmes that can be replayed, most recent first. */
    suspend fun catchup(account: XtreamAccount, streamId: String): List<XtreamProgramme> = runCatching {
        val now = Instant.now()
        programmes(get(account, "get_simple_data_table", "&stream_id=$streamId"))
            .filter { it.end <= now.plusSeconds(60) && it.start >= now.minusSeconds(14 * 86_400) }
            .sortedByDescending { it.start }
    }.getOrDefault(emptyList())

    private val timeshiftFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm").withZone(ZoneOffset.UTC)

    /** /timeshift/{user}/{pass}/{minutes}/{yyyy-MM-dd:HH-mm}/{id}.ts — the
     *  start in the server's time, which panels give as UTC. */
    fun timeshiftUrl(account: XtreamAccount, streamId: String, p: XtreamProgramme) =
        "${account.baseUrl}/timeshift/${seg(account.username)}/${seg(account.password)}/${p.durationMinutes}/${timeshiftFormat.format(p.start)}/$streamId.ts"

    /** The provider's full XMLTV guide. */
    fun guideUrl(account: XtreamAccount) =
        "${account.baseUrl}/xmltv.php?username=${enc(account.username)}&password=${enc(account.password)}"

    // -------------------- plumbing --------------------

    private val listingFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun programmes(json: JsonElement): List<XtreamProgramme> = (json["epg_listings"] as? JsonArray).orEmpty().mapNotNull { e ->
        val st = text(e, "start_timestamp")?.toLongOrNull()
        val et = text(e, "stop_timestamp")?.toLongOrNull()
        val (start, end) = if (st != null && et != null) Instant.ofEpochSecond(st) to Instant.ofEpochSecond(et) else {
            val s = runCatching { LocalDateTime.parse(text(e, "start"), listingFormat).toInstant(ZoneOffset.UTC) }.getOrNull() ?: return@mapNotNull null
            val en = runCatching { LocalDateTime.parse(text(e, "end") ?: text(e, "stop"), listingFormat).toInstant(ZoneOffset.UTC) }.getOrNull() ?: return@mapNotNull null
            s to en
        }
        XtreamProgramme(start, end, base64(text(e, "title")) ?: "", base64(text(e, "description")), text(e, "has_archive") == "1")
    }

    /** Titles and descriptions are base64 on most panels, plain on some. */
    private fun base64(s: String?): String? {
        if (s.isNullOrEmpty()) return s
        return runCatching { Base64.getDecoder().decode(s).decodeToString().ifEmpty { s } }.getOrDefault(s)
    }

    private fun categories(json: JsonElement): Map<String, String> = rows(json)
        .mapNotNull { c -> text(c, "category_id")?.let { id -> nonempty(text(c, "category_name"))?.let { id to it } } }
        .distinctBy { it.first }.toMap()

    private fun rows(json: JsonElement): List<JsonElement> = (json as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private operator fun JsonElement?.get(name: String): JsonElement? = (this as? JsonObject)?.get(name)

    private suspend fun get(account: XtreamAccount, action: String?, extra: String = ""): JsonElement {
        if (account.baseUrl.isEmpty()) throw IllegalStateException(tr("Adresse du serveur Xtream invalide."))
        val url = "${account.baseUrl}/player_api.php?username=${enc(account.username)}&password=${enc(account.password)}" +
            (action?.let { "&action=$it" } ?: "") + extra
        val raw = withContext(Dispatchers.IO) {
            Http.client.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException(tr("Le serveur Xtream a répondu %s.", r.code))
                    r.body.string()
                }
        }
        val text = raw.trim('﻿', ' ', '\r', '\n', '\t')
        if (text.isEmpty()) return JsonNull
        val json = try {
            Storage.json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw IllegalStateException(tr("Réponse Xtream illisible."))
        }
        // An object where a list was expected: an error envelope; bad
        // credentials say so with user_info.auth = 0.
        if (action != null && action.startsWith("get_") && action !in setOf("get_series_info", "get_short_epg", "get_simple_data_table") &&
            json is JsonObject && text(json["user_info"], "auth") == "0"
        ) throw AuthException()
        return json
    }

    /** A property as text, whether the panel sent a string or a number. */
    private fun text(e: JsonElement?, name: String): String? {
        val v = (e as? JsonObject)?.get(name) as? JsonPrimitive ?: return null
        if (v is kotlinx.serialization.json.JsonNull) return null
        return v.booleanOrNull?.let { if (!v.isString) (if (it) "1" else "0") else null } ?: v.content
    }

    private fun nonempty(s: String?) = s?.trim()?.ifEmpty { null }
    private fun rating(s: String?) = s?.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** Path segment escaping strict enough for passwords with / ? # : @ %. */
    private fun seg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Lower case, accents removed, punctuation as spaces. */
    fun fold(s: String): String = buildString {
        Normalizer.normalize(s, Normalizer.Form.NFD).forEach { ch ->
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) return@forEach
            append(if (ch.isLetterOrDigit()) ch.lowercaseChar() else ' ')
        }
    }

    /** Letters and digits only, case and accents folded. */
    fun nameKey(s: String): String = fold(s).filter { it != ' ' }
}
