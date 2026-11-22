package app.veltrio.shared.iptv

import android.content.Context
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.Serializable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.time.Instant

/** The last channel list of each playlist (one per Xtream section) and its
 *  TV guide, kept in the app's cache: a new session shows them at once
 *  instead of asking the provider again. Providers limit requests and
 *  connections, and refetching 20,000 channels and the guide at every start
 *  got users blocked (HTTP 511). As on macOS and iOS.
 *
 *  Xtream passwords stay encrypted in [app.veltrio.shared.SecretStore]:
 *  stream URLs carry the password in their path, so it's stored as a
 *  placeholder and put back on load. */
object IptvCache {
    const val MAX_AGE = 12 * 3600_000L
    const val GUIDE_MAX_AGE = 6 * 3600_000L
    private const val PASSWORD = "{veltrio-password}"

    private lateinit var dir: File
    /** Compact: a big playlist is tens of thousands of entries. */
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        dir = File(context.cacheDir, "iptv")
    }

    class Entry(val items: List<ChannelItem>, val savedAt: Long) {
        val isFresh get() = System.currentTimeMillis() - savedAt < MAX_AGE
    }

    @Serializable
    private class Stored(val savedAt: Long, val items: List<ChannelItem>)

    @Serializable
    private class Programme(
        val channelId: String, val start: Long, val end: Long, val title: String,
        val description: String? = null, val category: String? = null, val image: String? = null,
    )

    @Serializable
    private class Guide(val savedAt: Long, val programmes: Map<String, List<Programme>>, val matches: Map<String, String>)

    private fun file(playlistId: String, name: String) = File(dir, "$playlistId-$name.json")

    /** The `/user/password/` segment of the playlist's stream URLs. */
    private fun secret(playlist: PlaylistRef): Pair<String, String>? {
        val account = playlist.xtream?.takeIf { it.password.isNotEmpty() } ?: return null
        val user = XtreamClient.seg(account.username)
        return "/$user/${XtreamClient.seg(account.password)}/" to "/$user/$PASSWORD/"
    }

    fun load(playlist: PlaylistRef, kind: ChannelKind): Entry? = try {
        val f = file(playlist.id, kind.name)
        if (!f.exists()) null else {
            val stored = json.decodeFromString(Stored.serializer(), f.readText())
            val secret = secret(playlist)
            Entry(stored.items.map { c -> secret?.let { c.copy(streamUrl = c.streamUrl.replace(it.second, it.first)) } ?: c }, stored.savedAt)
        }
    } catch (_: Exception) {
        null
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun save(playlist: PlaylistRef, kind: ChannelKind, items: List<ChannelItem>) {
        val secret = secret(playlist)
        val masked = items.map { c -> secret?.let { c.copy(streamUrl = c.streamUrl.replace(it.first, it.second)) } ?: c }
        writeStream(file(playlist.id, kind.name), Stored.serializer(), Stored(System.currentTimeMillis(), masked))
    }

    /** The guide and its name matches, when one younger than [GUIDE_MAX_AGE] is kept. */
    @OptIn(ExperimentalSerializationApi::class)
    fun loadGuide(playlistId: String): Triple<Map<String, List<EpgProgramme>>, Map<String, String>, Long>? = try {
        val f = file(playlistId, "guide")
        if (!f.exists()) null else {
            val g = BufferedInputStream(f.inputStream()).use { json.decodeFromStream(Guide.serializer(), it) }
            if (System.currentTimeMillis() - g.savedAt >= GUIDE_MAX_AGE) null
            else Triple(g.programmes.mapValues { (_, list) ->
                list.map { EpgProgramme(it.channelId, Instant.ofEpochSecond(it.start), Instant.ofEpochSecond(it.end), it.title, it.description, it.category, it.image) }
            }, g.matches, g.savedAt)
        }
    } catch (_: Throwable) {
        null
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun saveGuide(playlistId: String, programmes: Map<String, List<EpgProgramme>>, matches: Map<String, String>) {
        val guide = Guide(System.currentTimeMillis(), programmes.mapValues { (_, list) ->
            list.map { Programme(it.channelId, it.start.epochSecond, it.end.epochSecond, it.title, it.description, it.category, it.image) }
        }, matches)
        writeStream(file(playlistId, "guide"), Guide.serializer(), guide)
    }

    fun remove(playlistId: String) {
        dir.listFiles()?.filter { it.name.startsWith("$playlistId-") }?.forEach { it.delete() }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T> writeStream(target: File, serializer: SerializationStrategy<T>, value: T) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${target.name}.tmp")
            BufferedOutputStream(tmp.outputStream(), 1 shl 16).use { out ->
                json.encodeToStream(serializer, value, out)
            }
            tmp.renameTo(target)
        } catch (_: Throwable) {
            // A lost cache (or OOM on a huge XMLTV feed) only means asking the provider next time.
        }
    }
}
