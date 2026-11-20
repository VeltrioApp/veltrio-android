package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import android.util.Log
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.iptv.PlaylistRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Everything a device sends to set up another: sources, accounts, keys,
 *  servers, the Collection, playback choices. Version 1. */
@Serializable
data class SettingsBundle(
    val v: Int = 1,
    val from: String = "",
    /** Addon manifest URLs, in the sender's order. */
    val addons: List<String> = emptyList(),
    val disabledAddons: List<String> = emptyList(),
    /** Xtream passwords included. */
    val playlists: List<BundlePlaylist> = emptyList(),
    /** SecretStore entries: debrid.*, stremio.*, trakt.token, simkl.token, mediaserver.*. */
    val secrets: Map<String, String> = emptyMap(),
    val mediaServers: Map<String, MediaServers.Saved> = emptyMap(),
    val watch: List<WatchEntry> = emptyList(),
    val playerPrefs: PlayerPrefs? = null,
)

@Serializable
data class BundlePlaylist(val name: String, val url: String = "", val host: String? = null, val username: String? = null, val password: String? = null, val output: String = "ts")

/** Sending this device's settings to a TV on the same network, so it isn't
 *  set up again with a remote.
 *
 *  The TV advertises `_veltrio-pair._tcp` over DNS-SD and shows a 6-digit
 *  code. `GET /veltrio-pair` answers `{name, publicKey}`: an ephemeral P-256
 *  key, uncompressed (X9.63), base64. The sender makes its own key pair and
 *  derives the AES-256-GCM key with HKDF-SHA256 (input: the ECDH secret;
 *  salt: the code, UTF-8; info: "veltrio-pair-v1"), then
 *  `POST /veltrio-pair {publicKey, nonce, data}` — data being the
 *  SettingsBundle JSON, encrypted, tag appended. A wrong code fails the
 *  tag check (403); after 5 failures the TV draws a new code and key. The
 *  same exchange works from any platform with P-256 ECDH (CryptoKit on iOS
 *  and macOS, CNG on Windows). */
object Pairing {
    const val SERVICE_TYPE = "_veltrio-pair._tcp"
    private const val INFO = "veltrio-pair-v1"
    private val random = SecureRandom()

