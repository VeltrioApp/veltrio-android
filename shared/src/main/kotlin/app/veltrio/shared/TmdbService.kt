package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Optional TMDB integration for localized titles and synopses (French,
 *  English, Spanish, etc.) on top of Cinemeta / Stremio metadata.
 *  Accepts either a TMDB v3 API key or a v4 Read Access Token. Both the key
 *  and the chosen language live under the `tmdb.` prefix in [SecretStore], so
 *  LAN TV pairing transfers them automatically. */
object TmdbService {
    private const val KEY_SECRET = "tmdb.key"
    private const val LANG_SECRET = "tmdb.lang"
    private const val BASE = "https://api.themoviedb.org/3"

    val languages: List<Pair<String, String>> = listOf(
        "fr-FR" to "Français",
        "en-US" to "English",
        "es-ES" to "Español",
        "de-DE" to "Deutsch",
        "it-IT" to "Italiano",
        "pt-BR" to "Português",
        "ja-JP" to "日本語",
        "" to tr("Désactivé (langue de l'addon)"),
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
    private val cache = ConcurrentHashMap<String, LocalizedMeta>()

    val isConnected: Boolean get() = _state.value.connected
    val language: String get() = _state.value.language

    fun reload() {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() }
        val lang = SecretStore.get(LANG_SECRET) ?: "fr-FR"
        _state.value = _state.value.copy(connected = key != null, language = lang, error = null)
    }

    fun setLanguage(lang: String) {
        SecretStore.set(LANG_SECRET, lang)
        _state.value = _state.value.copy(language = lang)
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
            if (SecretStore.get(LANG_SECRET) == null) SecretStore.set(LANG_SECRET, "fr-FR")
            cache.clear()
            _state.value = _state.value.copy(connected = true, busy = false, error = null)
            true
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = e.message ?: Core.humanize(e))
            false
        }
    }

    fun disconnect() {
        SecretStore.set(KEY_SECRET, null)
        cache.clear()
        _state.value = _state.value.copy(connected = false, error = null)
    }

    /** Returns localized title & synopsis for an IMDb id (`tt...`), or null
     *  when TMDB is not configured, disabled, or has no match. */
    suspend fun localize(imdbId: String, type: String): LocalizedMeta? {
        val key = SecretStore.get(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: return null
        val lang = SecretStore.get(LANG_SECRET)?.takeIf { it.isNotBlank() } ?: return null
        if (!imdbId.startsWith("tt")) return null
        val cacheKey = "$imdbId|$type|$lang"
        cache[cacheKey]?.let { return it }
        return try {
            val req = buildRequest("/find/$imdbId?external_source=imdb_id&language=$lang", key)
            val json = Http.json(req, "TMDB")
            val isSeries = type.equals("series", ignoreCase = true)
            val primary = json.array(if (isSeries) "tv_results" else "movie_results").firstOrNull()
                ?: json.array(if (isSeries) "movie_results" else "tv_results").firstOrNull()
                ?: return null
            val title = (primary.str("title") ?: primary.str("name"))?.takeIf { it.isNotBlank() }
            val overview = primary.str("overview")?.takeIf { it.isNotBlank() }
            LocalizedMeta(title, overview).also { cache[cacheKey] = it }
        } catch (_: Exception) {
            null
        }
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
