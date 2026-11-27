package app.veltrio.shared.player

import app.veltrio.shared.i18n.tr
import android.content.Context
import android.view.SurfaceHolder
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An audio or subtitle track, as mpv lists it. */
data class MediaTrack(val id: Int, val type: String, val title: String?, val lang: String?, val codec: String?, val external: Boolean) {
    val label: String get() = listOfNotNull(
        lang?.let { java.util.Locale.forLanguageTag(it).displayLanguage.replaceFirstChar(Char::uppercase).ifEmpty { it } },
        title,
    ).distinct().joinToString(" · ").ifEmpty { "Piste $id" }
}

data class Chapter(val title: String?, val start: Double)

/** What the player screen draws from. */
data class PlayerState(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val paused: Boolean = false,
    val buffering: Boolean = true,
    val loaded: Boolean = false,
    val ended: Boolean = false,
    val failed: Boolean = false,
    val audio: List<MediaTrack> = emptyList(),
    val subtitles: List<MediaTrack> = emptyList(),
    val audioId: Int? = null,
    val subtitleId: Int? = null,
    val chapters: List<Chapter> = emptyList(),
    val speed: Double = 1.0,
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val hwdec: String? = null,
    val chapter: Int = -1,
    val subDelay: Double = 0.0,
) {
    /** The playhead is in a chapter titled like an intro (as macOS): where
     *  "Passer l'intro" jumps to, the next chapter's start; else null. */
    val introEnd: Double? get() {
        val title = chapters.getOrNull(chapter)?.title?.lowercase() ?: return null
        val hits = listOf("intro", "opening", "op ", "theme song", "generique", "générique")
        if (hits.none { it in title }) return null
        return chapters.getOrNull(chapter + 1)?.start
    }
}

/** libmpv drawing into a SurfaceView, as the macOS and Windows players: one
 *  instance per player screen, [release]d when it goes. */
class MpvPlayer(context: Context) : SurfaceHolder.Callback, MPVLib.EventObserver {
    private val mpv = checkNotNull(MPVLib.create(context.applicationContext)) { tr("libmpv could not start") }
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state
    private var released = false
    /** The decoding chosen at start, and the output it needs. */
    private var hwdec = "mediacodec-copy"
    private val videoOutput get() = if (hwdec == "mediacodec") "mediacodec_embed" else "gpu"

