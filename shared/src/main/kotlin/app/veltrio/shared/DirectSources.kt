package app.veltrio.shared

import android.util.Log
import app.veltrio.shared.iptv.ChannelItem
import app.veltrio.shared.iptv.ChannelKind
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.iptv.PlaylistRef
import app.veltrio.shared.iptv.XtreamClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import uniffi.veltrio.StreamSource
import uniffi.veltrio.Stream as CoreStream

/** Ready-to-play copies of an IMDB title that don't come from an addon: a
 *  file on the user's Jellyfin, Emby or Plex server, or a film or episode on
 *  their IPTV provider. Listed first among the title's streams. */
object DirectSources {
    val anyConfigured get() = MediaServers.connected.isNotEmpty() || IptvStore.playlists.value.any { it.isXtream }

    /** Every connected server and every Xtream playlist, in parallel.
     *  [imdbId]: the title's IMDB id; for a series, the series'. */
    suspend fun find(imdbId: String, title: String, year: Int?, season: Int?, episode: Int?): List<StreamItem> = coroutineScope {
        (MediaServers.connected.map { async { server(it, imdbId, title, year, season, episode) } } +
            IptvStore.playlists.value.filter { it.isXtream }.map { async { iptv(it, title, year, season, episode) } })
            .awaitAll().flatten()
    }

    fun direct(origin: String, name: String, detail: String?, languages: List<String>, url: String) = StreamItem(
        CoreStream(name, listOfNotNull(detail, languages.joinToString(" ").ifEmpty { null }).filter { it.isNotEmpty() }.joinToString(" · "), null, StreamSource.Url(url), emptyList(), null),
        origin, origin = origin.uppercase(),
    )

    private suspend fun server(server: MediaServer, imdbId: String, title: String, year: Int?, season: Int?, episode: Int?) = try {
        val matches = if (season != null && episode != null) server.findEpisode(imdbId, title, year, season, episode) else server.findMovie(imdbId, title, year)
        matches.map { m -> direct(server.name, m.name, m.video, m.audioLanguages.map(::shortLanguage), m.streamUrl) }
    } catch (e: Exception) {
        Log.w("Veltrio", "[direct] ${server.name}: ${e.message}")
        emptyList()
    }

    /** Every Xtream playlist is asked, not only one: the user wants to see
     *  every copy they can play. */
    private suspend fun iptv(playlist: PlaylistRef, title: String, year: Int?, season: Int?, episode: Int?): List<StreamItem> {
        val account = playlist.xtream ?: return emptyList()
        val label = if (IptvStore.playlists.value.count { it.isXtream } > 1) playlist.name else null
        fun detail(group: String) = listOfNotNull(group.ifEmpty { null }, label).joinToString(" · ")
        fun language(item: ChannelItem) = listOfNotNull(XtreamMatching.language(item.group, item.name))
        return try {
            val found = XtreamClient.search(account, title, limit = 50)
                .filter { it.kind == (if (season == null) ChannelKind.Movie else ChannelKind.Series) && XtreamMatching.isSameTitle(it.name, title, year) }
            if (season == null || episode == null) return found.map { direct("IPTV", it.name, detail(it.group), language(it), it.streamUrl) }
            // A title rarely has more than a couple of copies (one per
            // language); cap the info calls so a loose match can't fan out.
            found.filter { it.xtreamId != null }.take(4).mapNotNull { series ->
                val info = XtreamClient.seriesInfo(account, series.xtreamId!!)
                info.episodes.firstOrNull { it.season == season && it.number == episode }?.let { ep ->
                    direct("IPTV", "${series.name} · ${ep.title}", detail(series.group), language(series), ep.streamUrl)
                }
            }
        } catch (e: Exception) {
            Log.w("Veltrio", "[direct] ${playlist.name}: ${e.message}")
            emptyList()
        }
    }

    /** Servers report ISO 639-2 codes; show the short tags the IPTV rows use. */
    private fun shortLanguage(code: String) = when (code.lowercase()) {
        "fre", "fra" -> "FR"; "eng" -> "EN"; "ara" -> "AR"; "ger", "deu" -> "DE"; "spa" -> "ES"; "ita" -> "IT"
        "dut", "nld" -> "NL"; "tur" -> "TR"; "por" -> "PT"; "jpn" -> "JA"; "kor" -> "KO"; "chi", "zho" -> "ZH"
        "rus" -> "RU"; "pol" -> "PL"
        else -> code.uppercase()
    }
}