    // -------------------- crypto --------------------

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun x963(key: ECPublicKey): ByteArray {
        fun fixed(n: BigInteger) = n.toByteArray().let { b -> if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun publicKey(x963: ByteArray, like: ECPublicKey): ECPublicKey {
        require(x963.size == 65 && x963[0] == 4.toByte()) { tr("clé publique invalide") }
        val point = ECPoint(BigInteger(1, x963.copyOfRange(1, 33)), BigInteger(1, x963.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, like.params)) as ECPublicKey
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }.doFinal(INFO.encodeToByteArray() + 1)
    }

    private fun key(own: KeyPair, theirs: ECPublicKey, code: String): SecretKeySpec {
        val shared = KeyAgreement.getInstance("ECDH").apply { init(own.private); doPhase(theirs, true) }.generateSecret()
        return SecretKeySpec(hkdf(shared, code.encodeToByteArray()), "AES")
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    // -------------------- the bundle --------------------

    fun collect(): SettingsBundle {
        val addons = Core.addons.value
        return SettingsBundle(
            from = deviceName(),
            addons = addons.map { it.transportUrl },
            disabledAddons = addons.filter { it.id in Core.disabled.value }.map { it.transportUrl },
            playlists = IptvStore.playlists.value.filter { !it.url.startsWith("file://") }.map { p ->
                BundlePlaylist(p.name, p.url, p.xtream?.host, p.xtream?.username, p.xtream?.password, p.xtream?.output ?: "ts")
            },
            secrets = listOf("debrid.", "stremio.", "trakt.", "simkl.", "mediaserver.").fold(emptyMap()) { acc, prefix -> acc + SecretStore.withPrefix(prefix) },
            mediaServers = MediaServerKind.entries.mapNotNull { k -> MediaServers.savedOrNull(k)?.let { k.name.lowercase() to it } }.toMap(),
            watch = WatchStore.entries.value.values.toList(),
            playerPrefs = PlayerPrefs.current,
        )
    }

    /** Applies a received bundle: additive, nothing already here is removed.
     *  Returns a summary for the screen. */
    suspend fun apply(bundle: SettingsBundle): String {
        var addons = 0
        for (url in bundle.addons) if (!Core.isInstalled(url) && Core.installAddon(url) == null) addons++
        Core.addons.value.filter { a -> bundle.disabledAddons.any { Core.isSameAddon(it, a.transportUrl) } }.forEach { Core.setEnabled(it, false) }
        var playlists = 0
        for (p in bundle.playlists) {
            val ref = if (p.host != null) PlaylistRef(name = p.name, xtream = app.veltrio.shared.iptv.XtreamAccount(p.host, p.username ?: "", p.output, p.password ?: ""))
            else PlaylistRef(name = p.name, url = p.url)
            if (IptvStore.import(ref)) playlists++
        }
        // Accounts and keys: what isn't set here yet.
        val secrets = bundle.secrets.filter { (name, _) -> SecretStore.get(name) == null }
        secrets.forEach { (name, value) -> SecretStore.set(name, value) }
        bundle.mediaServers.forEach { (name, saved) ->
            val kind = MediaServerKind.entries.firstOrNull { it.name.lowercase() == name } ?: return@forEach
            if (MediaServers.savedOrNull(kind)?.userName == null) MediaServers.save(kind, saved)
        }
        DebridService.reload()
        StremioAccount.reload()
        TraktAccount.reload()
        SimklAccount.reload()
        MediaServers.all.forEach { it.reload() }
        val titles = WatchStore.mergeEntries(bundle.watch)
        bundle.playerPrefs?.let { prefs -> PlayerPrefs.update { prefs } }
        val accounts = secrets.keys.map { it.substringBefore('.') }.distinct().size
        fun count(n: Int, one: String, many: String) = if (n == 0) null else "$n ${if (n > 1) many else one}"
        return listOfNotNull(
            count(addons, "addon", "addons"),
            count(playlists, "playlist", "playlists"),
            count(accounts, tr("compte ou service"), tr("comptes et services")),
            count(titles, tr("titre de la Collection"), tr("titres de la Collection")),
        ).joinToString(", ").ifEmpty { tr("rien de nouveau, tout était déjà là") }
    }

    /** The name the user gave the device ("Salon"), else its model. */
    fun deviceName(): String = deviceLabel ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: tr("Android")

    internal var deviceLabel: String? = null

    internal fun init(context: Context) {
        deviceLabel = runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // -------------------- receiving (TV) --------------------

    data class ReceiverState(
        val code: String = "",
        val name: String = "",
        /** "192.168.1.20:8765", to type on the phone when discovery is blocked. */
        val addresses: List<String> = emptyList(),
        val running: Boolean = false,
        val result: String? = null,
        val error: String? = null,
        val busy: Boolean = false,
    )

    /** One receiving session: a server, its DNS-SD record and its code. */
    class Receiver(private val context: Context) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _state = MutableStateFlow(ReceiverState())
        val state: StateFlow<ReceiverState> = _state
        private var keys = newKeyPair()
        private var failures = 0
        private var server: ServerSocket? = null
        private var registration: NsdManager.RegistrationListener? = null
        private val nsd = context.getSystemService(NsdManager::class.java)

        private fun newCode() = "%06d".format(random.nextInt(1_000_000))

        fun start() {
            val name = tr("Veltrio · %s", deviceName())
            // A fixed port when free, so it can be typed (and forwarded).
            val socket = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
            _state.value = ReceiverState(code = newCode(), name = name, addresses = localAddresses().map { "$it:${socket.localPort}" }, running = true)
            server = socket
            scope.launch {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { runCatching { client.use { handle(it) } }.onFailure { Log.w("Veltrio", "[pair] ${it.message}") } }
                }
            }
            val info = NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                port = socket.localPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {}
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    _state.value = _state.value.copy(error = tr("Impossible de s'annoncer sur le réseau (%s).", errorCode))
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            registration = listener
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        }

        fun stop() {
            registration?.let { runCatching { nsd.unregisterService(it) } }
            registration = null
            runCatching { server?.close() }
            scope.cancel()
            _state.value = _state.value.copy(running = false)
        }

        private suspend fun handle(socket: Socket) {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val out = socket.getOutputStream()
            val (method, path) = requestLine.split(' ').let { it.getOrNull(0) to it.getOrNull(1) }
            if (path?.substringBefore('?') != "/veltrio-pair") return respond(out, 404, """{"error":"not found"}""")
            when (method) {
                "GET" -> respond(out, 200, buildJsonObject {
                    put("name", _state.value.name)
                    put("publicKey", b64(x963(keys.public as ECPublicKey)))
                    put("version", 1)
                }.toString())
                "POST" -> {
                    if (length !in 1..(64 shl 20)) return respond(out, 413, """{"error":"too large"}""")
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    receive(body.decodeToString(0, read), out)
                }
                else -> respond(out, 405, """{"error":"method"}""")
            }
        }

        private suspend fun receive(text: String, out: OutputStream) {
            val json = Storage.json.parseToJsonElement(text)
            val plain = try {
                val theirs = publicKey(unb64(json.str("publicKey")!!), keys.public as ECPublicKey)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(keys, theirs, _state.value.code), GCMParameterSpec(128, unb64(json.str("nonce")!!)))
                cipher.doFinal(unb64(json.str("data")!!))
            } catch (e: Exception) {
                // A wrong code: after five, a new code and key.
                if (++failures >= 5) {
                    failures = 0
                    keys = newKeyPair()
                    _state.value = _state.value.copy(code = newCode(), error = tr("Trop d'essais : nouveau code."))
                } else _state.value = _state.value.copy(error = tr("Code incorrect."))
                return respond(out, 403, """{"error":"Code incorrect."}""")
            }
            _state.value = _state.value.copy(busy = true, error = null)
            val bundle = Storage.json.decodeFromString(SettingsBundle.serializer(), plain.decodeToString())
            val summary = withContext(Dispatchers.IO) { apply(bundle) }
            _state.value = _state.value.copy(busy = false, result = tr("Reçu de %s : %s.", bundle.from, summary))
            respond(out, 200, buildJsonObject { put("result", summary) }.toString())
        }

        private fun readLine(input: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 8192) return null
                sb.append(c.toChar())
            }
        }

        private fun respond(out: OutputStream, status: Int, body: String) {
            val bytes = body.encodeToByteArray()
            out.write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    // -------------------- sending (phone) --------------------

    data class Target(val name: String, val host: String, val port: Int)

    const val PORT = 8765

    /** "192.168.1.20" or "192.168.1.20:8765". */
    fun manualTarget(address: String): Target? {
        val a = address.trim().ifEmpty { return null }
        val host = a.substringBeforeLast(':').takeIf { ':' in a } ?: a
        val port = if (':' in a) a.substringAfterLast(':').toIntOrNull() ?: return null else PORT
        return Target(a, host, port)
    }

    private fun localAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().map { it.hostAddress!! }
    }.getOrDefault(emptyList())

    /** TVs waiting for settings on the network. */
    class Finder(context: Context) {
        private val nsd = context.getSystemService(NsdManager::class.java)
        private val _targets = MutableStateFlow<List<Target>>(emptyList())
        val targets: StateFlow<List<Target>> = _targets
        private val found = mutableMapOf<String, Target>()
        private var listener: NsdManager.DiscoveryListener? = null
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var resolving = false

        fun start() {
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(queue) {
                        queue.addLast(info)
                        if (resolving) return
                        resolving = true
                    }
                    resolveNext()
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(found) { found.remove(info.serviceName) }
                    _targets.value = synchronized(found) { found.values.sortedBy { it.name } }
                }
            }
            listener = l
            runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
        }

        @Suppress("DEPRECATION")
        private fun resolveNext() {
            val next = synchronized(queue) { queue.removeFirstOrNull().also { if (it == null) resolving = false } } ?: return
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null) synchronized(found) { found[info.serviceName] = Target(info.serviceName, host, info.port) }
                    _targets.value = synchronized(found) { found.values.sortedBy { it.name } }
                    resolveNext()
                }
            })
        }

        fun stop() {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
            listener = null
        }
    }

    /** Sends this device's settings to [target] with the code it shows.
     *  Returns the TV's summary; throws with a message for the user. */
    suspend fun send(target: Target, code: String): String = withContext(Dispatchers.IO) {
        val base = "http://${if (':' in target.host) "[${target.host}]" else target.host}:${target.port}/veltrio-pair"
        val hello = Http.json(Request.Builder().url(base).build(), target.name)
        val own = newKeyPair()
        val theirs = publicKey(unb64(hello.str("publicKey") ?: throw ServiceException(tr("Réponse inattendue de la TV."))), own.public as ECPublicKey)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(own, theirs, code.trim()), GCMParameterSpec(128, nonce)) }
        val plain = Storage.json.encodeToString(SettingsBundle.serializer(), collect()).encodeToByteArray()
        val body = buildJsonObject {
            put("publicKey", b64(x963(own.public as ECPublicKey)))
            put("nonce", b64(nonce))
            put("data", b64(cipher.doFinal(plain)))
        }.toString().toRequestBody("application/json".toMediaType())
        val answer = try {
            Http.json(Request.Builder().url(base).post(body).build(), target.name)
        } catch (e: ServiceException) {
            throw ServiceException(if ("403" in (e.message ?: "")) tr("Code incorrect : vérifiez celui affiché sur la TV.") else e.message ?: tr("Échec de l'envoi."))
        }
        answer.str("result") ?: tr("Envoyé.")
    }
}