    init {
        with(mpv) {
            val prefs = app.veltrio.shared.PlayerPrefs.current
            // Direct decoding hands frames straight to the screen (the only
            // way a weak TV box plays 4K HEVC smoothly) but needs mpv's
            // mediacodec_embed output, which draws no subtitles; the copy
            // goes through the GPU output and its subtitles.
            hwdec = when (prefs.hwdec) {
                "auto" -> if (app.veltrio.shared.Device.isTv) "mediacodec" else "mediacodec-copy"
                else -> prefs.hwdec
            }
            if (hwdec == "mediacodec") {
                setOptionString("vo", videoOutput)
            } else {
                setOptionString("vo", videoOutput)
                setOptionString("gpu-context", "android")
                setOptionString("opengl-es", "yes")
            }
            setOptionString("hwdec", hwdec)
            setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            setOptionString("ao", "audiotrack,opensles")
            setOptionString("tls-verify", "no")
            setOptionString("cache", "yes")
            setOptionString("demuxer-max-bytes", "${prefs.cacheMiB * 1024 * 1024}")
            setOptionString("demuxer-max-back-bytes", "${prefs.cacheMiB / 2 * 1024 * 1024}")
            setOptionString("user-agent", "Veltrio-Android/0.1")
            // No fontconfig on Android: libass reads the system fonts.
            setOptionString("sub-fonts-dir", "/system/fonts")
            setOptionString("osd-fonts-dir", "/system/fonts")
            setOptionString("sub-font", "Roboto")
            setOptionString("sub-font-size", "46")
            setOptionString("sub-scale", prefs.subtitleScale.toString())
            setOptionString("sub-color", prefs.subtitleColor)
            setOptionString("sub-border-size", "2.5")
            // The chosen language under all its codes; else the file's default.
            languages(prefs.subtitleLang)?.let { setOptionString("slang", it) }
            languages(prefs.audioLang)?.let { setOptionString("alang", it) }
            setOptionString("ytdl", "no")
            setOptionString("keep-open", "yes")
            setOptionString("force-window", "no")
            setOptionString("idle", "yes")
            init()
            addObserver(this@MpvPlayer)
            observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("seeking", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
            observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
            observeProperty("aid", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("sid", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("track-list/count", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("chapter-list/count", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("video-params/w", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("hwdec-current", MpvFormat.MPV_FORMAT_STRING)
            observeProperty("chapter", MpvFormat.MPV_FORMAT_INT64)
            observeProperty("sub-delay", MpvFormat.MPV_FORMAT_DOUBLE)
        }
    }

    private fun languages(code: String) =
        code.takeIf { it.isNotEmpty() }?.let { app.veltrio.shared.PlayerPrefs.synonyms(it).joinToString(",") }

    fun load(url: String, start: Double = 0.0) {
        _state.value = PlayerState()
        mpv.setPropertyString("start", if (start > 0) "$start" else "none")
        mpv.command(arrayOf("loadfile", url))
    }

    fun togglePause() = mpv.setPropertyBoolean("pause", !_state.value.paused)
    fun setPaused(paused: Boolean) = mpv.setPropertyBoolean("pause", paused)
    fun seek(to: Double) = mpv.command(arrayOf("seek", "$to", "absolute"))
    fun skip(by: Double) = mpv.command(arrayOf("seek", "$by", "relative"))
    fun setSpeed(speed: Double) = mpv.setPropertyDouble("speed", speed)
    fun selectAudio(id: Int) = mpv.setPropertyString("aid", "$id")
    fun selectSubtitle(id: Int?) = mpv.setPropertyString("sid", id?.toString() ?: "no")
    fun setSubDelay(seconds: Double) = mpv.setPropertyDouble("sub-delay", seconds)

    /** A playback profile, as macOS: video scaling and HDR tone mapping, or
     *  audio passthrough to an amplifier (HDMI on a TV box). */
    fun applyProfile(profile: app.veltrio.shared.PlaybackProfile, toneMapping: String) {
        when (profile) {
            app.veltrio.shared.PlaybackProfile.Quality -> {
                mpv.setPropertyString("scale", "spline36")
                mpv.setPropertyString("cscale", "spline36")
                mpv.setPropertyString("deband", "yes")
            }
            app.veltrio.shared.PlaybackProfile.Performance -> {
                mpv.setPropertyString("scale", "bilinear")
                mpv.setPropertyString("cscale", "bilinear")
                mpv.setPropertyString("deband", "no")
                mpv.setPropertyString("framedrop", "decoder+vo")
            }
            else -> {
                mpv.setPropertyString("scale", "bilinear")
                mpv.setPropertyString("deband", "no")
            }
        }
        // Bitstream DTS / AC3 / E-AC3 to the amplifier; PCM otherwise.
        mpv.setPropertyString("audio-spdif", if (profile == app.veltrio.shared.PlaybackProfile.Audiophile) "ac3,dts,eac3,truehd,dts-hd" else "")
        mpv.setPropertyString("audio-buffer", if (profile == app.veltrio.shared.PlaybackProfile.Audiophile) "0.5" else "0.2")
        mpv.setPropertyString("tone-mapping", toneMapping)
    }

    fun addSubtitle(url: String, title: String) = mpv.command(arrayOf("sub-add", url, "select", title))

    fun setSubtitleAppearance(scale: Float, color: String) {
        if (released) return
        mpv.setPropertyDouble("sub-scale", scale.toDouble())
        mpv.setPropertyString("sub-color", color)
    }

    /** "fit", "fill" (crop to the screen) or "stretch". */
    fun setAspect(mode: String) {
        if (released) return
        mpv.setPropertyDouble("panscan", if (mode == "fill") 1.0 else 0.0)
        mpv.setPropertyString("keepaspect", if (mode == "stretch") "no" else "yes")
    }

    fun release() {
        if (released) return
        released = true
        mpv.removeObserver(this)
        val instance = mpv
        // Detach surface and terminate libmpv off the main thread so slow MediaCodec /
        // network demuxer teardown can never trigger a 5s main-thread ANR.
        Thread({
            runCatching {
                instance.setPropertyString("vo", "null")
                instance.setOptionString("force-window", "no")
                instance.detachSurface()
            }
            runCatching { instance.destroy() }
        }, "mpv-release").start()
    }

    // -------------------- surface --------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        mpv.attachSurface(holder.surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", videoOutput)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!released) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (released) return
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    // -------------------- events (mpv's thread) --------------------

    private inline fun update(change: PlayerState.() -> PlayerState) {
        _state.value = _state.value.change()
    }

    override fun eventProperty(property: String) {
        when (property) {
            "duration" -> update { copy(duration = 0.0) }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "track-list/count" -> readTracks()
            "chapter-list/count" -> readChapters(value.toInt())
            "chapter" -> update { copy(chapter = value.toInt()) }
            "video-params/w" -> update {
                copy(width = value.toInt(), height = mpv.getPropertyInt("video-params/h") ?: 0, videoCodec = mpv.getPropertyString("video-codec"))
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> update { copy(position = value) }
            "duration" -> update { copy(duration = value) }
            "speed" -> update { copy(speed = value) }
            "sub-delay" -> update { copy(subDelay = value) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> update { copy(paused = value) }
            "paused-for-cache", "seeking" -> update { copy(buffering = value || !loaded) }
            "eof-reached" -> update { copy(ended = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "aid" -> update { copy(audioId = value.toIntOrNull()) }
            "sid" -> update { copy(subtitleId = value.toIntOrNull()) }
            "hwdec-current" -> update { copy(hwdec = value) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                update { copy(loaded = true, failed = false) }
                readTracks()
            }
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> update { copy(buffering = false) }
            // Ended before it ever loaded: it couldn't be opened.
            MpvEvent.MPV_EVENT_END_FILE -> if (!_state.value.loaded) update { copy(failed = true, buffering = false) }
        }
    }

    private fun readTracks() {
        if (released) return
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val tracks = (0 until count).mapNotNull { i ->
            val type = mpv.getPropertyString("track-list/$i/type") ?: return@mapNotNull null
            MediaTrack(
                id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null,
                type = type,
                title = mpv.getPropertyString("track-list/$i/title"),
                lang = mpv.getPropertyString("track-list/$i/lang"),
                codec = mpv.getPropertyString("track-list/$i/codec"),
                external = mpv.getPropertyBoolean("track-list/$i/external") == true,
            )
        }
        update { copy(audio = tracks.filter { it.type == "audio" }, subtitles = tracks.filter { it.type == "sub" }) }
    }

    private fun readChapters(count: Int) {
        if (released) return
        val chapters = (0 until count).map { i ->
            Chapter(mpv.getPropertyString("chapter-list/$i/title"), mpv.getPropertyDouble("chapter-list/$i/time") ?: 0.0)
        }
        update { copy(chapters = chapters) }
    }
}
