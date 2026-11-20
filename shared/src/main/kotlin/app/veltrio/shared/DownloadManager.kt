package app.veltrio.shared

import app.veltrio.shared.i18n.tr
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class DownloadStatus { Downloading, Paused, Completed, Failed }

/** A film or episode saved for offline playback. */
@Serializable
data class DownloadItem(
    /** The video id: tt123 for a film, tt123:1:3 for an episode. */
    val id: String,
    val title: String = "",
    val subtitle: String? = null,
    val contentType: String? = null,
    val contentId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val poster: String? = null,
    val sourceUrl: String = "",
    /** Set for a torrent played through the local engine: stopped once the file is down. */
    val infoHash: String? = null,
    val localPath: String = "",
    val bytesExpected: Long = 0,
    val bytesDownloaded: Long = 0,
    val status: DownloadStatus = DownloadStatus.Paused,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val progress get() = if (bytesExpected > 0) (bytesDownloaded.toDouble() / bytesExpected).coerceAtMost(1.0) else 0.0
}

/** Offline copies, as on macOS: a stream resolved to an HTTP(S) URL (direct,
 *  debrid, or the local torrent engine's server) is saved in the app's
 *  storage. A paused or interrupted download resumes where it left off when
 *  the server supports ranges. While one runs, a foreground service keeps
 *  the app alive (Android stops background work otherwise). */
object DownloadManager {
    private const val FILE = "downloads.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()
    private val client by lazy { Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }
    private lateinit var appContext: Context
    lateinit var folder: File
        private set

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    internal fun init(context: Context) {
        appContext = context.applicationContext
        folder = File(context.getExternalFilesDir(null) ?: context.filesDir, tr("Downloads"))
        // Nothing runs at launch: an interrupted download waits to be resumed.
        _items.value = Storage.load(FILE, ListSerializer(DownloadItem.serializer()), emptyList())
            .map { if (it.status == DownloadStatus.Downloading) it.copy(status = DownloadStatus.Paused) else it }
    }

    /** The notification service is started by the app, which knows it. */
    var serviceClass: Class<*>? = null

    private fun save() = Storage.save(FILE, ListSerializer(DownloadItem.serializer()), _items.value)

    private fun update(id: String, change: (DownloadItem) -> DownloadItem) {
        synchronized(this) {
            _items.value = _items.value.map { if (it.id == id) change(it) else it }
        }
    }

    fun item(id: String) = _items.value.firstOrNull { it.id == id }

    /** The finished local file for a video id, if any. */
    fun localFile(id: String) = item(id)?.takeIf { it.status == DownloadStatus.Completed && File(it.localPath).exists() }?.localPath

    val anyRunning get() = running.isNotEmpty()

    /** Resolves the stream, then downloads it. The request carries what the
     *  player would need, so the copy plays with the same progress. */
    fun start(id: String, request: PlayRequest) {
        val existing = item(id)
        if (existing?.status == DownloadStatus.Downloading || existing?.status == DownloadStatus.Completed) return
        val base = (existing ?: DownloadItem(id)).copy(
            title = request.title, subtitle = request.subtitle, contentType = request.contentType, contentId = request.contentId,
            season = request.season, episode = request.episode, poster = request.poster,
            status = DownloadStatus.Downloading, error = null,
        )
        synchronized(this) { _items.value = _items.value.filter { it.id != id } + base }
        save()
        startService()
        // Registered before it starts: the loop runs while it's listed.
        launchFor(id) {
            val url = try {
                request.url ?: Core.resolve(request.stream!!, request.season, request.episode)
            } catch (e: Exception) {
                fail(id, Core.humanize(e))
                running.remove(id)
                return@launchFor
            }
            var ext = runCatching { URI(url).path.substringAfterLast('.', "") }.getOrDefault("")
            if (ext.length !in 1..4) ext = "mkv"
            folder.mkdirs()
            val path = File(folder, id.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("") + ".$ext").absolutePath
            update(id) { it.copy(sourceUrl = url, infoHash = request.stream?.infoHash, localPath = path) }
            download(id)
        }
    }

    private fun launchFor(id: String, block: suspend () -> Unit) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }
        running[id] = job
        job.start()
    }

    fun resume(id: String) {
        val item = item(id) ?: return
        if (item.status !in setOf(DownloadStatus.Paused, DownloadStatus.Failed) || item.sourceUrl.isEmpty()) return
        update(id) { it.copy(status = DownloadStatus.Downloading, error = null) }
        save()
        startService()
        launchFor(id) { download(id) }
    }

    fun pause(id: String) {
        running.remove(id)?.cancel()
        update(id) { if (it.status == DownloadStatus.Downloading) it.copy(status = DownloadStatus.Paused) else it }
        save()
    }

    fun remove(id: String) {
        pause(id)
        item(id)?.let { File(it.localPath).delete() }
        synchronized(this) { _items.value = _items.value.filter { it.id != id } }
        save()
    }

    private suspend fun download(id: String) {
        val item = item(id) ?: return
        try {
            val target = File(item.localPath)
            var have = if (target.exists()) target.length() else 0L
            val request = Request.Builder().url(item.sourceUrl).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException(tr("Le serveur a répondu %s.", response.code))
                // A server that ignores the range sends the whole file again.
                val resuming = have > 0 && response.code == 206
                if (!resuming) have = 0
                val expected = have + response.body.contentLength().coerceAtLeast(0)
                update(id) { it.copy(bytesExpected = expected, bytesDownloaded = have) }
                var done = have
                var lastReport = 0L
                FileOutputStream(target, resuming).use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(1 shl 16)
                    while (scope.isActive && running.containsKey(id)) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        if (System.currentTimeMillis() - lastReport > 1000) {
                            lastReport = System.currentTimeMillis()
                            update(id) { it.copy(bytesDownloaded = done) }
                        }
                    }
                }
                if (!running.containsKey(id)) return // paused meanwhile
                update(id) { it.copy(bytesDownloaded = done, bytesExpected = maxOf(it.bytesExpected, done), status = DownloadStatus.Completed) }
                save()
                stopEngine(id)
            }
        } catch (e: Exception) {
            if (running.containsKey(id)) fail(id, e.message ?: tr("Erreur"))
        } finally {
            running.remove(id)
        }
    }

    private fun fail(id: String, message: String) {
        update(id) { it.copy(status = DownloadStatus.Failed, error = message) }
        save()
        stopEngine(id)
        Log.w("Veltrio", "[download] $id: $message")
    }

    private fun stopEngine(id: String) {
        item(id)?.infoHash?.let { Core.stopTorrent(it) }
    }

    private fun startService() {
        val cls = serviceClass ?: return
        runCatching { appContext.startForegroundService(Intent(appContext, cls)) }
    }
}
