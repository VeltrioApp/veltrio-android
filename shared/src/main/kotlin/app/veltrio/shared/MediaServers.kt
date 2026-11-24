package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class MediaServerKind { Jellyfin, Emby, Plex }
enum class MediaSectionKind { Movies, Shows, Mixed }

/** A library on the server: "Films", "Séries TV", "Kids"… */
data class MediaLibrarySection(val id: String, val name: String, val kind: MediaSectionKind)

/** A film or series in a library. */
data class MediaLibraryItem(
    val id: String,
    val title: String,
    val year: Int? = null,
    /** "movie" or "series", as the rest of the app spells them. */
    val type: String,
    /** Set when the server identified the title: it then opens on its title
     *  page, where this copy is listed among the streams. */
    val imdbId: String? = null,
    val posterUrl: String? = null,
    /** Plex plays a media part, known from the listing; Jellyfin and Emby
     *  build the URL from the id. */
    val streamUrl: String? = null,
) {
    val subtitle get() = year?.toString() ?: if (type == "series") tr("SÉRIE") else tr("FILM")
}

/** An episode of a series the server couldn't tie to an IMDB id. */
data class MediaLibraryEpisode(val id: String, val season: Int, val number: Int, val title: String, val image: String? = null, val streamUrl: String? = null) {
    val code get() = "S$season · E$number"
}

data class MediaLibraryPage(val items: List<MediaLibraryItem>, val total: Int)

/** A server's copy of an IMDB title, as the stream list shows it. */
data class MediaServerMatch(val server: MediaServer, val itemId: String, val name: String, val video: String?, val audioLanguages: List<String>, val streamUrl: String)

/** One server's state, as the Sources cards show it. */
data class MediaServerState(
    val connected: Boolean = false,
    val user: String? = null,
    val host: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val pendingCode: String? = null,
    val pendingUrl: String? = null,
)

/** One media server account: Jellyfin and Emby (address, user, password),
 *  Plex (PIN on plex.tv). The token lives in secrets.dat, the rest in
 *  media-servers.json. */
abstract class MediaServer(val kind: MediaServerKind) {
    abstract val name: String
    abstract val color: Long

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val saved: MediaServers.Saved get() = MediaServers.saved(kind)
    protected var token: String? = SecretStore.get("mediaserver.${kind.name}".lowercase())
    protected open val signedIn get() = true
    val isConnected get() = token != null && signedIn

    protected val _state = MutableStateFlow(MediaServerState())
    val state: StateFlow<MediaServerState> = _state
    protected fun update(change: (MediaServerState) -> MediaServerState) = synchronized(this) { _state.value = change(_state.value) }
    protected open val host: String? get() = null
    protected fun publish() = update { it.copy(connected = isConnected, user = saved.userName, host = host) }

    protected fun saveToken(value: String?) {
        token = value
        SecretStore.set("mediaserver.${kind.name}".lowercase(), value)
    }

    abstract fun disconnect()

    /** The account changed (settings received from a phone). */
    open fun reload() {
        token = SecretStore.get("mediaserver.${kind.name}".lowercase())
        publish()
        MediaServers._version.value++
    }

    // Library browsing (Collection) and IMDB matching (title pages).
    abstract suspend fun sections(): List<MediaLibrarySection>
    abstract suspend fun items(section: MediaLibrarySection, start: Int, limit: Int): MediaLibraryPage
    abstract suspend fun episodes(series: MediaLibraryItem): List<MediaLibraryEpisode>
    abstract fun streamUrl(itemId: String): String?
    abstract suspend fun findMovie(imdbId: String, title: String, year: Int?): List<MediaServerMatch>
    abstract suspend fun findEpisode(seriesImdbId: String, title: String, year: Int?, season: Int, episode: Int): List<MediaServerMatch>

