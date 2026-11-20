package app.veltrio.shared.iptv

import android.content.Context
import android.net.Uri
import app.veltrio.shared.SecretStore
import app.veltrio.shared.Storage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import uniffi.veltrio.IptvClient
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** IPTV sources: M3U playlists through the core's parser (a URL, or a file
 *  picked on the device and copied in), and Xtream Codes accounts (live,
 *  films, series). Xtream passwords are kept encrypted. */
object IptvStore {
    private const val FILE = "iptv-playlists.json"
    private const val SELECTED_FILE = "iptv-selected.json"

    private val client by lazy { IptvClient() }
    private val cache = ConcurrentHashMap<Pair<String, ChannelKind>, Deferred<List<ChannelItem>>>()

    private val _playlists = MutableStateFlow<List<PlaylistRef>>(emptyList())
    val playlists: StateFlow<List<PlaylistRef>> = _playlists
    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId

    internal fun init() {
        _playlists.value = Storage.load(FILE, ListSerializer(PlaylistRef.serializer()), emptyList()).map { p ->
            p.xtream?.let { p.copy(xtream = it.copy(password = SecretStore.get(secretName(p.id)) ?: "")) } ?: p
        }
        _selectedId.value = Storage.load(SELECTED_FILE, String.serializer(), "").ifEmpty { null }
    }

    private fun secretName(id: String) = "xtream.$id"

    val selected: PlaylistRef? get() = _playlists.value.firstOrNull { it.id == _selectedId.value } ?: _playlists.value.firstOrNull()

    fun select(playlist: PlaylistRef) {
        _selectedId.value = playlist.id
        Storage.save(SELECTED_FILE, String.serializer(), playlist.id)
    }

    // -------------------- adding / removing --------------------

    fun addM3u(name: String, rawUrl: String): String? {
        val url = rawUrl.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrEmpty()) return "L'URL de la playlist doit commencer par http:// ou https://."
        add(PlaylistRef(name = name.trim().ifEmpty { uri!!.host }, url = url))
        return null
    }

    /** A .m3u file picked on the device: copied into the app, since a
     *  picked document's permission doesn't outlive the app. */
    fun addLocalFile(context: Context, name: String, uri: Uri): String? = try {
        val playlist = PlaylistRef(name = name.trim().ifEmpty { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Playlist" })
        val target = localFile(playlist)
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        add(playlist.copy(url = "file://${target.absolutePath}"))
        null
    } catch (e: Exception) {
        "Fichier illisible : ${e.message}"
    }

    private fun localFile(p: PlaylistRef) = File(Storage.folder, "playlists/${p.id}.m3u")

    /** Checks the account against the server before keeping it. Returns an
     *  error message, or null. */
    suspend fun addXtream(name: String, host: String, username: String, password: String, output: String): String? {
        val account = XtreamAccount(host = host.trim(), username = username.trim(), password = password, output = output)
        if (account.baseUrl.isEmpty() || account.username.isEmpty() || password.isEmpty()) return "Renseignez le serveur, l'utilisateur et le mot de passe."
        try {
            XtreamClient.verify(account)
        } catch (e: java.io.IOException) {
            return "Serveur injoignable : ${e.message}"
        } catch (e: Exception) {
            return e.message
        }
        val playlist = PlaylistRef(name = name.trim().ifEmpty { runCatching { URI(account.baseUrl).host }.getOrNull() ?: "Xtream" }, xtream = account)
        SecretStore.set(secretName(playlist.id), password)
        add(playlist)
        return null
    }

    /** A playlist received from a phone, password included; skipped when
     *  the same source is already there. Returns whether it was added. */
    fun import(playlist: PlaylistRef): Boolean {
        val same = _playlists.value.any { p ->
            if (playlist.xtream != null) p.xtream?.baseUrl == playlist.xtream.baseUrl && p.xtream.username == playlist.xtream.username
            else p.url == playlist.url
        }
        if (same || playlist.url.startsWith("file://")) return false
        playlist.xtream?.let { SecretStore.set(secretName(playlist.id), it.password) }
        _playlists.value = _playlists.value + playlist
        save()
        if (_selectedId.value == null) select(playlist)
        return true
    }

    private fun add(playlist: PlaylistRef) {
        _playlists.value = _playlists.value + playlist
        save()
        select(playlist)
    }

    fun remove(playlist: PlaylistRef) {
        _playlists.value = _playlists.value.filter { it.id != playlist.id }
        cache.keys.filter { it.first == playlist.id }.forEach { cache.remove(it) }
        if (playlist.isXtream) SecretStore.set(secretName(playlist.id), null)
        localFile(playlist).delete()
        save()
    }

    private fun save() = Storage.save(FILE, ListSerializer(PlaylistRef.serializer()), _playlists.value)

    // -------------------- content --------------------

    /** Live channels (M3U or Xtream), or an Xtream account's films or
     *  series. Loaded once per session unless [refresh]. */
    suspend fun items(playlist: PlaylistRef, kind: ChannelKind = ChannelKind.Live, refresh: Boolean = false): List<ChannelItem> {
        if (!playlist.isXtream && kind != ChannelKind.Live) return emptyList()
        val key = playlist.id to kind
        val existing = cache[key]
        if (!refresh && existing != null && !(existing.isCompleted && existing.getCompletionExceptionOrNull() != null)) return existing.await()
        val deferred = CompletableDeferred<List<ChannelItem>>()
        cache[key] = deferred
        try {
            deferred.complete(load(playlist, kind))
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
        }
        return deferred.await()
    }

    /** Already loaded this session, without waiting. */
    fun loaded(playlist: PlaylistRef, kind: ChannelKind = ChannelKind.Live): List<ChannelItem>? =
        cache[playlist.id to kind]?.takeIf { it.isCompleted && it.getCompletionExceptionOrNull() == null }?.getCompleted()

    private suspend fun load(playlist: PlaylistRef, kind: ChannelKind): List<ChannelItem> {
        val account = playlist.xtream
        val items = when {
            account != null -> when (kind) {
                ChannelKind.Movie -> XtreamClient.movies(account)
                ChannelKind.Series -> XtreamClient.series(account)
                ChannelKind.Live -> XtreamClient.live(account)
            }
            playlist.url.startsWith("file://") ->
                client.parseInline(File(URI(playlist.url)).readText()).channels.map(ChannelItem::from)
            else -> client.loadPlaylist(playlist.url).channels.map(ChannelItem::from)
        }
        // Channel numbers follow the playlist's order.
        return items.mapIndexed { i, c -> c.copy(number = i + 1) }
    }

    /** Live channels of every playlist whose name matches, then the films and
     *  series of each Xtream account, searched on the provider's server. */
    suspend fun search(query: String): List<Pair<PlaylistRef, ChannelItem>> = coroutineScope {
        _playlists.value.map { p ->
            async {
                try {
                    val live = items(p).filter { XtreamClient.titleMatches(it.name, query) }.take(40)
                    val vod = p.xtream?.let { XtreamClient.search(it, query) }.orEmpty()
                    (live + vod).map { p to it }
                } catch (_: Exception) {
                    // An unreachable source just doesn't contribute.
                    emptyList()
                }
            }
        }.awaitAll().flatten().take(120)
    }
}
