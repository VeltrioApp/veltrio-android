package app.veltrio.shared.iptv

import app.veltrio.shared.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import uniffi.veltrio.IptvChannel
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** An Xtream Codes account. The password isn't in the JSON: it's kept
 *  encrypted in SecretStore, by playlist id. */
@Serializable
data class XtreamAccount(
    val host: String = "",
    val username: String = "",
    /** Live container: "ts" or "m3u8". */
    val output: String = "ts",
    @Transient val password: String = "",
) {
    /** "http://" added when missing, trailing slashes removed. */
    val baseUrl: String get() {
        val h = host.trim().trimEnd('/')
        return if (h.isNotEmpty() && "://" !in h) "http://$h" else h
    }
}

/** An IPTV source the user added: an M3U URL or an Xtream account. */
@Serializable
data class PlaylistRef(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** M3U URL; empty for Xtream. */
    val url: String = "",
    val xtream: XtreamAccount? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val isXtream get() = xtream != null
    val sourceLabel get() = if (xtream != null) tr("XTREAM · %s", xtream.baseUrl) else url
}

enum class ChannelKind { Live, Movie, Series }

/** A channel, or for Xtream a film or series, in one shape so the lists and
 *  search don't need three. */
data class ChannelItem(
    val id: String,
    val name: String,
    /** Playable URL; empty for a series (opens its page instead). */
    val streamUrl: String = "",
    val logo: String? = null,
    val group: String = "",
    val kind: ChannelKind = ChannelKind.Live,
    /** XMLTV channel id, to find the guide. */
    val tvgId: String? = null,
    val tvgName: String? = null,
    /** Xtream stream id (live, film) or series id. */
    val xtreamId: String? = null,
    /** Xtream keeps past programmes (catch-up), this many days. */
    val archiveDays: Int = 0,
    val rating: String? = null,
    val year: String? = null,
    /** Position in the whole playlist, so numbers don't move when a group or
     *  a search narrows the list. */
    val number: Int = 0,
) {
    val numberLabel get() = if (number > 0) "%03d".format(number) else ""
    val hasCatchup get() = archiveDays > 0

    /** A playlist entry pointing at a file (provider VOD lists) rather than
     *  a live stream: it gets a scrubber. */
    val isFile: Boolean get() {
        val path = runCatching { URI(streamUrl).path }.getOrNull() ?: streamUrl
        return listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { path.endsWith(it, ignoreCase = true) }
    }

    val subtitle get() = listOfNotNull(year, rating?.let { "★ $it" }, group).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        fun from(c: IptvChannel) = ChannelItem(
            id = c.id, name = c.name, streamUrl = c.streamUrl,
            logo = c.logoUrl?.takeIf { it.isNotBlank() },
            group = c.group?.takeIf { it.isNotBlank() } ?: tr("Sans groupe"),
            tvgId = c.tvgId?.takeIf { it.isNotBlank() }, tvgName = c.tvgName?.takeIf { it.isNotBlank() },
        )
    }
}

/** An Xtream series: seasons of episodes. */
data class XtreamSeries(
    val name: String,
    val plot: String? = null,
    val cover: String? = null,
    val backdrop: String? = null,
    val genre: String? = null,
    val released: String? = null,
    val rating: String? = null,
    val episodes: List<XtreamEpisode> = emptyList(),
)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String? = null,
    val image: String? = null,
    val duration: String? = null,
    val streamUrl: String,
) {
    val code get() = "S$season · E$number"
}

private val hourMinute = DateTimeFormatter.ofPattern("HH:mm")
private val dayHourMinute = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.FRENCH)
internal fun Instant.local(format: DateTimeFormatter): String = format.format(atZone(ZoneId.systemDefault()))

/** One programme of the TV guide. */
data class EpgProgramme(
    val channelId: String,
    val start: Instant,
    val end: Instant,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val image: String? = null,
) {
    fun progressAt(now: Instant): Float =
        if (end > start) ((now.epochSecond - start.epochSecond).toFloat() / (end.epochSecond - start.epochSecond)).coerceIn(0f, 1f) else 0f

    val timeRange get() = "${start.local(hourMinute)} – ${end.local(hourMinute)}"
}

/** A short-EPG / catch-up entry from player_api.php. */
data class XtreamProgramme(
    val start: Instant,
    val end: Instant,
    val title: String,
    val description: String?,
    val hasArchive: Boolean,
) {
    val timeRange get() = "${start.local(dayHourMinute)} – ${end.local(hourMinute)}"
    /** "lun. 20:30", the replay list's first column. */
    val startLabel get() = start.local(dayHourMinute)
    val durationMinutes get() = maxOf(1, Math.ceil((end.epochSecond - start.epochSecond) / 60.0).toInt())
}