    companion object {
        internal val http = Http.client.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()

        /** Title equality for items without an IMDB id: case and accents
         *  ignored, years within one of each other. */
        fun sameTitle(theirs: String, ours: String, theirYear: Int?, year: Int?): Boolean {
            fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase().trim()
            if (fold(theirs) != fold(ours)) return false
            return year == null || theirYear == null || kotlin.math.abs(theirYear - year) <= 1
        }

        fun JsonElement?.int(name: String): Int? = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.intOrNull

        /** GET returning JSON, or null on any failure: browsing a server that
         *  went away shows an empty library, not an error. */
        suspend fun tryGet(request: Request): JsonElement? = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request).execute().use { r -> if (!r.isSuccessful) null else Storage.json.parseToJsonElement(r.body.string()) }
            }.getOrNull()
        }

        fun normalizeHost(raw: String): String {
            val h = raw.trim().trimEnd('/')
            return if (h.isNotEmpty() && "://" !in h) "http://$h" else h
        }

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

/** The three servers, and what's saved about them. */
object MediaServers {
    private const val FILE = "media-servers.json"

    /** Per server: address, user and the install's device id. */
    @Serializable
    data class Saved(val url: String? = null, val userId: String? = null, val userName: String? = null, val deviceId: String? = null)

    private val serializer = MapSerializer(String.serializer(), Saved.serializer())
    private var state: Map<String, Saved> = emptyMap()
    private var loaded = false

    @Synchronized
    fun saved(kind: MediaServerKind): Saved {
        if (!loaded) {
            state = Storage.load(FILE, serializer, emptyMap())
            loaded = true
        }
        val key = kind.name.lowercase()
        val s = state[key]
        // Stable per install: the server lists each device id once and ties
        // its tokens to it.
        if (s?.deviceId != null) return s
        val withId = (s ?: Saved()).copy(deviceId = "veltrio-${UUID.randomUUID().toString().replace("-", "").take(12)}")
        save(kind, withId)
        return withId
    }

    /** What's saved for a server, as it is (sending settings to a TV). */
    @Synchronized
    fun savedOrNull(kind: MediaServerKind): Saved? = saved(kind).takeIf { it.url != null || it.userName != null }

    @Synchronized
    fun save(kind: MediaServerKind, value: Saved) {
        state = state + (kind.name.lowercase() to value)
        Storage.save(FILE, serializer, state)
    }

    val jellyfin by lazy { MediaBrowserServer(MediaServerKind.Jellyfin) }
    val emby by lazy { MediaBrowserServer(MediaServerKind.Emby) }
    val plex by lazy { PlexServer() }
    val all: List<MediaServer> get() = listOf(jellyfin, emby, plex)
    val connected get() = all.filter { it.isConnected }

    /** Bumped when a server connects or disconnects, for the Collection tabs. */
    internal val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
}

/** Jellyfin and Emby: same ancestor, same API; they differ in the auth
 *  headers and the stream URL's token parameter. */
class MediaBrowserServer(kind: MediaServerKind) : MediaServer(kind) {
    override val name = if (kind == MediaServerKind.Jellyfin) tr("Jellyfin") else tr("Emby")
    override val color = if (kind == MediaServerKind.Jellyfin) 0xFF00A4DC else 0xFF52B54B
    override val signedIn get() = saved.userId != null
    override val host get() = saved.url?.let { runCatching { URI(it).host }.getOrNull() }
    private val baseUrl get() = saved.url ?: ""

    init {
        publish()
    }

    private fun Request.Builder.authorize(token: String?): Request.Builder {
        var value = "MediaBrowser Client=\"Veltrio\", Device=\"Android\", DeviceId=\"${saved.deviceId}\", Version=\"0.1.0\""
        if (token != null) value += ", Token=\"$token\""
        // Recent Jellyfin reads Authorization; older servers and Emby the legacy header.
        header("X-Emby-Authorization", value)
        if (kind == MediaServerKind.Jellyfin) header("Authorization", value) else if (token != null) header("X-Emby-Token", token)
        return this
    }

    private fun get(pathAndQuery: String) = Request.Builder().url(baseUrl + pathAndQuery).authorize(token).build()

    fun connect(host: String, user: String, password: String) = scope.launch {
        update { it.copy(error = null, busy = true) }
        val error = signIn(normalizeHost(host), user.trim(), password)
        publish()
        update { it.copy(error = error, busy = false) }
        MediaServers._version.value++
    }

