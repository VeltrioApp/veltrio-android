package app.veltrio.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** One account's state, as the Settings cards show it. */
data class AccountState(
    /** False when the build has no app credentials for the service. */
    val available: Boolean = true,
    val connected: Boolean = false,
    val user: String? = null,
    val pendingCode: String? = null,
    val pendingUrl: String? = null,
    val busy: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
    val lastResult: String? = null,
    /** Stremio: the result of installing the account's addons. */
    val addonResult: String? = null,
)

private val jsonType = "application/json".toMediaType()
private fun JsonElement.body() = toString().toRequestBody(jsonType)

internal object AccountText {
    fun importResult(read: Int, changed: Int) = "$read titre${if (read > 1) "s" else ""} lu${if (read > 1) "s" else ""}, $changed mis à jour"
    fun parseDate(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

/** Base of the three: a state flow and a scope for flows and imports. */
abstract class AccountService {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state
    protected var flow: Job? = null

    protected fun update(change: (AccountState) -> AccountState) {
        synchronized(this) { _state.value = change(_state.value) }
    }

    fun cancelFlow() {
        flow?.cancel()
        flow = null
    }

    /** Runs an import, with the busy flag and the result line. */
    protected fun runImport(failure: String = "", work: suspend () -> List<ImportedWatchItem>) {
        scope.launch {
            update { it.copy(error = null, importing = true) }
            try {
                val items = work()
                update { it.copy(lastResult = AccountText.importResult(items.size, WatchStore.merge(items))) }
            } catch (e: Exception) {
                update { it.copy(error = failure + (e.message ?: "")) }
            } finally {
                update { it.copy(importing = false) }
            }
        }
    }
}

/** A Stremio account: sign in with email and password, then import its
 *  library (saved titles, watched films, film progress) and its addons.
 *  Import is one-way: nothing is written back to Stremio. */
object StremioAccount : AccountService() {
    private const val API = "https://api.strem.io/api/"
    private const val KEY_SECRET = "stremio.authkey"
    private const val EMAIL_SECRET = "stremio.email"
    private var authKey: String? = SecretStore.get(KEY_SECRET)

    init {
        reload()
    }

    /** The session changed in SecretStore (settings received from a phone). */
    fun reload() {
        authKey = SecretStore.get(KEY_SECRET)
        _state.value = AccountState(connected = authKey != null, user = SecretStore.get(EMAIL_SECRET))
    }

    fun signIn(email: String, password: String) = scope.launch {
        update { it.copy(error = null, busy = true) }
        try {
            val login = call("login", buildJsonObject { put("type", "Login"); put("email", email); put("password", password); put("facebook", false) })
            val key = login.str("authKey") ?: throw ServiceException("Stremio n'a pas renvoyé de session.")
            val shown = login["user"].str("email") ?: email
            authKey = key
            SecretStore.set(KEY_SECRET, key)
            SecretStore.set(EMAIL_SECRET, shown)
            update { it.copy(connected = true, user = shown) }
        } catch (e: Exception) {
            update { it.copy(error = e.message) }
        } finally {
            update { it.copy(busy = false) }
        }
    }

    fun signOut() {
        authKey?.let { key -> scope.launch { runCatching { call("logout", buildJsonObject { put("type", "Logout"); put("authKey", key) }) } } }
        authKey = null
        SecretStore.set(KEY_SECRET, null)
        SecretStore.set(EMAIL_SECRET, null)
        _state.value = AccountState()
    }

    /** Saved and watched titles, and film progress, into the library. */
    fun importLibrary() {
        val key = authKey ?: return
        runImport {
            val items = call("datastoreGet", buildJsonObject {
                put("authKey", key); put("collection", "libraryItem"); put("ids", JsonArray(emptyList())); put("all", true)
            })
            items.items.mapNotNull(::imported)
        }
    }

    /** Installs the account's addons that aren't installed yet. */
    fun installAccountAddons() {
        val key = authKey ?: return
        scope.launch {
            update { it.copy(error = null, busy = true) }
            try {
                val collection = call("addonCollectionGet", buildJsonObject { put("type", "AddonCollectionGet"); put("authKey", key); put("update", true) })
                val urls = collection.array("addons").mapNotNull { it.str("transportUrl") }.filter { !Core.isInstalled(it) }
                var installed = 0
                for (url in urls) if (Core.installAddon(url) == null) installed++
                val result = when {
                    installed == 0 && urls.isEmpty() -> "Tous les addons du compte sont déjà installés"
                    installed == 0 -> "Aucun addon n'a pu être installé"
                    installed == 1 -> "1 addon installé"
                    else -> "$installed addons installés"
                }
                update { it.copy(addonResult = result) }
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    /** A Stremio library item as an import, or null for anything that isn't
     *  a film or series keyed by an IMDB id. */
    private fun imported(item: JsonElement): ImportedWatchItem? {
        val id = item.str("_id") ?: return null
        val type = item.str("type") ?: return null
        if (!id.startsWith("tt") || type !in setOf("movie", "series")) return null
        val isFilm = type == "movie"
        val state = item["state"]
        fun ms(name: String) = ((state[name] as? JsonPrimitive)?.doubleOrNull)?.div(1000)
        return ImportedWatchItem(
            type = type, id = id, name = item.str("name") ?: id, poster = item.str("poster"),
            // "temp" items are ones Stremio tracks only because they were opened.
            inLibrary = item.bool("removed") != true && item.bool("temp") != true,
            watched = isFilm && (state.long("flaggedWatched") > 0 || state.long("timesWatched") > 0),
            position = if (isFilm) ms("timeOffset") else null,
            duration = if (isFilm) ms("duration") else null,
            lastWatched = AccountText.parseDate(state.str("lastWatched")),
        )
    }

    /** The API answers 200 with either "result" or "error". */
    private suspend fun call(method: String, body: JsonElement): JsonElement {
        val json = Http.json(Request.Builder().url(API + method).post(body.body()).build(), "Stremio")
        json["result"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { return it }
        throw ServiceException(
            when (val message = json["error"].str("message")) {
                null -> "Stremio a refusé la demande."
                "Wrong passphrase", "User not found" -> "E-mail ou mot de passe incorrect."
                else -> "Stremio : $message"
            },
        )
    }
}

/** Trakt.tv: OAuth device code sign-in, scrobbling from the player, and
 *  importing the watchlist and watched history. Needs a Trakt app, baked in
 *  at build time; without it the service stays unavailable. */
object TraktAccount : AccountService() {
    private const val API = "https://api.trakt.tv/"
    private const val TOKEN_SECRET = "trakt.token"
    private val clientId = BuildConfig.TRAKT_CLIENT_ID.ifEmpty { null }
    private val clientSecret = BuildConfig.TRAKT_CLIENT_SECRET.ifEmpty { null }
    private var token: String? = SecretStore.get(TOKEN_SECRET)
    private val available get() = clientId != null && clientSecret != null
    val isConnected get() = token != null && available

    init {
        reload()
    }

    /** The token changed in SecretStore (settings received from a phone). */
    fun reload() {
        token = SecretStore.get(TOKEN_SECRET)
        _state.value = AccountState(available = available, connected = isConnected)
        if (isConnected) scope.launch { refreshUser() }
    }

    private suspend fun refreshUser() {
        try {
            val me = get("users/me?extended=full")
            update { it.copy(user = me.str("name")?.ifEmpty { null } ?: me.str("username")) }
        } catch (_: Exception) {
            // Offline or revoked: the card still shows "connected".
        }
    }

    /** Shows a code, then polls until the user has entered it on trakt.tv. */
    fun startDeviceAuth() {
        if (!available) return
        cancelFlow()
        flow = scope.launch {
            update { it.copy(error = null, busy = true) }
            try {
                val code = post("oauth/device/code", buildJsonObject { put("client_id", clientId) }, auth = false)
                val deviceCode = code.str("device_code")!!
                update { it.copy(pendingCode = code.str("user_code"), pendingUrl = code.str("verification_url") ?: "https://trakt.tv/activate") }
                val interval = maxOf(5L, code.long("interval"))
                val deadline = System.currentTimeMillis() + code.long("expires_in") * 1000
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000)
                    val answer = try {
                        post("oauth/device/token", buildJsonObject { put("code", deviceCode); put("client_id", clientId); put("client_secret", clientSecret) }, auth = false)
                    } catch (_: ServiceException) {
                        continue // 400 while the code hasn't been entered yet
                    }
                    answer.str("access_token")?.let { access ->
                        token = access
                        SecretStore.set(TOKEN_SECRET, access)
                        update { it.copy(connected = true) }
                        refreshUser()
                        return@launch
                    }
                }
                update { it.copy(error = "Le code Trakt a expiré. Recommencez.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            } finally {
                update { it.copy(pendingCode = null, pendingUrl = null, busy = false) }
            }
        }
    }

    fun disconnect() {
        cancelFlow()
        token = null
        SecretStore.set(TOKEN_SECRET, null)
        _state.value = AccountState(available = available)
    }

    /** start, pause or stop; progress 0–100. Silent on every error. */
    suspend fun scrobble(action: String, imdbId: String, season: Int?, episode: Int?, progress: Double) {
        if (!isConnected) return
        runCatching { post("scrobble/$action", scrobbleBody(imdbId, season, episode, progress), auth = true) }
    }

    /** Watchlist (saved) and watched history, films and series. */
    fun importLibrary() {
        if (!isConnected) return
        runImport {
            coroutineScope {
                suspend fun rows(path: String) = runCatching { get(path).items }.getOrDefault(emptyList())
                val lists = listOf("sync/watchlist/movies", "sync/watchlist/shows", "sync/watched/movies", "sync/watched/shows")
                    .map { async { rows(it) } }.awaitAll()
                (lists[0] + lists[1]).mapNotNull { item(it, saved = true) } + (lists[2] + lists[3]).mapNotNull { item(it, saved = false) }
            }
        }
    }

    private fun item(row: JsonElement, saved: Boolean): ImportedWatchItem? {
        val isFilm = row["movie"] is JsonObject
        val title = row[if (isFilm) "movie" else "show"] as? JsonObject ?: return null
        val imdb = title["ids"].str("imdb")?.takeIf { it.startsWith("tt") } ?: return null
        return ImportedWatchItem(
            if (isFilm) "movie" else "series", imdb, title.str("title") ?: imdb,
            inLibrary = saved,
            // A show in the watched list may be partly watched; only films get the flag.
            watched = !saved && isFilm,
            lastWatched = AccountText.parseDate(row.str("last_watched_at") ?: row.str("listed_at")),
        )
    }

    private fun request(path: String, auth: Boolean) = Request.Builder().url(API + path)
        .header("trakt-api-version", "2").header("trakt-api-key", clientId ?: "")
        .apply { if (auth) token?.let { header("Authorization", "Bearer $it") } }

    private suspend fun get(path: String) = Http.json(request(path, auth = true).build(), "Trakt")
    private suspend fun post(path: String, body: JsonElement, auth: Boolean) = Http.json(request(path, auth).post(body.body()).build(), "Trakt")
}

/** SIMKL: PIN sign-in, scrobbling, and importing the account's lists.
 *  Needs an app-specific client id baked in at build time. */
object SimklAccount : AccountService() {
    private const val API = "https://api.simkl.com"
    private const val TOKEN_SECRET = "simkl.token"
    private val clientId = BuildConfig.SIMKL_CLIENT_ID.ifEmpty { null }
    private var token: String? = SecretStore.get(TOKEN_SECRET)
    val isConnected get() = token != null && clientId != null

    init {
        reload()
    }

    /** The token changed in SecretStore (settings received from a phone). */
    fun reload() {
        token = SecretStore.get(TOKEN_SECRET)
        _state.value = AccountState(available = clientId != null, connected = isConnected)
    }

    fun startPinFlow() {
        val id = clientId ?: return
        cancelFlow()
        flow = scope.launch {
            update { it.copy(error = null, busy = true) }
            try {
                val pin = send("/oauth/pin?client_id=$id", null, auth = false)
                val code = pin.str("user_code")!!
                update { it.copy(pendingCode = code, pendingUrl = pin.str("verification_url") ?: "https://simkl.com/pin") }
                val interval = pin.long("interval").takeIf { it > 0 } ?: 5
                val deadline = System.currentTimeMillis() + (pin.long("expires_in").takeIf { it > 0 } ?: 900) * 1000
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000)
                    val answer = try { send("/oauth/pin/$code?client_id=$id", null, auth = false) } catch (_: ServiceException) { continue }
                    answer.str("access_token")?.let { access ->
                        token = access
                        SecretStore.set(TOKEN_SECRET, access)
                        update { it.copy(connected = true) }
                        return@launch
                    }
                }
                update { it.copy(error = "Le code SIMKL a expiré. Recommencez.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            } finally {
                update { it.copy(pendingCode = null, pendingUrl = null, busy = false) }
            }
        }
    }

    fun disconnect() {
        cancelFlow()
        token = null
        SecretStore.set(TOKEN_SECRET, null)
        _state.value = AccountState(available = clientId != null)
    }

    /** Plan-to-watch and watching (saved) and completed (watched) lists. */
    fun importLibrary() {
        if (!isConnected) return
        runImport("Impossible de lire les listes SIMKL. ") {
            val all = send("/sync/all-items/", null, auth = true)
            all.array("movies").mapNotNull { item(it, isFilm = true) } +
                (all.array("shows") + all.array("anime")).mapNotNull { item(it, isFilm = false) }
        }
    }

    private fun item(row: JsonElement, isFilm: Boolean): ImportedWatchItem? {
        val title = row[if (isFilm) "movie" else "show"] as? JsonObject ?: return null
        val imdb = title["ids"].str("imdb")?.takeIf { it.startsWith("tt") } ?: return null
        val status = row.str("status")
        return ImportedWatchItem(
            if (isFilm) "movie" else "series", imdb, title.str("title") ?: imdb,
            inLibrary = status == "plantowatch" || status == "watching",
            watched = isFilm && status == "completed",
            lastWatched = AccountText.parseDate(row.str("last_watched_at") ?: row.str("added_to_watchlist_at")),
        )
    }

    /** start, pause or stop; SIMKL marks a title watched when "stop" comes
     *  with 80 % or more. Silent on every error. */
    suspend fun scrobble(action: String, imdbId: String, season: Int?, episode: Int?, progress: Double) {
        if (!isConnected || !imdbId.startsWith("tt")) return
        runCatching { send("/scrobble/$action", scrobbleBody(imdbId, season, episode, progress), auth = true) }
    }

    private suspend fun send(path: String, body: JsonElement?, auth: Boolean): JsonElement {
        val request = Request.Builder().url(API + path).header("simkl-api-key", clientId ?: "")
            .apply { if (auth) token?.let { header("Authorization", "Bearer $it") } }
            .apply { body?.let { post(it.body()) } }
        return Http.json(request.build(), "SIMKL")
    }
}

internal fun scrobbleBody(imdbId: String, season: Int?, episode: Int?, progress: Double) = buildJsonObject {
    put("progress", progress.coerceIn(0.0, 100.0))
    if (season != null && episode != null) {
        putJsonObject("show") { putJsonObject("ids") { put("imdb", imdbId.substringBefore(':')) } }
        putJsonObject("episode") { put("season", season); put("number", episode) }
    } else {
        putJsonObject("movie") { putJsonObject("ids") { put("imdb", imdbId) } }
    }
}

/** Tells Trakt and SIMKL what one playback is doing: "start" once it's
 *  under way and again after a pause, "pause", and "stop" when the player
 *  closes (80 % or more marks the title watched on both). Only titles
 *  keyed by an IMDB id; fire-and-forget. */
class Scrobbler(private val request: PlayRequest) {
    private var sent: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val applies get() = !request.isLive && request.contentId?.startsWith("tt") == true &&
        (TraktAccount.isConnected || SimklAccount.isConnected)

    /** Called with each progress report; sends only on changes. */
    fun update(position: Double, duration: Double, paused: Boolean) {
        if (duration <= 0 || position <= 0) return
        send(if (paused) "pause" else "start", position, duration)
    }

    fun stop(position: Double, duration: Double) {
        if (sent == null || duration <= 0) return
        send("stop", position, duration)
    }

    private fun send(action: String, position: Double, duration: Double) {
        if (action == sent || !applies) return
        sent = action
        val progress = position / duration * 100
        val id = request.contentId!!
        scope.launch { TraktAccount.scrobble(action, id, request.season, request.episode, progress) }
        scope.launch { SimklAccount.scrobble(action, id, request.season, request.episode, progress) }
    }
}
