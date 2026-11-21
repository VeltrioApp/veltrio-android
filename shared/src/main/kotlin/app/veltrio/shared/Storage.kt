package app.veltrio.shared

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** JSON files in the app's private storage, the counterpart of the macOS
 *  app's Application Support folder. Same file names and shapes. */
object Storage {
    lateinit var folder: File
        private set

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /** Called once from the Application, before anything else. */
    fun init(context: Context) {
        folder = context.filesDir
        AppPrefs.load()
        PlayerPrefs.load()
        Core.init(context)
        WatchStore.init()
        app.veltrio.shared.iptv.IptvCache.init(context)
        app.veltrio.shared.iptv.IptvStore.init()
        DownloadManager.init(context)
        Pairing.init(context)
        DownloadManager.serviceClass = app.veltrio.android.DownloadService::class.java
    }

    fun <T> load(file: String, serializer: KSerializer<T>, fallback: T): T = try {
        val f = File(folder, file)
        if (f.exists()) json.decodeFromString(serializer, f.readText()) else fallback
    } catch (_: Exception) {
        fallback
    }

    /** Writes through a temporary file, so a crash mid-write can't leave a
     *  truncated file behind. */
    fun <T> save(file: String, serializer: KSerializer<T>, value: T) {
        try {
            val target = File(folder, file)
            val tmp = File(folder, "$file.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            tmp.renameTo(target)
        } catch (_: Exception) {
            // Losing a save is better than crashing the UI.
        }
    }
}
