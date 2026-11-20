package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Chromecast, as on macOS and Windows, without Google's SDK (which needs
 *  Play services): devices are found over DNS-SD (_googlecast._tcp, through
 *  Android's NsdManager), and casting speaks Cast V2 — a TLS connection to
 *  the device (port 8009, self-signed certificate) carrying length-prefixed
 *  protobuf CastMessages with JSON inside. The Default Media Receiver
 *  (CC1AD845) is launched and asked to LOAD the URL, which the Chromecast
 *  fetches itself: debrid, IPTV, media servers and addon links work; the
 *  local torrent server (127.0.0.1) doesn't. */
object ChromecastService {
    data class Device(val name: String, val host: String, val port: Int)
    enum class CastState { Idle, Connecting, Casting, Failed }
    data class State(val state: CastState = CastState.Idle, val deviceName: String? = null, val error: String? = null) {
        val isCasting get() = state == CastState.Connecting || state == CastState.Casting
    }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val found = mutableMapOf<String, Device>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var session: CastSession? = null

    // -------------------- discovery --------------------

    fun startDiscovery(context: Context) {
        if (listener != null) return
        val manager = context.applicationContext.getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName) }
                publish()
            }
        }
        listener = l
        runCatching { manager.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w("Veltrio", "[cast] discovery: ${it.message}"); listener = null }
    }

    fun stopDiscovery() {
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
    }

    /** NsdManager resolves one service at a time. */
    private fun enqueue(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        val manager = nsd ?: return
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(info: NsdServiceInfo) {
                // The friendly name is the TXT record's "fn"; the instance name is an opaque id.
                val name = info.attributes["fn"]?.decodeToString() ?: info.serviceName
                val host = info.host?.hostAddress
                if (host != null) synchronized(found) { found[info.serviceName] = Device(name, host, info.port.takeIf { it > 0 } ?: 8009) }
                publish()
                resolveNext()
            }
        })
    }

    private fun publish() {
        _devices.value = synchronized(found) { found.values.sortedBy { it.name } }
    }

    // -------------------- casting --------------------

    /** Whether a Chromecast could fetch this URL itself. */
    fun canCast(url: String?): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && uri.host !in setOf("localhost", "127.0.0.1", "::1", null)
    }

    fun cast(device: Device, url: String, title: String, poster: String?, isLive: Boolean, startAt: Double) {
        stop()
        _state.value = State(CastState.Connecting, device.name)
        val s = CastSession(device, CastSession.Media(url, title, poster, isLive, startAt))
        s.onEvent = { outcome ->
            if (session === s) {
                _state.value = when (outcome) {
                    CastSession.Outcome.Playing -> _state.value.copy(state = CastState.Casting)
                    CastSession.Outcome.Closed -> {
                        session = null
                        if (_state.value.state == CastState.Connecting) State(CastState.Failed, device.name, tr("Le Chromecast a fermé la connexion."))
                        else State()
                    }
                    CastSession.Outcome.Failed -> {
                        session = null
                        State(CastState.Failed, device.name, s.failure ?: tr("Le Chromecast n'a pas pu lire ce flux."))
                    }
                }
            }
        }
        session = s
        s.run()
    }

    fun stop() {
        val s = session
        session = null
        s?.stop()
        if (_state.value.state != CastState.Idle) _state.value = State()
    }
}

/** One connection to one device, playing one item. */
internal class CastSession(private val device: ChromecastService.Device, private val media: Media) {
    data class Media(val url: String, val title: String, val poster: String?, val isLive: Boolean, val startAt: Double)
    enum class Outcome { Playing, Failed, Closed }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val requestId = AtomicInteger(1)
    private var sessionId: String? = null
    private var loaded = false
    private var playing = false
    var onEvent: (Outcome) -> Unit = {}
    var failure: String? = null
        private set

