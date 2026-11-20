package app.veltrio.shared

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import uniffi.veltrio.StremioClient
import uniffi.veltrio.StreamSource
import uniffi.veltrio.TorrentEngine
import uniffi.veltrio.VeltrioException
import java.net.URI

/** The app's single entry point into the Rust core: installed addons,
 *  catalogs, metadata, streams and their resolution. The counterpart of the
 *  macOS AppStore. */
object Core {
    private val client by lazy { StremioClient() }
    private val torrents by lazy { TorrentEngine() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class Suggestion(val name: String, val url: String, val blurb: String)

    val suggestedAddons = listOf(
        Suggestion("Cinemeta", "https://v3-cinemeta.strem.io/manifest.json", "Les catalogues officiels de films et de séries (via TMDB)."),
        Suggestion("OpenSubtitles v3", "https://opensubtitles-v3.strem.io/manifest.json", "Des sous-titres dans plus de 50 langues."),
        Suggestion("WatchHub", "https://watchhub.strem.io/manifest.json", "Des liens de streaming légaux : Apple TV, Prime, etc."),
    )

    /** Where torrents played without debrid are kept: the core writes
     *  under $HOME/.cache/veltrio/torrents, so HOME points into the app's
     *  cache (an Android process has none of its own it can write to). */
    lateinit var torrentCache: java.io.File
        private set

    internal fun init(context: Context) {
        android.system.Os.setenv("HOME", context.cacheDir.absolutePath, true)
        android.system.Os.setenv("TMPDIR", context.cacheDir.absolutePath, true)
        torrentCache = java.io.File(context.cacheDir, ".cache/veltrio/torrents")
        _addons.value = Storage.load(ADDONS_FILE, ListSerializer(AddonInfo.serializer()), emptyList())
        _disabled.value = Storage.load(DISABLED_FILE, SetSerializer(String.serializer()), emptySet())
    }

    // -------------------- addons --------------------

    private const val ADDONS_FILE = "addons.json"
    private const val DISABLED_FILE = "addons-disabled.json"

    private val _addons = MutableStateFlow<List<AddonInfo>>(emptyList())
    private val _disabled = MutableStateFlow<Set<String>>(emptySet())
    val addons: StateFlow<List<AddonInfo>> = _addons
    val disabled: StateFlow<Set<String>> = _disabled
    val enabledAddons get() = _addons.value.filter { it.id !in _disabled.value }

    fun isEnabled(addon: AddonInfo) = addon.id !in _disabled.value

    fun isSameAddon(a: String, b: String) = baseUrl(a) == baseUrl(b)

    fun isInstalled(manifestUrl: String) = _addons.value.any { baseUrl(it.transportUrl) == baseUrl(manifestUrl) }

    /** The core keeps the addon's base URL; suggestions use the manifest's. */
    private fun baseUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.endsWith("/manifest.json", ignoreCase = true)) u = u.dropLast("/manifest.json".length)
        return u.lowercase()
    }

    /** Installs the addon behind a manifest URL. Returns an error message, or null. */
    suspend fun installAddon(raw: String): String? {
        var url = raw.trim()
        // stremio:// links are https:// manifests in disguise.
        if (url.startsWith("stremio://", ignoreCase = true)) url = "https://" + url.drop("stremio://".length)
        val scheme = runCatching { URI(url).scheme }.getOrNull()
        if (scheme != "http" && scheme != "https") return "L'URL d'un addon doit commencer par http:// ou https://."
        return try {
            val addon = AddonInfo.from(client.fetchManifest(url))
            if (_addons.value.any { it.id == addon.id }) return "L'addon ${addon.name} est déjà installé."
            saveAddons(_addons.value + addon)
            null
        } catch (e: Exception) {
            humanize(e)
        }
    }

    fun removeAddon(addon: AddonInfo) {
        _disabled.value = _disabled.value - addon.id
        Storage.save(DISABLED_FILE, SetSerializer(String.serializer()), _disabled.value)
        saveAddons(_addons.value.filter { it.id != addon.id })
    }

    fun setEnabled(addon: AddonInfo, enabled: Boolean) {
        _disabled.value = if (enabled) _disabled.value - addon.id else _disabled.value + addon.id
        Storage.save(DISABLED_FILE, SetSerializer(String.serializer()), _disabled.value)
    }

    fun move(addon: AddonInfo, delta: Int) {
        val list = _addons.value.toMutableList()
        val from = list.indexOfFirst { it.id == addon.id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        saveAddons(list)
    }

    private fun saveAddons(list: List<AddonInfo>) {
        _addons.value = list
        Storage.save(ADDONS_FILE, ListSerializer(AddonInfo.serializer()), list)
    }

    // -------------------- catalogs --------------------

    private val catalogCache = mutableMapOf<String, Deferred<List<PosterItem>>>()

    /** A catalog's first page, cached for the session so going back to
     *  Home doesn't refetch every row. */
    suspend fun loadCatalog(addon: AddonInfo, catalog: CatalogInfo): List<PosterItem> {
        val key = "${addon.transportUrl}|${catalog.type}|${catalog.id}"
        val task = synchronized(catalogCache) {
            catalogCache[key]?.takeUnless { it.isCompleted && it.getCompletionExceptionOrNull() != null }
                ?: scope.async { withTimeout(20_000) { fetchCatalog(addon, catalog, emptyList()) } }.also { catalogCache[key] = it }
        }
        return task.await()
    }

    /** Further pages for the "See all" grid (Stremio's skip= extra). */
    suspend fun loadCatalogPage(addon: AddonInfo, catalog: CatalogInfo, skip: Int): List<PosterItem> = try {
        withTimeout(15_000) { fetchCatalog(addon, catalog, if (skip > 0) listOf("skip=$skip") else emptyList()) }
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun fetchCatalog(addon: AddonInfo, catalog: CatalogInfo, extras: List<String>) =
        client.fetchCatalog(addon.transportUrl, catalog.type, catalog.id, extras).map { PosterItem.from(it, addon) }

    /** Every browsable catalog of the enabled addons, in order. */
    fun homeCatalogs() = enabledAddons.flatMap { a -> a.catalogs.filter { it.isBrowsable }.map { a to it } }

    // -------------------- metadata --------------------

    /** The addon to open a title's page with: the preferred one when still
     *  installed, else the first metadata addon for the type. */
    fun metaAddon(type: String, preferredId: String? = null) =
        _addons.value.firstOrNull { it.id == preferredId } ?: _addons.value.firstOrNull { it.serves("meta", type) }

    /** Tries the preferred addon, then every other enabled addon serving
     *  metadata for the type; the first answer wins. */
    suspend fun fetchDetails(preferred: AddonInfo, type: String, id: String): TitleDetails {
        val ordered = listOf(preferred) + enabledAddons.filter { it.id != preferred.id && it.serves("meta", type) }
        var last: Exception? = null
        for (addon in ordered) {
            try {
                return TitleDetails.from(withTimeout(15_000) { client.fetchMeta(addon.transportUrl, type, id) }, addon)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException(last?.let { humanize(it) } ?: "Aucun addon n'a pu fournir la fiche.")
    }

    // -------------------- streams --------------------

    /** Streams from every enabled addon serving this type, fetched in
     *  parallel, 15 s each; an addon that fails or hangs is left out. Best first. */
    suspend fun fetchStreams(type: String, id: String): List<StreamItem> {
        val eligible = enabledAddons.filter { it.serves("stream", type) }
        return eligible.map { addon ->
            scope.async {
                try {
                    withTimeout(15_000) { client.fetchStreams(addon.transportUrl, type, id) }
                        .map { StreamItem(it, addon.name, score(it)) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().sortedByDescending { it.score }
    }

    private val uhd = Regex("""(?<![A-Z0-9])(4K|2160P|UHD)(?![A-Z0-9])""")
    private val resolutions = listOf("1080P" to "1080p", "720P" to "720p", "480P" to "480p")

    /** The resolution a stream's name and title announce. Matched as whole
     *  words: release sites and groups carry them too ("Wolfmax4k"), and
     *  those mustn't make a 1080p file read as 4K. */
    fun resolution(upperCased: String): String {
        if (uhd.containsMatchIn(upperCased)) return "4K"
        return resolutions.firstOrNull { (tag, _) -> Regex("""(?<![A-Z0-9])$tag(?![A-Z0-9])""").containsMatchIn(upperCased) }?.second ?: ""
    }

    /** Same ranking as macOS: direct links before torrents, then resolution,
     *  HDR / Dolby / Atmos, then seeds. */
    fun score(s: uniffi.veltrio.Stream): Int {
        val hay = "${s.name} ${s.title}".uppercase()
        var score = when (s.source) {
            is StreamSource.Url -> 5_000
            is StreamSource.External -> 3_000
            else -> 0
        }
        score += when (resolution(hay)) {
            "4K" -> 2_000
            "1080p" -> 1_500
            "720p" -> 1_000
            "480p" -> 500
            else -> 0
        }
        if ("HDR" in hay) score += 200
        if ("DOLBY" in hay) score += 150
        if ("ATMOS" in hay) score += 150
        Regex("""👤\s*(\d+)""").find(s.title ?: "")?.groupValues?.get(1)?.toIntOrNull()?.let { score += minOf(it, 500) }
        return score
    }

    /** Turns a stream into a URL the player can open: links as they are;
     *  torrents through a debrid service when one is connected (a direct
     *  HTTPS link, no seeding), else the core's local engine. */
    suspend fun resolve(item: StreamItem, season: Int? = null, episode: Int? = null): String {
        item.localPath?.let { return it }
        return when (val source = item.stream.source) {
            is StreamSource.Url -> if (isPlayable(source.url)) source.url else error("Ce flux ne se lit pas dans l'app.")
            is StreamSource.Torrent -> {
                if (DebridService.anyConnected) DebridService.resolve(source.infoHash, season, episode)?.let { return it }
                streaming += source.infoHash.lowercase()
                torrents.startStream(source.infoHash, source.sources, source.fileIdx)
            }
            else -> error("Ce flux ne se lit pas dans l'app.")
        }
    }

    /** Links meant for the browser (YouTube, external pages). */
    fun externalUrl(item: StreamItem): String? = when (val s = item.stream.source) {
        is StreamSource.YouTube -> "https://www.youtube.com/watch?v=${s.videoId}"
        is StreamSource.External -> s.externalUrl.takeIf { it.startsWith("http") }
        else -> null
    }

    /** Torrents currently streamed: their files are kept when the cache is cleared. */
    private val streaming = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Size of the torrent cache, in bytes. */
    /** Space the torrent cache takes, in bytes: the engine creates sparse
     *  files of the whole torrent's size, so blocks are counted, not lengths. */
    fun torrentCacheSize(): Long = torrentCache.walkBottomUp().filter { it.isFile }.sumOf {
        runCatching { android.system.Os.stat(it.path).st_blocks * 512 }.getOrDefault(it.length())
    }

    /** Frees the torrent cache; a torrent being played isn't touched. */
    fun clearTorrentCache() {
        torrentCache.listFiles()?.forEach { f ->
            if (streaming.none { f.name.contains(it, ignoreCase = true) }) f.deleteRecursively()
        }
    }

    /** Stops seeding a torrent once the player closes. */
    fun stopTorrent(infoHash: String) {
        streaming -= infoHash.lowercase()
        scope.launch { runCatching { torrents.stopStream(infoHash, false) } }
    }

    /** A hostile addon shouldn't make the app open file:// or similar. */
    fun isPlayable(url: String) = runCatching { URI(url).scheme }.getOrNull() in
        setOf("http", "https", "rtmp", "rtmps", "rtsp", "udp")

    // -------------------- search --------------------

    /** One row per searchable catalog that answered with results, in addon
     *  order. 10 s per catalog. */
    suspend fun search(query: String): List<CatalogRow> {
        val targets = enabledAddons.flatMap { a -> a.catalogs.filter { it.isSearchable }.map { a to it } }
        return targets.map { (a, c) ->
            scope.async {
                try {
                    val items = withTimeout(10_000) { fetchCatalog(a, c, listOf("search=$query")) }
                    if (items.isEmpty()) null
                    else CatalogRow(typeLabel(c.type, plural = true), "${a.name} · ${c.name}", "", a, null, items)
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    // -------------------- helpers --------------------

    fun typeLabel(type: String, plural: Boolean = false) = when (type) {
        "movie" -> if (plural) "Films" else "Film"
        "series" -> if (plural) "Séries" else "Série"
        "channel" -> if (plural) "Chaînes" else "Chaîne"
        "tv" -> "TV"
        else -> type
    }

    fun humanize(e: Throwable): String = when (e) {
        is VeltrioException.Network -> "Réseau : ${e.message}"
        is VeltrioException.Parse -> "Réponse illisible : ${e.message}"
        is VeltrioException.InvalidUrl -> "URL invalide : ${e.message}"
        is VeltrioException.NotFound -> "Introuvable : ${e.message}"
        is VeltrioException.Unsupported -> "Non pris en charge : ${e.message}"
        is VeltrioException.Internal -> "Erreur interne : ${e.message}"
        is kotlinx.coroutines.TimeoutCancellationException -> "Délai dépassé"
        else -> e.message ?: e.toString()
    }
}
