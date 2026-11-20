package app.veltrio.shared.iptv

import app.veltrio.shared.i18n.tr
import android.util.Xml
import app.veltrio.shared.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** The XMLTV guide of the selected playlist. An Xtream provider serves it at
 *  xmltv.php; an M3U playlist names it in its header (url-tvg /
 *  x-tvg-url). Guides are often tens of megabytes and gzipped, so the file
 *  is streamed and only the playlist's channels, from an hour back to a day
 *  ahead, are kept — as on macOS. */
object EpgStore {
    private const val MAX_AGE = 6 * 3600_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var programmes = mapOf<String, List<EpgProgramme>>()
    /** Playlist channel id → XMLTV channel id, for channels matched by name. */
    private var nameMatches = mapOf<String, String>()
    private var loadedFor: String? = null
    private var loadedAt = 0L
    private var job: Job? = null

    /** Bumped when the guide changes, for screens to redraw. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    var error: String? = null
        private set

    val hasGuide get() = programmes.isNotEmpty()

    fun load(playlist: PlaylistRef, channels: List<ChannelItem>, force: Boolean = false) {
        if (!force && loadedFor == playlist.id && (System.currentTimeMillis() - loadedAt < MAX_AGE || job?.isActive == true)) return
        loadedFor = playlist.id
        loadedAt = System.currentTimeMillis()
        error = null
        job?.cancel()
        val live = channels.filter { it.kind == ChannelKind.Live }
        _loading.value = true
        job = scope.launch {
            try {
                val url = guideUrl(playlist)
                if (url == null) apply(playlist.id, emptyMap(), emptyMap())
                else fetchAndParse(url, live).let { (p, m) -> apply(playlist.id, p, m) }
            } catch (e: Exception) {
                error = tr("Guide TV indisponible : %s", e.message)
                apply(playlist.id, emptyMap(), emptyMap())
            } finally {
                _loading.value = false
            }
        }
    }

    private fun apply(id: String, p: Map<String, List<EpgProgramme>>, m: Map<String, String>) {
        if (loadedFor != id) return // another playlist was picked meanwhile
        programmes = p
        nameMatches = m
        _version.value++
    }

    // -------------------- queries --------------------

    fun schedule(channel: ChannelItem): List<EpgProgramme> {
        channel.tvgId?.lowercase()?.let { programmes[it] }?.let { return it }
        return nameMatches[channel.id]?.let { programmes[it] }.orEmpty()
    }

    fun current(channel: ChannelItem, at: Instant = Instant.now()) = schedule(channel).firstOrNull { it.start <= at && at < it.end }
    fun upcoming(channel: ChannelItem, at: Instant = Instant.now()) = schedule(channel).filter { it.start > at }

    // -------------------- fetching --------------------

    /** Xtream: xmltv.php. M3U: the header of the playlist, read from its
     *  first few kilobytes rather than downloading it all. */
    private fun guideUrl(playlist: PlaylistRef): String? {
        playlist.xtream?.let { return XtreamClient.guideUrl(it) }
        if (playlist.url.startsWith("file://")) return File(URI(playlist.url)).bufferedReader().use { headerGuideUrl(it.readLine() ?: "") }
        val request = Request.Builder().url(playlist.url).build()
        return Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val buffer = ByteArray(8192)
            val read = r.body.byteStream().readNBytes(buffer, 0, buffer.size)
            headerGuideUrl(buffer.decodeToString(0, read).substringBefore('\n'))
        }
    }

    internal fun headerGuideUrl(header: String): String? {
        if (!header.trimStart('﻿').startsWith("#EXTM3U")) return null
        for (attribute in listOf("url-tvg", "x-tvg-url")) {
            val at = header.indexOf("$attribute=\"", ignoreCase = true).takeIf { it >= 0 } ?: continue
            val start = at + attribute.length + 2
            val end = header.indexOf('"', start).takeIf { it >= 0 } ?: continue
            // Some headers list several guides, comma-separated: take the first.
            val first = header.substring(start, end).split(',')[0].trim()
            if (runCatching { URI(first).scheme }.getOrNull() != null) return first
        }
        return null
    }

    private fun fetchAndParse(url: String, channels: List<ChannelItem>): Pair<Map<String, List<EpgProgramme>>, Map<String, String>> {
        val wantedIds = channels.mapNotNull { it.tvgId?.lowercase() }.toMutableSet()
        val wantedNames = channels.groupBy { XtreamClient.nameKey(it.tvgName ?: it.name) }.mapValues { it.value.first().id }
        val now = Instant.now()
        val from = now.minusSeconds(3600)
        val to = now.plusSeconds(24 * 3600)
        val client = Http.client.newBuilder().readTimeout(3, java.util.concurrent.TimeUnit.MINUTES).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(tr("le serveur a répondu %s", response.code))
            val raw = BufferedInputStream(response.body.byteStream())
            raw.mark(2)
            val gzipped = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val input: InputStream = if (gzipped) GZIPInputStream(raw, 64 * 1024) else raw

            val programmes = mutableMapOf<String, MutableList<EpgProgramme>>()
            val matches = mutableMapOf<String, String>()
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)
            var channelId: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")?.lowercase()
                    "display-name" -> {
                        val ch = channelId ?: continue
                        // A playlist channel without a matching tvg-id is matched by name.
                        val playlistId = wantedNames[XtreamClient.nameKey(parser.nextText())]
                        if (playlistId != null && playlistId !in matches) {
                            matches[playlistId] = ch
                            wantedIds += ch
                        }
                    }
                    "programme" -> {
                        val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                        val start = parseDate(parser.getAttributeValue(null, "start"))
                        val end = parseDate(parser.getAttributeValue(null, "stop")) ?: start?.plusSeconds(1800)
                        if (ch == null || ch !in wantedIds || start == null || end == null || end < from || start > to) {
                            skip(parser)
                            continue
                        }
                        readProgramme(parser, ch, start, end)?.let { programmes.getOrPut(ch) { mutableListOf() } += it }
                    }
                }
            }
            programmes.values.forEach { list -> list.sortBy { it.start } }
            return programmes to matches
        }
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun readProgramme(parser: XmlPullParser, channel: String, start: Instant, end: Instant): EpgProgramme? {
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var image: String? = null
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT || (event == XmlPullParser.END_TAG && parser.name == "programme")) break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> if (title == null) title = parser.nextText().trim() else skip(parser)
                "desc" -> if (desc == null) desc = parser.nextText().trim() else skip(parser)
                "category" -> if (category == null) category = parser.nextText().trim() else skip(parser)
                "icon" -> {
                    if (image == null) image = parser.getAttributeValue(null, "src")
                    skip(parser)
                }
                else -> skip(parser)
            }
        }
        return title?.ifEmpty { null }?.let {
            EpgProgramme(channel, start, end, it, desc?.ifEmpty { null }, category?.ifEmpty { null }, image)
        }
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** "20261119203000 +0100", or without the zone (UTC). */
    internal fun parseDate(raw: String?): Instant? {
        val s = raw?.trim() ?: return null
        if (s.length < 14) return null
        val local = runCatching { LocalDateTime.parse(s.substring(0, 14), dateFormat) }.getOrNull() ?: return null
        val zone = s.substring(14).trim()
        val offset = if (zone.length >= 5 && (zone[0] == '+' || zone[0] == '-')) {
            val h = zone.substring(1, 3).toIntOrNull() ?: 0
            val m = zone.substring(3, 5).toIntOrNull() ?: 0
            ZoneOffset.ofHoursMinutes(if (zone[0] == '-') -h else h, if (zone[0] == '-') -m else m)
        } else ZoneOffset.UTC
        return local.toInstant(offset)
    }
}