    fun run() = scope.launch {
        try {
            // Chromecasts present a device certificate no CA signs.
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            })
            val context = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
            val raw = java.net.Socket().apply { connect(InetSocketAddress(device.host, device.port), 8000) }
            val tls = context.socketFactory.createSocket(raw, device.host, device.port, true) as SSLSocket
            tls.startHandshake()
            socket = tls
            out = tls.outputStream
            send(NS_CONNECTION, "receiver-0", buildJsonObject { put("type", "CONNECT") })
            send(NS_RECEIVER, "receiver-0", buildJsonObject { put("type", "LAUNCH"); put("appId", DEFAULT_MEDIA_RECEIVER); put("requestId", nextId()) })
            launch {
                while (isActive) {
                    delay(5000)
                    runCatching { send(NS_HEARTBEAT, "receiver-0", buildJsonObject { put("type", "PING") }) }
                }
            }
            val input = DataInputStream(tls.inputStream)
            while (isActive) {
                val length = input.readInt()
                if (length <= 0 || length > 1 shl 20) break
                val body = ByteArray(length).also { input.readFully(it) }
                val message = CastMessage.decode(body) ?: continue
                if (!handle(message)) break
            }
            onEvent(Outcome.Closed)
        } catch (e: Exception) {
            if (!isActive) return@launch // stopped by the user
            if (playing) onEvent(Outcome.Closed) // the device went away or another sender took over
            else {
                failure = tr("Connexion au Chromecast impossible : %s", e.message)
                onEvent(Outcome.Failed)
            }
        } finally {
            runCatching { socket?.close() }
            scope.cancel()
        }
    }

    fun stop() {
        val sid = sessionId
        scope.launch {
            if (sid != null) runCatching { send(NS_RECEIVER, "receiver-0", buildJsonObject { put("type", "STOP"); put("sessionId", sid); put("requestId", nextId()) }) }
            runCatching { socket?.close() }
            scope.cancel()
        }
    }

    private fun nextId() = requestId.incrementAndGet()

    /** False when the session is over. */
    private fun handle(message: CastMessage): Boolean {
        val json = runCatching { Storage.json.parseToJsonElement(message.payload) as? JsonObject }.getOrNull() ?: return true
        when (json.str("type")) {
            "PING" -> send(NS_HEARTBEAT, message.source, buildJsonObject { put("type", "PONG") })
            "RECEIVER_STATUS" -> {
                val app = json["status"].array("applications").firstOrNull { it.str("appId") == DEFAULT_MEDIA_RECEIVER }
                val transport = app.str("transportId")
                if (loaded || transport == null) return true
                loaded = true
                sessionId = app.str("sessionId")
                send(NS_CONNECTION, transport, buildJsonObject { put("type", "CONNECT") })
                send(NS_MEDIA, transport, loadRequest())
            }
            "MEDIA_STATUS" -> {
                val state = json.array("status").firstOrNull().str("playerState")
                if (!playing && (state == "PLAYING" || state == "BUFFERING")) {
                    playing = true
                    onEvent(Outcome.Playing)
                }
            }
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST", "LAUNCH_ERROR" -> {
                failure = tr("Le Chromecast n'a pas pu lire ce flux.")
                onEvent(Outcome.Failed)
                return false
            }
            "CLOSE" -> return false
        }
        return true
    }

    private fun loadRequest(): JsonElement = buildJsonObject {
        put("type", "LOAD")
        put("requestId", nextId())
        put("autoplay", true)
        put("currentTime", if (media.isLive) 0.0 else media.startAt)
        putJsonObject("media") {
            put("contentId", media.url)
            put("contentType", contentType(media.url))
            put("streamType", if (media.isLive) "LIVE" else "BUFFERED")
            putJsonObject("metadata") {
                put("metadataType", 0)
                put("title", media.title)
                media.poster?.let { p -> putJsonArray("images") { addJsonObject { put("url", p) } } }
            }
        }
    }

    @Synchronized
    private fun send(ns: String, destination: String, payload: JsonElement) {
        val o = out ?: return
        val body = CastMessage("sender-0", destination, ns, payload.toString()).encode()
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 24).toByte(); frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte(); frame[3] = body.size.toByte()
        body.copyInto(frame, 4)
        o.write(frame)
        o.flush()
    }

    companion object {
        private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        fun contentType(url: String) = when (runCatching { URI(url).path }.getOrNull()?.substringAfterLast('.', "")?.lowercase()) {
            "m3u8" -> "application/x-mpegURL"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }
}

/** The six fields of CastMessage (cast_channel.proto), encoded by hand:
 *  1 protocol_version (CASTV2_1_0 = 0), 2 source_id, 3 destination_id,
 *  4 namespace, 5 payload_type (STRING = 0), 6 payload_utf8. */
internal data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray {
        val d = ByteArrayOutputStream()
        fun varint(value: Long) {
            var v = value
            while (v >= 0x80) {
                d.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            d.write(v.toInt())
        }
        fun text(field: Int, s: String) {
            val bytes = s.encodeToByteArray()
            varint((field shl 3 or 2).toLong())
            varint(bytes.size.toLong())
            d.write(bytes)
        }
        varint(1 shl 3); varint(0)
        text(2, source)
        text(3, destination)
        text(4, namespace)
        varint(5 shl 3); varint(0)
        text(6, payload)
        return d.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): CastMessage? {
            val fields = mutableMapOf<Int, String>()
            var i = 0
            fun varint(): Long? {
                var result = 0L
                var shift = 0
                while (i < bytes.size && shift < 64) {
                    val b = bytes[i++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
                return null
            }
            while (i < bytes.size) {
                val tag = varint() ?: return null
                when ((tag and 7).toInt()) {
                    0 -> varint() ?: return null
                    2 -> {
                        val length = varint()?.toInt() ?: return null
                        if (i + length > bytes.size) return null
                        fields[(tag shr 3).toInt()] = bytes.decodeToString(i, i + length)
                        i += length
                    }
                    else -> return null
                }
            }
            val ns = fields[4] ?: return null
            return CastMessage(fields[2] ?: "", fields[3] ?: "", ns, fields[6] ?: "")
        }
    }
}