/** Matching provider entries to a title, as on macOS. */
object XtreamMatching {
    /** Provider names dress titles up: "NF - The Irishman (2019)", "FR | Dune
     *  [MULTI]". Both sides are stripped down to the bare title; when both
     *  carry a year they must agree to within one. */
    fun isSameTitle(providerName: String, title: String, year: Int?): Boolean {
        // A trailing number may be part of the title ("Blade Runner 2049") or
        // a year the provider appended ("Dune 2021"). Equal with it kept: only
        // a bracketed year can disagree. Equal only once it's dropped: it was
        // a year, and it has to agree.
        val theirs = when {
            normalized(providerName, true) == normalized(title, true) -> year(providerName, trailing = false)
            normalized(providerName) == normalized(title) -> year(providerName, trailing = true)
            else -> return false
        }
        return year == null || theirs == null || kotlin.math.abs(theirs - year) <= 1
    }

    private val leadingTag = Regex("""^\s*(\[[^\]]*\]|\([^)]*\)|[A-Z0-9+]{1,8}\s*(\||\s-\s))\s*""")
    private val bracketed = Regex("""\[[^\]]*\]|\([^)]*\)""")
    private val trailingYear = Regex("""\s(19|20)\d{2}\s*$""")
    private val trailingYearCapture = Regex("""\s((?:19|20)\d{2})\s*$""")
    private val bracketedYear = Regex("""[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]""")
    private val nameTag = Regex("""^\s*(\[[^\]]*\]|[A-Z]{2,8}(?=\s*(\||\s-\s|:\s)))""")

    fun normalized(raw: String, keepTrailingYear: Boolean = false): String {
        var s = raw
        // Leading tags: "NF - ", "FR | ", "[4K] ", "VOSTFR: "… A tag is upper
        // case and set off by a pipe or a spaced dash, so "Spider-Man" and
        // "ALIEN: Romulus" keep their first word.
        while (true) {
            val m = leadingTag.find(s) ?: break
            if (m.value.isEmpty()) break
            s = s.substring(m.value.length)
        }
        s = bracketed.replace(s, " ")
        if (!keepTrailingYear) s = trailingYear.replace(s, " ")
        s = XtreamClient.fold(s.replace("&", " and "))
        return s.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** A bracketed year, "(2019)", and when trailing, a bare one at the end. */
    fun year(name: String, trailing: Boolean = true): Int? {
        bracketedYear.findAll(name).lastOrNull()?.let { return it.groupValues[1].toInt() }
        if (trailing) trailingYearCapture.find(name)?.let { return it.groupValues[1].toInt() }
        return null
    }

    /** Language of a provider entry, from its category ("FR | Films",
     *  "VOSTFR - Séries"), failing that from its name's leading tag. */
    fun language(category: String?, name: String?): String? {
        for (text in listOf(category, name?.let { nameTag.find(it)?.value })) {
            if (text.isNullOrEmpty()) continue
            for (token in text.uppercase().split(Regex("""[^\p{L}]+"""))) languageCodes[token]?.let { return it }
        }
        return null
    }

    private val languageCodes = mapOf(
        "FR" to "FR", "FRA" to "FR", "FRE" to "FR", "FRENCH" to "FR", "FRANCE" to "FR", "VF" to "VF", "VFF" to "VF",
        "VFQ" to "VFQ", "TRUEFRENCH" to "VF", "VOSTFR" to "VOSTFR", "VOST" to "VOSTFR",
        "EN" to "EN", "ENG" to "EN", "ENGLISH" to "EN", "UK" to "EN", "US" to "EN", "USA" to "EN",
        "AR" to "AR", "ARA" to "AR", "ARABIC" to "AR", "DE" to "DE", "GER" to "DE", "GERMAN" to "DE", "DEUTSCH" to "DE",
        "ES" to "ES", "ESP" to "ES", "SPA" to "ES", "SPANISH" to "ES", "IT" to "IT", "ITA" to "IT", "ITALIAN" to "IT",
        "NL" to "NL", "DUTCH" to "NL", "TR" to "TR", "TURK" to "TR", "TURKISH" to "TR",
        "PT" to "PT", "POR" to "PT", "BR" to "PT-BR", "PORTUGUESE" to "PT", "MULTI" to "MULTI", "MULTISUB" to "MULTI",
    )
}
