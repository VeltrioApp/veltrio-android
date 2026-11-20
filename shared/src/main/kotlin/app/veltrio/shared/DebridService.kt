package app.veltrio.shared

import android.util.Log
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
import uniffi.veltrio.AllDebridClient
import java.util.concurrent.ConcurrentHashMap

enum class DebridProvider(
    val label: String,
    val short: String,
    val color: Long,
    /** Sign-in with a code shown in the app (RD, AD) or an API key (TorBox, Premiumize). */
    val usesCode: Boolean,
    val tagline: String,
    val keyPage: String,
) {
    RealDebrid("Real-Debrid", "RD", 0xFF6CAD3E, true, "Le plus répandu. Connexion par code, sans mot de passe.", "https://real-debrid.com/device"),
    AllDebrid("AllDebrid", "AD", 0xFF16A34A, true, "Connexion par code PIN. Lit aussi ce qui n'est pas encore en cache.", "https://alldebrid.com/pin/"),
    Premiumize("Premiumize", "PM", 0xFFFFA200, false, "Clé d'API à copier depuis la page de votre compte.", "https://www.premiumize.me/account"),
    TorBox("TorBox", "TB", 0xFF4EA2FF, false, "Clé d'API à copier depuis les réglages de votre compte.", "https://torbox.app/settings"),
}

/** One provider's state, as the Settings cards show it. */
data class DebridAccount(
    val provider: DebridProvider,
    val key: String? = null,
    val user: String? = null,
    val premium: Boolean? = null,
    /** A code flow in progress: what to show and where to go. */
    val pendingCode: String? = null,
    val pendingUrl: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
) {
    val isConnected get() = key != null
}

/** Debrid orchestration, as on macOS: provider sign-in, a cache map per
 *  provider for the ⚡ badges, and resolving a torrent through the first
 *  service that can serve it. */
object DebridService {
    private val allDebrid by lazy { AllDebridClient() }
    private val cached = ConcurrentHashMap<Pair<DebridProvider, String>, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flow: Job? = null

    private val _accounts = MutableStateFlow(
        DebridProvider.entries.associateWith { DebridAccount(it, key = SecretStore.get(secretName(it))) },
    )
    val accounts: StateFlow<Map<DebridProvider, DebridAccount>> = _accounts

    /** Bumped when the cache map changes, for the ⚡ badges. */
    private val _cacheVersion = MutableStateFlow(0)
    val cacheVersion: StateFlow<Int> = _cacheVersion

    init {
        _accounts.value.values.filter { it.isConnected }.forEach { scope.launch { refreshUser(it.provider) } }
    }

    /** Keys changed in SecretStore (settings received from a phone). */
    fun reload() {
        DebridProvider.entries.forEach { p ->
            val key = SecretStore.get(secretName(p))
            if (key != account(p).key) {
                update(p) { DebridAccount(p, key = key) }
                if (key != null) scope.launch { refreshUser(p) }
            }
        }
    }

    fun account(p: DebridProvider) = _accounts.value.getValue(p)
    val anyConnected get() = _accounts.value.values.any { it.isConnected }

    private fun secretName(p: DebridProvider) = "debrid.${p.name}".lowercase()

    private fun update(p: DebridProvider, change: (DebridAccount) -> DebridAccount) {
        synchronized(this) { _accounts.value = _accounts.value + (p to change(_accounts.value.getValue(p))) }
    }

    private suspend fun refreshUser(p: DebridProvider) {
        val key = account(p).key ?: return
        try {
            when (p) {
                DebridProvider.RealDebrid -> RealDebridClient.user(key).let { (u, prem) -> update(p) { it.copy(user = u, premium = prem) } }
                DebridProvider.AllDebrid -> allDebrid.userInfo(key).let { info -> update(p) { it.copy(user = info.username, premium = info.isPremium) } }
                DebridProvider.TorBox -> TorBoxClient.user(key).let { (u, paid) -> update(p) { it.copy(user = u, premium = paid) } }
                DebridProvider.Premiumize -> PremiumizeClient.account(key).let { prem -> update(p) { it.copy(premium = prem) } }
            }
        } catch (_: Exception) {
            // Offline or revoked: the card still shows "connected".
        }
    }

    // -------------------- sign-in --------------------

    /** TorBox / Premiumize: checks the key before keeping it, so a typo
     *  shows here rather than as streams that never resolve. */
    suspend fun connectWithKey(p: DebridProvider, raw: String) {
        val key = raw.trim().ifEmpty { return }
        update(p) { it.copy(error = null, busy = true) }
        try {
            if (p == DebridProvider.TorBox) TorBoxClient.user(key) else PremiumizeClient.account(key)
            SecretStore.set(secretName(p), key)
            update(p) { it.copy(key = key) }
            refreshUser(p)
        } catch (e: Exception) {
            update(p) { it.copy(key = null, error = "${p.label} a refusé cette clé. ${e.message}") }
        } finally {
            update(p) { it.copy(busy = false) }
        }
    }