    /** The error to show, or null once signed in. */
    private suspend fun signIn(url: String, user: String, password: String): String? = withContext(Dispatchers.IO) {
        if (runCatching { URI(url).host }.getOrNull().isNullOrEmpty()) return@withContext tr("Adresse de serveur invalide.")
        // Check a server of this kind answers before sending credentials, so
        // a wrong address or port isn't reported as a wrong password.
        try {
            val info = http.newCall(Request.Builder().url("$url/System/Info/Public").build()).execute().use { r ->
                if (r.isSuccessful) runCatching { Storage.json.parseToJsonElement(r.body.string()) }.getOrNull() else null
            }
            if (info.str("Version") == null) return@withContext if (kind == MediaServerKind.Jellyfin)
                tr("Aucun serveur Jellyfin ne répond à %s. Vérifiez l'adresse et le port (8096 par défaut), et ajoutez le chemin de base si votre serveur en a un, par exemple /jellyfin.", url)
            else tr("Aucun serveur Emby ne répond à %s. Vérifiez l'adresse et le port (8096 par défaut).", url)
            val product = info.str("ProductName") ?: ""
            if (kind == MediaServerKind.Jellyfin && product.contains("emby", true)) return@withContext tr("C'est un serveur Emby : connectez-le avec la carte Emby.")
            if (kind == MediaServerKind.Emby && product.contains("jellyfin", true)) return@withContext tr("C'est un serveur Jellyfin : connectez-le avec la carte Jellyfin.")
        } catch (e: Exception) {
            return@withContext tr("Impossible de joindre %s : %s", url, e.message)
        }
        val body = buildJsonObject { put("Username", user); put("Pw", password) }.toString().toRequestBody("application/json".toMediaType())
        try {
            http.newCall(Request.Builder().url("$url/Users/AuthenticateByName").post(body).authorize(null).build()).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) return@withContext when (r.code) {
                    401 -> tr("Nom d'utilisateur ou mot de passe incorrect.")
                    403 -> tr("Ce compte n'a pas le droit de se connecter (désactivé, ou accès distant coupé pour lui).")
                    else -> tr("%s a refusé la connexion (HTTP %s)%s", name, r.code, if (text.isNotEmpty()) " : " + text.take(200).trim() else "")
                }
                val json = Storage.json.parseToJsonElement(text)
                val accessToken = json.str("AccessToken")
                val userId = json["User"].str("Id")
                if (accessToken == null || userId == null) return@withContext tr("Réponse inattendue du serveur.")
                MediaServers.save(kind, saved.copy(url = url, userId = userId, userName = json["User"].str("Name") ?: user))
                saveToken(accessToken)
                null
            }
        } catch (e: Exception) {
            e.message
        }
    }

    override fun disconnect() {
        saveToken(null)
        MediaServers.save(kind, saved.copy(userId = null, userName = null))
        _state.value = MediaServerState(host = host)
        MediaServers._version.value++
    }

    // -------------------- browsing --------------------

    override suspend fun sections(): List<MediaLibrarySection> {
        val json = if (isConnected) tryGet(get("/Users/${saved.userId}/Views")) else null
        // Music, photos and live TV are left out: the app plays films and series.
        return json.array("Items").mapNotNull { v ->
            val id = v.str("Id") ?: return@mapNotNull null
            val n = v.str("Name") ?: ""
            when (v.str("CollectionType")) {
                "movies" -> MediaLibrarySection(id, n, MediaSectionKind.Movies)
                "tvshows" -> MediaLibrarySection(id, n, MediaSectionKind.Shows)
                null, "mixed", "homevideos" -> MediaLibrarySection(id, n, MediaSectionKind.Mixed)
                else -> null
            }
        }
    }

    override suspend fun items(section: MediaLibrarySection, start: Int, limit: Int): MediaLibraryPage {
        val query = "/Users/${saved.userId}/Items?ParentId=${enc(section.id)}&IncludeItemTypes=Movie,Series&Recursive=true" +
            "&SortBy=SortName&Fields=ProviderIds,ProductionYear&StartIndex=$start&Limit=$limit"
        val json = (if (isConnected) tryGet(get(query)) else null) ?: return MediaLibraryPage(emptyList(), 0)
        val items = json.array("Items").mapNotNull { i ->
            MediaLibraryItem(
                id = i.str("Id") ?: return@mapNotNull null, title = i.str("Name") ?: "", year = i.int("ProductionYear"),
                type = if (i.str("Type") == "Series") "series" else "movie", imdbId = imdb(i), posterUrl = imageUrl(i, 360),
            )
        }
        return MediaLibraryPage(items, json.int("TotalRecordCount") ?: items.size)
    }

    override suspend fun episodes(series: MediaLibraryItem): List<MediaLibraryEpisode> {
        val json = (if (isConnected) tryGet(get("/Shows/${series.id}/Episodes?UserId=${saved.userId}")) else null) ?: return emptyList()
        return json.array("Items").mapNotNull { e ->
            val number = e.int("IndexNumber") ?: 0
            val id = e.str("Id") ?: return@mapNotNull null
            MediaLibraryEpisode(id, e.int("ParentIndexNumber") ?: 1, number, e.str("Name") ?: tr("Épisode %s", number), imageUrl(e, 480), streamUrl(id))
        }
    }

    private fun imageUrl(item: JsonElement, width: Int) =
        item["ImageTags"].str("Primary")?.let { "$baseUrl/Items/${item.str("Id")}/Images/Primary?maxWidth=$width&tag=$it&quality=90" }

    private fun imdb(item: JsonElement) =
        (item["ProviderIds"] as? JsonObject)?.entries?.firstOrNull { it.key.equals("imdb", true) }?.value?.let { (it as? JsonPrimitive)?.content }

    /** Direct play: the server sends the file as is ("Static"). Jellyfin
     *  before 10.10 still wants api_key, newer ones ApiKey. */
    override fun streamUrl(itemId: String): String? {
        val t = token ?: return null
        return if (kind == MediaServerKind.Jellyfin) "$baseUrl/Videos/$itemId/stream?ApiKey=$t&api_key=$t&Static=true"
        else "$baseUrl/Videos/$itemId/stream?X-Emby-Token=$t&Static=true"
    }

    // -------------------- matching an IMDB title --------------------

    override suspend fun findMovie(imdbId: String, title: String, year: Int?) = findItems("Movie", imdbId, title, year).map(::match)

    override suspend fun findEpisode(seriesImdbId: String, title: String, year: Int?, season: Int, episode: Int): List<MediaServerMatch> =
        findItems("Series", seriesImdbId, title, year).flatMap { series ->
            tryGet(get("/Shows/${series.str("Id")}/Episodes?UserId=${saved.userId}&Season=$season&Fields=MediaStreams"))
                .array("Items").filter { it.int("ParentIndexNumber") == season && it.int("IndexNumber") == episode }.map(::match)
        }

    private suspend fun findItems(type: String, imdbId: String, title: String, year: Int?): List<JsonElement> {
        if (!isConnected) return emptyList()
        val query = "/Users/${saved.userId}/Items?SearchTerm=${enc(title)}&IncludeItemTypes=$type&Recursive=true&Fields=ProviderIds,ProductionYear,MediaStreams&Limit=20"
        return tryGet(get(query)).array("Items").filter { r ->
            imdb(r)?.equals(imdbId, true) ?: sameTitle(r.str("Name") ?: "", title, r.int("ProductionYear"), year)
        }
    }

    private fun match(r: JsonElement): MediaServerMatch {
        val streams = r.array("MediaStreams")
        val id = r.str("Id")!!
        return MediaServerMatch(
            this, id, r.str("Name") ?: "",
            streams.firstOrNull { it.str("Type") == "Video" }?.str("DisplayTitle"),
            streams.filter { it.str("Type") == "Audio" }.mapNotNull { it.str("Language")?.ifEmpty { null } }.distinct(),
            streamUrl(id)!!,
        )
    }
}

