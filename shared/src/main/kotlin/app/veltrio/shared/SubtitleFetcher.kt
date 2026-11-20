package app.veltrio.shared

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

/** A subtitle from an addon's `subtitles` resource. */
data class AddonSubtitle(val url: String, val lang: String?, val addonName: String) {
    val language: String get() = lang?.let { Locale.forLanguageTag(it).displayLanguage.ifEmpty { null }?.replaceFirstChar(Char::uppercase) } ?: "Langue inconnue"
}

/** Subtitles from every enabled addon that serves them (Stremio:
 *  GET {base}/subtitles/{type}/{id}.json), in parallel, 10 s each — as the
 *  macOS SubtitleFetcher. */
object SubtitleFetcher {
    /** [id]: the episode id for a series (tt…:s:e), else the title id. */
    suspend fun fetch(type: String, id: String): List<AddonSubtitle> = coroutineScope {
        Core.enabledAddons.filter { it.serves("subtitles", type) }.map { addon ->
            async {
                withTimeoutOrNull(10_000) {
                    runCatching {
                        val root = addon.transportUrl.trim().removeSuffix("/manifest.json").trimEnd('/')
                        val url = "$root/subtitles/$type/${URLEncoder.encode(id, "UTF-8").replace("%3A", ":")}.json"
                        Http.json(Request.Builder().url(url).build(), addon.name).array("subtitles").mapNotNull { s ->
                            s.str("url")?.takeIf { it.isNotEmpty() }?.let { AddonSubtitle(it, s.str("lang"), addon.name) }
                        }
                    }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll().flatten().sortedBy { it.language }
    }

    /** The first subtitle in one of the preferred languages (any synonym,
     *  then prefix match). */
    fun pick(subtitles: List<AddonSubtitle>, preferred: List<String>): AddonSubtitle? {
        for (code in preferred) {
            val synonyms = PlayerPrefs.synonyms(code)
            subtitles.firstOrNull { (it.lang ?: "").lowercase() in synonyms }?.let { return it }
            subtitles.firstOrNull { s -> synonyms.any { (s.lang ?: "").lowercase().startsWith(it) } }?.let { return it }
        }
        return null
    }
}