    /** Real-Debrid device code or AllDebrid PIN: shows a code, then polls
     *  until the user has entered it on the provider's site. */
    fun startCodeFlow(p: DebridProvider) {
        cancelCodeFlow()
        flow = scope.launch {
            update(p) { it.copy(error = null, busy = true) }
            try {
                if (p == DebridProvider.RealDebrid) {
                    val code = RealDebridClient.startDeviceAuth()
                    update(p) { it.copy(pendingCode = code.userCode, pendingUrl = code.verificationUrl) }
                    val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(code.interval * 1000L)
                        RealDebridClient.pollDeviceAuth(code.deviceCode)?.let { connected(p, it); return@launch }
                    }
                } else {
                    val pin = allDebrid.startPinAuth()
                    update(p) { it.copy(pendingCode = pin.pin, pendingUrl = pin.userUrl) }
                    val deadline = System.currentTimeMillis() + pin.expiresInSeconds.toLong() * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        delay(2500)
                        // Transient errors: the deadline ends the loop.
                        runCatching { allDebrid.pollPin(pin.pin, pin.checkToken) }.getOrNull()?.let { connected(p, it); return@launch }
                    }
                }
                update(p) { it.copy(error = "Le code a expiré. Recommencez.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(p) { it.copy(error = Core.humanize(e)) }
            } finally {
                update(p) { it.copy(pendingCode = null, pendingUrl = null, busy = false) }
            }
        }
    }

    fun cancelCodeFlow() {
        flow?.cancel()
        flow = null
    }

    private suspend fun connected(p: DebridProvider, key: String) {
        SecretStore.set(secretName(p), key)
        update(p) { it.copy(key = key) }
        refreshUser(p)
    }

    fun disconnect(p: DebridProvider) {
        SecretStore.set(secretName(p), null)
        update(p) { DebridAccount(p) }
        cached.keys.filter { it.first == p }.forEach { cached.remove(it) }
        _cacheVersion.value++
    }

    // -------------------- availability --------------------

    /** Asks every connected service which of these torrents it has cached.
     *  Real-Debrid can't be asked any more (it disabled the endpoint); its
     *  torrents are tried at playback instead. */
    suspend fun prefetch(infoHashes: List<String>) {
        val hashes = infoHashes.map { it.lowercase() }.distinct().ifEmpty { return }
        coroutineScope {
            listOf(
                async { check(DebridProvider.AllDebrid, hashes) { key, missing -> allDebrid.checkAvailability(key, missing) } },
                async { check(DebridProvider.TorBox, hashes) { key, missing -> TorBoxClient.checkCached(missing, key) } },
                async { check(DebridProvider.Premiumize, hashes) { key, missing -> PremiumizeClient.checkCached(missing, key) } },
            ).awaitAll()
        }
        _cacheVersion.value++
    }

    private suspend fun check(p: DebridProvider, hashes: List<String>, check: suspend (String, List<String>) -> Map<String, Boolean>) {
        val key = account(p).key ?: return
        val missing = hashes.filter { !cached.containsKey(p to it) }.ifEmpty { return }
        try {
            check(key, missing).forEach { (h, c) -> cached[p to h.lowercase()] = c }
        } catch (_: Exception) {
            // Unknown availability: no badge, still tried at playback.
        }
    }

    /** The service that has this torrent cached, if any (the ⚡ tag). */
    fun cachedOn(infoHash: String): DebridProvider? =
        DebridProvider.entries.firstOrNull { cached[it to infoHash.lowercase()] == true }

    // -------------------- resolution --------------------

    /** A direct HTTPS link for a torrent, or null to fall back to the local
     *  engine. Services that have it cached first, then Real-Debrid (tried
     *  blind) and AllDebrid. */
    suspend fun resolve(infoHash: String, season: Int?, episode: Int?): String? {
        val h = infoHash.lowercase()
        val order = _accounts.value.values.filter { it.isConnected }.sortedByDescending { cached[it.provider to h] == true }
        for (account in order) {
            val isCached = cached[account.provider to h] == true
            // TorBox and Premiumize only serve what they have cached.
            if (!isCached && account.provider in setOf(DebridProvider.TorBox, DebridProvider.Premiumize)) continue
            try {
                val key = account.key!!
                val url = when (account.provider) {
                    DebridProvider.RealDebrid -> RealDebridClient.resolve(h, season, episode, key)
                    DebridProvider.AllDebrid -> allDebrid.resolveStream(key, h, season, episode)
                    DebridProvider.TorBox -> TorBoxClient.resolve(h, season, episode, key)
                    DebridProvider.Premiumize -> PremiumizeClient.resolve(h, season, episode, key)
                }
                if (url != null) return url
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Veltrio", "[debrid] ${account.provider.label}: ${e.message}")
            }
        }
        return null
    }
}