/** Plex: PIN sign-in on plex.tv/link, then the user's Plex Media Server,
 *  found through plex.tv's resources; playback uses the media part's URL. */
class PlexServer : MediaServer(MediaServerKind.Plex) {
    override val name = tr("Plex")
    override val color = 0xFFE5A00D
    private var flow: Job? = null
    private var access: Pair<String, String>? = null
    override val host get() = access?.first?.let { runCatching { URI(it).host }.getOrNull() }

    init {
        publish()
    }

    private fun request(url: String, token: String?) = Request.Builder().url(url)
        .header("X-Plex-Client-Identifier", saved.deviceId ?: "")
        .header("X-Plex-Product", "Veltrio").header("X-Plex-Device", "Android").header("X-Plex-Platform", "Android")
        .header("X-Plex-Version", "0.1.0").header("Accept", "application/json")
        .apply { token?.let { header("X-Plex-Token", it) } }

    // -------------------- PIN --------------------

    fun startPinFlow() {
        cancelPinFlow()
        flow = scope.launch {
            update { it.copy(error = null, busy = true) }
            try {
                // A short PIN, typed at plex.tv/link like on a TV.
                val pin = Http.json(request("https://plex.tv/api/v2/pins", null).post(FormBody.Builder().add("strong", "false").build()).build(), "Plex")
                val id = pin.long("id")
                update { it.copy(pendingCode = pin.str("code"), pendingUrl = "https://plex.tv/link") }
                val deadline = System.currentTimeMillis() + (pin.long("expiresIn").takeIf { it > 0 } ?: 900) * 1000
                while (System.currentTimeMillis() < deadline) {
                    delay(3000)
                    val check = try { Http.json(request("https://plex.tv/api/v2/pins/$id", null).build(), tr("Plex")) } catch (_: ServiceException) { continue }
                    check.str("authToken")?.ifEmpty { null }?.let { t ->
                        saveToken(t)
                        refreshUser()
                        MediaServers._version.value++
                        return@launch
                    }
                }
                update { it.copy(error = tr("Le code Plex a expiré. Recommencez.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            } finally {
                update { it.copy(pendingCode = null, pendingUrl = null, busy = false) }
                publish()
            }
        }
    }

    fun cancelPinFlow() {
        flow?.cancel()
        flow = null
    }

    override fun reload() {
        access = null
        super.reload()
    }

    private suspend fun refreshUser() {
        tryGet(request("https://plex.tv/api/v2/user", token).build())?.let { me ->
            MediaServers.save(kind, saved.copy(userName = me.str("username") ?: me.str("title")))
        }
        publish()
    }

    override fun disconnect() {
        cancelPinFlow()
        saveToken(null)
        MediaServers.save(kind, saved.copy(userName = null))
        access = null
        _state.value = MediaServerState()
        MediaServers._version.value++
    }

    // -------------------- server --------------------

    /** The server's address and the token it accepts (a server shared by
     *  someone else only takes its own). Connections are tried local first,
     *  and the first that answers is kept for the session. */
    private suspend fun access(): Pair<String, String>? {
        access?.let { return it }
        val t = token ?: return null
        val json = tryGet(request("https://clients.plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", t).build()) as? JsonArray ?: return null
        val quick = http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        for (server in json.filter { (it.str("provides") ?: "").contains("server") }) {
            val serverToken = server.str("accessToken") ?: t
            val connections = server.array("connections")
                .sortedWith(compareByDescending<JsonElement> { it.bool("local") == true }.thenBy { it.bool("relay") == true })
                .mapNotNull { it.str("uri") }
            for (uri in connections) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { quick.newCall(request("$uri/identity", serverToken).build()).execute().use { it.isSuccessful } }.getOrDefault(false)
                }
                if (!ok) continue
                access = uri.trimEnd('/') to serverToken
                publish()
                return access
            }
        }
        return null
    }

    private suspend fun metadata(path: String): List<JsonElement> {
        val (base, t) = access() ?: return emptyList()
        return tryGet(request(base + path, t).build())["MediaContainer"].array("Metadata")
    }

    private fun withToken(url: String, t: String) = "$url${if ('?' in url) '&' else '?'}X-Plex-Token=$t"
    private fun imageUrl(m: JsonElement) = access?.let { (base, t) -> m.str("thumb")?.let { withToken(base + it, t) } }
    private fun partUrl(m: JsonElement) = access?.let { (base, t) ->
        m.array("Media").firstOrNull().array("Part").firstOrNull().str("key")?.let { withToken(base + it, t) }
    }
    private fun imdb(m: JsonElement) = m.array("Guid").mapNotNull { it.str("id") }.firstOrNull { it.startsWith("imdb://") }?.removePrefix("imdb://")

    private suspend fun plexSections(): List<JsonElement> {
        val (base, t) = access() ?: return emptyList()
        return tryGet(request("$base/library/sections", t).build())["MediaContainer"].array("Directory")
    }

    // -------------------- browsing --------------------

    override suspend fun sections() = plexSections().mapNotNull { s ->
        val key = s.str("key") ?: return@mapNotNull null
        when (s.str("type")) {
            "movie" -> MediaLibrarySection(key, s.str("title") ?: "", MediaSectionKind.Movies)
            "show" -> MediaLibrarySection(key, s.str("title") ?: "", MediaSectionKind.Shows)
            else -> null
        }
    }

    override suspend fun items(section: MediaLibrarySection, start: Int, limit: Int): MediaLibraryPage {
        val (base, t) = access() ?: return MediaLibraryPage(emptyList(), 0)
        val url = "$base/library/sections/${section.id}/all?type=${if (section.kind == MediaSectionKind.Shows) 2 else 1}" +
            "&includeGuids=1&sort=titleSort&X-Plex-Container-Start=$start&X-Plex-Container-Size=$limit"
        val container = tryGet(request(url, t).build())?.get("MediaContainer") ?: return MediaLibraryPage(emptyList(), 0)
        val rows = container.array("Metadata")
        val items = rows.mapNotNull { m ->
            MediaLibraryItem(
                id = m.str("ratingKey") ?: return@mapNotNull null, title = m.str("title") ?: "", year = m.int("year"),
                type = if (m.str("type") == "show") "series" else "movie", imdbId = imdb(m), posterUrl = imageUrl(m), streamUrl = partUrl(m),
            )
        }
        return MediaLibraryPage(items, container.int("totalSize") ?: (start + rows.size))
    }

    override suspend fun episodes(series: MediaLibraryItem) = metadata("/library/metadata/${series.id}/allLeaves").mapNotNull { e ->
        MediaLibraryEpisode(e.str("ratingKey") ?: return@mapNotNull null, e.int("parentIndex") ?: 1, e.int("index") ?: 0, e.str("title") ?: "", imageUrl(e), partUrl(e))
    }

    /** Plex URLs come from the listing, not from an id. */
    override fun streamUrl(itemId: String): String? = null

    // -------------------- matching an IMDB title --------------------

    override suspend fun findMovie(imdbId: String, title: String, year: Int?): List<MediaServerMatch> =
        plexSections().filter { it.str("type") == "movie" }.flatMap { section ->
            metadata("/library/sections/${section.str("key")}/all?type=1&includeGuids=1&title=${enc(title)}")
                .filter { isSame(it, imdbId, title, year) }.mapNotNull { match(it) }
        }

    override suspend fun findEpisode(seriesImdbId: String, title: String, year: Int?, season: Int, episode: Int): List<MediaServerMatch> =
        plexSections().filter { it.str("type") == "show" }.flatMap { section ->
            metadata("/library/sections/${section.str("key")}/all?type=2&includeGuids=1&title=${enc(title)}")
                .filter { isSame(it, seriesImdbId, title, year) }
                .flatMap { show -> metadata("/library/metadata/${show.str("ratingKey")}/allLeaves") }
                .filter { it.int("parentIndex") == season && it.int("index") == episode }.mapNotNull { match(it) }
        }

    private fun isSame(item: JsonElement, imdbId: String, title: String, year: Int?) =
        imdb(item)?.equals(imdbId, true) ?: sameTitle(item.str("title") ?: "", title, item.int("year"), year)

    /** The listing omits audio streams, so the item's own metadata is
     *  fetched for its languages. */
    private suspend fun match(item: JsonElement): MediaServerMatch? {
        val key = item.str("ratingKey") ?: return null
        val full = metadata("/library/metadata/$key").firstOrNull() ?: item
        val url = partUrl(full) ?: return null
        val media = full.array("Media").firstOrNull()
        val part = media.array("Part").firstOrNull()
        val languages = part.array("Stream").filter { it.int("streamType") == 2 }.mapNotNull { it.str("languageCode") }.distinct()
        val resolution = media.str("videoResolution")
        val video = listOfNotNull(
            resolution?.let { if (it.equals("4k", true)) "4K" else if (it.all(Char::isDigit)) "${it}p" else it.uppercase() },
            media.str("videoCodec")?.uppercase(),
        ).joinToString(" ")
        return MediaServerMatch(this, key, item.str("title") ?: "", video.ifEmpty { null }, languages, url)
    }
}
