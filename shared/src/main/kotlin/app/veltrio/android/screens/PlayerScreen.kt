package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.veltrio.android.LocalTv
import app.veltrio.android.Nav
import androidx.compose.foundation.focusable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import app.veltrio.shared.AddonSubtitle
import app.veltrio.shared.ChromecastService
import app.veltrio.shared.Core
import app.veltrio.shared.PlayerPrefs
import app.veltrio.shared.PlaybackProfile
import app.veltrio.shared.Scrobbler
import app.veltrio.shared.SubtitleFetcher
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.WatchStore
import app.veltrio.shared.iptv.EpgStore
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.player.MpvPlayer
import app.veltrio.shared.player.PlayerState
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val speeds = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
private val aspects = listOf("fit" to tr("Adapter"), "fill" to tr("Remplir l'écran"), "stretch" to tr("Étirer"))

private enum class Panel { Channels, Audio, Subtitles, Speed, Aspect, Profile, Cast, Info }

/** The player, as macOS's PlayerView on a phone: full screen in landscape,
 *  the Back pill, title and track menus above, the thin amber seek bar and
 *  transport below; tap to show or hide, double tap a side to skip 10 s.
 *  At the end of an episode, the next one in 10 s, or back to the episodes. */
@Composable
fun PlayerScreen(request: PlayRequest) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val player = remember { MpvPlayer(context) }
    val state by player.state.collectAsState()
    var resolveError by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(true) }
    // The URL being played, for Chromecast (which fetches it itself).
    var resolvedUrl by remember { mutableStateOf<String?>(null) }
    val cast by ChromecastService.state.collectAsState()
    DisposableEffect(Unit) {
        ChromecastService.startDiscovery(context)
        onDispose { ChromecastService.stopDiscovery() }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var aspect by remember { mutableStateOf("fit") }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var endOverlay by remember { mutableStateOf(false) }
    var replay by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(state)

    val scrobbler = remember { Scrobbler(request) }
    fun report(closing: Boolean = false) {
        val s = latest
        if (request.isLive || !s.loaded || s.duration <= 0) return
        WatchStore.reportProgress(request, s.position, s.duration)
        if (closing) scrobbler.stop(s.position, s.duration) else scrobbler.update(s.position, s.duration, s.paused)
    }

    // In the background: paused, progress kept.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                report()
                player.setPaused(true)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            report(closing = true)
            player.release()
            // A torrent streamed for this player only stops with it.
            request.stream?.infoHash?.let { Core.stopTorrent(it) }
        }
    }

    LaunchedEffect(attempt) {
        resolving = true
        resolveError = null
        try {
            val url = request.url ?: Core.resolve(request.stream!!, request.season, request.episode)
            val start = if (request.isLive || request.contentType == null || request.contentId == null) 0.0
            else WatchStore.resumePosition(request.contentType!!, request.contentId!!, request.videoId)
            resolvedUrl = url
            PlayerPrefs.current.let { player.applyProfile(it.profile, it.toneMapping) }
            player.load(url, start)
        } catch (e: Exception) {
            resolveError = Core.humanize(e)
        }
        resolving = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            report()
        }
    }
    // A pause or resume reaches Trakt / SIMKL at once.
    LaunchedEffect(state.paused) { report() }

    // Addon subtitles, as macOS: listed in the menu, and one in the chosen
    // language added when the file has none and the audio isn't in it.
    var addonSubtitles by remember { mutableStateOf<List<AddonSubtitle>>(emptyList()) }
    var addedSubtitles by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.loaded) {
        val type = request.contentType ?: return@LaunchedEffect
        val id = request.videoId ?: request.contentId ?: return@LaunchedEffect
        if (!state.loaded || request.isLive || addonSubtitles.isNotEmpty()) return@LaunchedEffect
        addonSubtitles = SubtitleFetcher.fetch(type, id)
        val wanted = PlayerPrefs.current.subtitleLang.ifEmpty { return@LaunchedEffect }
        val synonyms = PlayerPrefs.synonyms(wanted)
        val s = player.state.value
        val audioLang = s.audio.firstOrNull { it.id == s.audioId }?.lang?.lowercase()
        if (s.subtitles.any { it.lang?.lowercase() in synonyms } || audioLang in synonyms) return@LaunchedEffect
        SubtitleFetcher.pick(addonSubtitles, listOf(wanted))?.let {
            player.addSubtitle(it.url, "${it.language} · ${it.addonName}")
            addedSubtitles = addedSubtitles + it.url
        }
    }

    // Controls fade after a few seconds of playback without a touch.
    LaunchedEffect(controls, touched, state.paused, panel, scrub) {
        if (controls && !state.paused && panel == null && scrub == null) {
            delay(3500)
            controls = false
        }
    }

    LaunchedEffect(state.ended) {
        if (state.ended && !request.isLive && request.videoId != null) {
            report()
            endOverlay = true
        }
    }

    val tv = LocalTv.current
    BackHandler {
        when {
            scrub != null -> scrub = null
            panel != null -> panel = null
            // With a remote, Back first hides the controls, as on macOS Escape
            // first leaves full screen.
            tv && controls -> controls = false
            else -> Nav.back()
        }
    }

    // The remote (and a keyboard): play / pause, ±10 s while the controls are
    // hidden (opening the bar so you can keep scrubbing), any other direction
    // shows them, channel up / down zaps.
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val topBarFocus = remember { FocusRequester() }
    LaunchedEffect(controls, panel, endOverlay) {
        if (panel != null || endOverlay) return@LaunchedEffect
        if (controls && tv) {
            // Wait one frame for AnimatedVisibility to attach BottomBar nodes.
            withFrameNanos {}
            runCatching {
                if (!request.isLive) seekFocus.requestFocus() else playFocus.requestFocus()
            }
        } else if (!controls) {
            runCatching { rootFocus.requestFocus() }
        }
    }
    fun zap(step: Int) {
        val playlist = request.playlist ?: return
        val channel = request.channel ?: return
        val list = IptvStore.loaded(playlist).orEmpty()
        val index = list.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 } ?: return
        list.getOrNull((index + step + list.size) % list.size)?.let { Nav.zap(PlayRequest.forChannel(it, playlist)) }
    }
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        touched++
        if (panel != null || endOverlay) return false
        when (event.key) {
            Key.MediaPlayPause, Key.Spacebar -> { player.togglePause(); return true }
            Key.MediaPlay -> { player.setPaused(false); return true }
            Key.MediaPause -> { player.setPaused(true); return true }
            Key.ChannelUp, Key.PageUp -> { zap(-1); return request.isLive }
            Key.ChannelDown, Key.PageDown -> { zap(1); return request.isLive }
            Key.MediaFastForward -> { if (!request.isLive) { player.skip(30.0); controls = true }; return true }
            Key.MediaRewind -> { if (!request.isLive) { player.skip(-30.0); controls = true }; return true }
        }
        if (controls) return false
        when (event.key) {
            Key.DirectionLeft -> if (!request.isLive) { player.skip(-10.0); controls = true } else zap(-1)
            Key.DirectionRight -> if (!request.isLive) { player.skip(10.0); controls = true } else zap(1)
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp -> controls = true
            Key.DirectionDown -> if (request.isLive && request.playlist != null) panel = Panel.Channels else controls = true
            else -> return false
        }
        return true
    }

    val p = VeltrioTheme.palette
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) touched++; false }
            .focusRequester(rootFocus).focusable().onKeyEvent(::onKey),
    ) {
        AndroidView({ SurfaceView(it).apply { holder.addCallback(player) } }, Modifier.fillMaxSize())

        // Taps: show / hide; a double tap on either third skips 10 s.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = constraints.maxWidth
            Box(
                Modifier.fillMaxSize().pointerInput(request) {
                    detectTapGestures(
                        onTap = {
                            if (panel != null) panel = null else controls = !controls
                            touched++
                        },
                        onDoubleTap = { at ->
                            when {
                                request.isLive -> {}
                                at.x < width / 3f -> player.skip(-10.0)
                                at.x > width * 2 / 3f -> player.skip(10.0)
                                else -> player.togglePause()
                            }
                            touched++
                        },
                    )
                },
            )
        }

        val error = resolveError ?: if (state.failed) tr("Le flux n'a pas pu être lu. Il est peut-être hors ligne, ou dans un format que ce lecteur ne prend pas en charge.") else null
        when {
            error != null -> ErrorState(error, onRetry = { attempt++ }, onBack = { Nav.back() })
            resolving || !state.loaded || state.buffering -> Loading(
                when {
                    resolving && request.stream?.infoHash != null && request.stream?.cachedOn == null -> tr("Connexion aux pairs…")
                    resolving -> tr("Résolution du flux…")
                    !state.loaded -> tr("Ouverture…")
                    else -> tr("Mise en mémoire tampon…")
                },
                request.title,
            )
        }

        AnimatedVisibility(controls && error == null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(request, state, Modifier.align(Alignment.TopCenter), topBarFocus, downFocus = if (!request.isLive) seekFocus else playFocus, onReplay = { replay = true }) {
                    panel = if (panel == it) null else it
                    touched++
                }
                BottomBar(
                    request, state, scrub, Modifier.align(Alignment.BottomCenter),
                    onScrub = { scrub = it; touched++ },
                    onCommit = { player.seek(it); scrub = null; touched++ },
                    onToggle = { player.togglePause(); touched++ },
                    onSkip = { player.skip(it); touched++ },
                    playFocus = playFocus,
                    seekFocus = seekFocus,
                    topBarFocus = topBarFocus,
                )
            }
        }

        panel?.let { current ->
            val panelFocus = remember(current) { FocusRequester() }
            LaunchedEffect(current) {
                delay(60)
                runCatching { panelFocus.requestFocus() }
            }
            SidePanel(
                when (current) {
                    Panel.Channels -> tr("Chaînes")
                    Panel.Audio -> tr("Audio")
                    Panel.Subtitles -> tr("Sous-titres")
                    Panel.Speed -> tr("Vitesse")
                    Panel.Aspect -> tr("Format de l'image")
                    Panel.Profile -> tr("Profil de lecture")
                    Panel.Cast -> tr("Caster")
                    Panel.Info -> tr("Infos média")
                },
                onClose = { panel = null },
                Modifier.align(Alignment.CenterEnd),
                closeModifier = if (current == Panel.Channels) Modifier else Modifier.focusRequester(panelFocus),
            ) {
                when (current) {
                    Panel.Channels -> ChannelList(request, panelFocus, onClose = { panel = null })
                    Panel.Audio -> state.audio.forEach { t ->
                        Option(t.label, t.codec?.uppercase(), t.id == state.audioId) { player.selectAudio(t.id) }
                    }
                    Panel.Subtitles -> {
                        Option(tr("Désactivés"), null, state.subtitleId == null) { player.selectSubtitle(null) }
                        state.subtitles.forEach { t ->
                            Option(t.label, if (t.external) tr("EXTERNE") else t.codec?.uppercase(), t.id == state.subtitleId) { player.selectSubtitle(t.id) }
                        }
                        addonSubtitles.filter { it.url !in addedSubtitles }.forEach { sub ->
                            Option(sub.language, sub.addonName.uppercase(), false) {
                                player.addSubtitle(sub.url, "${sub.language} · ${sub.addonName}")
                                addedSubtitles = addedSubtitles + sub.url
                            }
                        }
                        // Out of sync: shift them, as macOS's Z / X.
                        Text(tr("DÉCALAGE"), style = Type.eyebrow(9, 0.18f), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(start = 20.dp, top = 14.dp))
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("−0,1 s", Icons.Filled.Remove, primary = false) { player.setSubDelay(state.subDelay - 0.1) }
                            Text("%+.1f s".format(state.subDelay), style = Type.mono(13, FontWeight.Bold), color = Color.White)
                            Pill("+0,1 s", Icons.Filled.Add, primary = false) { player.setSubDelay(state.subDelay + 0.1) }
                        }
                        if (kotlin.math.abs(state.subDelay) > 0.001) Option(tr("Remettre à zéro"), null, false) { player.setSubDelay(0.0) }
                    }
                    Panel.Speed -> speeds.forEach { s ->
                        Option(if (s == 1.0) tr("Normale") else "×${s.toString().trimEnd('0').trimEnd('.')}", null, kotlin.math.abs(state.speed - s) < 0.01) { player.setSpeed(s) }
                    }
                    Panel.Profile -> {
                        var prefs by remember { mutableStateOf(PlayerPrefs.current) }
                        PlaybackProfile.entries.forEach { pr ->
                            Option(pr.label, null, pr == prefs.profile) {
                                PlayerPrefs.update { it.copy(profile = pr) }
                                prefs = PlayerPrefs.current
                                player.applyProfile(pr, prefs.toneMapping)
                            }
                        }
                        Text(prefs.profile.blurb, style = Type.caption(11), color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        Text(tr("TONE MAPPING HDR"), style = Type.eyebrow(9, 0.18f), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                        PlayerPrefs.toneMappings.forEach { (key, label) ->
                            Option(label, null, key == prefs.toneMapping) {
                                PlayerPrefs.update { it.copy(toneMapping = key) }
                                prefs = PlayerPrefs.current
                                player.applyProfile(prefs.profile, key)
                            }
                        }
                    }
                    Panel.Aspect -> aspects.forEach { (key, label) ->
                        Option(label, null, key == aspect) { aspect = key; player.setAspect(key) }
                    }
                    Panel.Cast -> CastList(resolvedUrl, cast) { device ->
                        val s = player.state.value
                        player.setPaused(true)
                        ChromecastService.cast(device, resolvedUrl!!, request.title, request.poster ?: request.background, request.isLive, s.position)
                        panel = null
                    }
                    Panel.Info -> {
                        InfoLine(tr("Résolution"), if (state.width > 0) "${state.width} × ${state.height}" else "—")
                        InfoLine(tr("Vidéo"), state.videoCodec ?: "—")
                        InfoLine(tr("Décodage"), state.hwdec?.takeIf { it != "no" && it.isNotEmpty() }?.let { tr("Matériel (%s)", it) } ?: tr("Logiciel"))
                        InfoLine(tr("Pistes audio"), "${state.audio.size}")
                        InfoLine(tr("Sous-titres"), "${state.subtitles.size}")
                        request.stream?.let { InfoLine(tr("Source"), it.addonName) }
                    }
                }
            }
        }

        if (cast.state != ChromecastService.CastState.Idle) {
            CastOverlay(cast) {
                ChromecastService.stop()
            }
        }

        // In an intro chapter: "Passer l'intro", as macOS.
        state.introEnd?.let { end ->
            if (!endOverlay && panel == null && cast.state == ChromecastService.CastState.Idle) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 110.dp)) {
                    Pill(tr("Passer l'intro"), Icons.Filled.SkipNext, primary = true) { player.seek(end) }
                }
            }
        }

        if (replay && request.channel != null && request.playlist != null) {
            CatchupDialog(request.playlist!!, request.channel!!, onPlay = { Nav.zap(it) }) { replay = false }
        }

        if (endOverlay) {
            EndOfEpisode(
                request,
                onStay = { endOverlay = false },
                onEpisodes = { Nav.back() },
                onNext = { next -> Nav.zap(next) },
            )
        }
    }
}

@Composable
private fun TopBar(
    request: PlayRequest,
    state: PlayerState,
    modifier: Modifier,
    backFocus: FocusRequester,
    downFocus: FocusRequester,
    onReplay: () -> Unit,
    onPanel: (Panel) -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
            .safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp)
            .focusProperties { down = downFocus },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .focusRequester(backFocus)
                .clickable { Nav.back() }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.ChevronLeft, null, Modifier.size(18.dp), tint = Color.White)
            Text(tr("Retour"), style = Type.label(13, FontWeight.SemiBold), color = Color.White)
        }
        if (request.isLive) LiveBadge()
        Column(Modifier.weight(1f)) {
            Text(request.title, style = Type.label(14, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val onNow = request.channel?.takeIf { request.isLive }?.let { EpgStore.current(it) }
            (onNow?.let { "${it.timeRange} · ${it.title}" } ?: request.subtitle)?.let {
                Text(it, style = Type.label(11, FontWeight.Medium), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (request.channel != null && request.playlist != null && request.isLive) GlassIcon(Icons.AutoMirrored.Filled.List, tr("Chaînes")) { onPanel(Panel.Channels) }
        if (state.audio.size > 1) GlassIcon(Icons.Filled.Audiotrack, tr("Audio")) { onPanel(Panel.Audio) }
        GlassIcon(Icons.Filled.Subtitles, tr("Sous-titres")) { onPanel(Panel.Subtitles) }
        if (!request.isLive) GlassIcon(Icons.Filled.Speed, tr("Vitesse")) { onPanel(Panel.Speed) }
        GlassIcon(Icons.Filled.AspectRatio, tr("Format")) { onPanel(Panel.Aspect) }
        GlassIcon(Icons.Filled.Tune, tr("Profil de lecture")) { onPanel(Panel.Profile) }
        if (request.isLive && request.channel?.hasCatchup == true && request.playlist?.xtream != null) GlassIcon(Icons.Filled.History, tr("Revoir")) { onReplay() }
        GlassIcon(Icons.Filled.Cast, tr("Caster")) { onPanel(Panel.Cast) }
        GlassIcon(Icons.Outlined.Info, tr("Infos média")) { onPanel(Panel.Info) }
    }
}

@Composable
private fun GlassIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(18.dp), tint = Color.White) }
}

@Composable
private fun LiveBadge() {
    val p = VeltrioTheme.palette
    Row(
        Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(p.danger))
        Text(tr("DIRECT"), style = Type.label(11, FontWeight.Black), color = Color.White)
    }
}

@Composable
private fun BottomBar(
    request: PlayRequest, state: PlayerState, scrub: Double?, modifier: Modifier,
    onScrub: (Double) -> Unit, onCommit: (Double) -> Unit, onToggle: () -> Unit, onSkip: (Double) -> Unit,
    playFocus: FocusRequester,
    seekFocus: FocusRequester,
    topBarFocus: FocusRequester,
) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val shown = scrub ?: state.position
        if (!request.isLive) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(format(shown), style = Type.mono(11, FontWeight.Bold), color = Color.White, modifier = Modifier.width(58.dp))
                SeekBar(
                    shown, state.duration, state.chapters.map { it.start },
                    Modifier.weight(1f).focusProperties { up = topBarFocus; down = playFocus },
                    seekFocus, onScrub, onCommit, onToggle,
                )
                Text("-" + format((state.duration - shown).coerceAtLeast(0.0)), style = Type.mono(11, FontWeight.Bold), color = Color.White.copy(alpha = 0.75f), modifier = Modifier.width(58.dp), textAlign = TextAlign.End)
            }
        }
        Row(
            Modifier.focusProperties { up = if (!request.isLive) seekFocus else topBarFocus },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!request.isLive) ControlButton(Icons.Filled.Replay10, tr("Reculer de 10 s")) { onSkip(-10.0) }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).focusRequester(playFocus).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) tr("Lire") else tr("Pause"), Modifier.size(26.dp), tint = Color.Black)
            }
            if (!request.isLive) ControlButton(Icons.Filled.Forward10, tr("Avancer de 10 s")) { onSkip(10.0) }
            Spacer(Modifier.weight(1f))
            if (request.isLive) Text(if (state.buffering) tr("Mise en mémoire tampon…") else tr("En direct"), style = Type.label(12, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f))
            else request.nextLabel?.let { Text(tr("Ensuite · %s", it), style = Type.label(11, FontWeight.Medium), color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(26.dp), tint = Color.White)
    }
}

/** The macOS slider: a 4 dp rail that thickens with a knob while dragged or
 *  focused on TV, the played part in amber, chapter marks in the rail. On a
 *  remote or keyboard, Left/Right scrub progressively and commit on release. */
@Composable
private fun SeekBar(
    value: Double,
    duration: Double,
    chapters: List<Double>,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onScrub: (Double) -> Unit,
    onCommit: (Double) -> Unit,
    onToggle: () -> Unit,
) {
    val p = VeltrioTheme.palette
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableDoubleStateOf(0.0) }
    var focused by remember { mutableStateOf(false) }
    var keyScrubbing by remember { mutableStateOf(false) }
    var keyTarget by remember { mutableDoubleStateOf(0.0) }
    val max = duration.coerceAtLeast(0.000001)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(32.dp)
            .clip(shape)
            .then(
                if (focused) Modifier.background(p.accent.copy(alpha = 0.14f)).border(2.dp, p.accent, shape)
                else Modifier,
            )
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                val wasFocused = focused
                focused = state.isFocused
                if (wasFocused && !focused && keyScrubbing) {
                    keyScrubbing = false
                    onCommit(keyTarget)
                }
            }
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (duration <= 0) return@onKeyEvent true
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                val repeat = event.nativeKeyEvent.repeatCount
                                val step = when {
                                    repeat >= 15 -> maxOf(60.0, duration / 40.0).coerceAtMost(120.0)
                                    repeat >= 5 -> 30.0
                                    else -> 10.0
                                }
                                val base = if (keyScrubbing) keyTarget else value
                                val delta = if (event.key == Key.DirectionLeft) -step else step
                                val next = (base + delta).coerceIn(0.0, duration)
                                keyScrubbing = true
                                keyTarget = next
                                onScrub(next)
                                true
                            }
                            KeyEventType.KeyUp -> {
                                if (keyScrubbing) {
                                    keyScrubbing = false
                                    onCommit(keyTarget)
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            if (keyScrubbing) {
                                keyScrubbing = false
                                onCommit(keyTarget)
                            } else {
                                onToggle()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .then(
                    if (duration <= 0) Modifier
                    else Modifier.pointerInput(duration) {
                        detectTapGestures { onCommit((it.x / size.width).coerceIn(0f, 1f) * max) }
                    }.pointerInputDrag(max, onStart = { dragging = true; dragValue = it; onScrub(it) }, onMove = { dragValue = it; onScrub(it) }, onEnd = { dragging = false; onCommit(dragValue) }),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            val active = dragging || focused
            val fraction = (value / max).coerceIn(0.0, 1.0).toFloat()
            val rail = if (active) 8.dp else 4.dp
            val knobSize = if (focused) 16.dp else 14.dp
            Box(Modifier.fillMaxWidth().height(rail).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)))
            Box(Modifier.fillMaxWidth(fraction).height(rail).clip(RoundedCornerShape(50)).background(p.accent))
            chapters.filter { it > 0 && it < max }.forEach { start ->
                Box(Modifier.offset(x = maxWidth * (start / max).toFloat() - 1.dp).width(2.dp).height(rail + 4.dp).background(Color.White.copy(alpha = 0.65f)))
            }
            if (active) {
                val knobX = (maxWidth * fraction - knobSize / 2).coerceIn(0.dp, (maxWidth - knobSize).coerceAtLeast(0.dp))
                Box(
                    Modifier.offset(x = knobX).size(knobSize).clip(CircleShape).background(Color.White)
                        .then(if (focused) Modifier.border(2.dp, p.accent, CircleShape) else Modifier),
                )
            }
        }
    }
}

private fun Modifier.pointerInputDrag(max: Double, onStart: (Double) -> Unit, onMove: (Double) -> Unit, onEnd: () -> Unit) =
    this.then(
        Modifier.pointerInput(max) {
            detectHorizontalDragGestures(
                onDragStart = { onStart((it.x / size.width).coerceIn(0f, 1f) * max) },
                onDragEnd = onEnd, onDragCancel = onEnd,
            ) { change, _ -> onMove((change.position.x / size.width).coerceIn(0f, 1f) * max) }
        },
    )

@Composable
private fun SidePanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    closeModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = VeltrioTheme.palette
    Column(
        modifier.fillMaxHeight().width(320.dp).background(Color(0xF0101010)).safeDrawingPadding().padding(vertical = 18.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = Type.eyebrow(10, 0.24f), color = p.accentText, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Close, tr("Fermer"), closeModifier.size(22.dp).clickable(onClick = onClose), tint = Color.White)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn { item { Column { content() } } }
    }
}

@Composable
private fun Option(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val p = VeltrioTheme.palette
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(18.dp)) { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = p.accent) }
        Text(label, style = Type.label(14, if (selected) FontWeight.SemiBold else FontWeight.Normal), color = Color.White, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = Type.mono(10), color = Color.White.copy(alpha = 0.5f)) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label.uppercase(), style = Type.eyebrow(9, 0.16f), color = Color.White.copy(alpha = 0.5f), modifier = Modifier.width(110.dp))
        Text(value, style = Type.mono(12), color = Color.White)
    }
}

@Composable
private fun Loading(status: String, title: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Text(status, style = Type.label(13, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f))
        Text(title, style = Type.label(12), color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val p = VeltrioTheme.palette
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(42.dp), tint = Color(0xFFFFD60A))
        Text(tr("Impossible d'ouvrir ce flux"), style = Type.title(18), color = Color.White)
        Text(message, style = Type.label(12), color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FrameButton(tr("Réessayer"), Icons.Filled.Refresh, p.accent, filled = true, onClick = onRetry)
            FrameButton(tr("Retour"), null, Color.White, onClick = onBack)
        }
    }
}

@Composable
private fun FrameButton(label: String, icon: ImageVector?, color: Color, filled: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.background(if (filled) color else Color.Transparent).border(1.dp, color).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (filled) Color.Black else color
        icon?.let { Icon(it, null, Modifier.size(15.dp), tint = fg) }
        Text(label.uppercase(), style = Type.eyebrow(11, 0.16f), color = fg)
    }
}

/** "Épisode terminé": the next episode in 10 s, or back to the episodes
 *  after the last one; "Rester" keeps the player on the last frame. */
@Composable
private fun EndOfEpisode(request: PlayRequest, onStay: () -> Unit, onEpisodes: () -> Unit, onNext: (PlayRequest) -> Unit) {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    var countdown by remember { mutableIntStateOf(10) }
    var finding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasNext = request.next != null

    fun playNext() {
        if (finding) return
        finding = true
        scope.launch {
            val next = try {
                request.next?.invoke()
            } catch (e: Exception) {
                error = Core.humanize(e); null
            }
            if (next != null) onNext(next) else {
                error = error ?: tr("Aucun flux trouvé pour l'épisode suivant.")
                finding = false
            }
        }
    }

    // With "Enchaîner l'épisode suivant" off, nothing happens on its own.
    val auto = PlayerPrefs.current.autoNextEpisode
    LaunchedEffect(Unit) {
        if (!auto) return@LaunchedEffect
        repeat(10) {
            delay(1000)
            countdown--
        }
        if (hasNext) playNext() else onEpisodes()
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(enabled = false) {},
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(52.dp), tint = p.accent)
        Text(tr("Épisode terminé"), style = Type.displaySerif(28), color = Color.White)
        request.nextLabel?.let { Text(it, style = Type.label(14, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center) }
        Text(
            error ?: when {
                finding -> tr("Recherche du flux…")
                !auto -> ""
                hasNext -> tr("Épisode suivant dans %s s", countdown.coerceAtLeast(0))
                else -> tr("Retour aux épisodes dans %s s", countdown.coerceAtLeast(0))
            },
            style = Type.label(13, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            Pill(tr("Rester"), Icons.Filled.Stop, primary = false, onClick = onStay)
            Pill(tr("Épisodes"), Icons.AutoMirrored.Filled.List, primary = !hasNext, onClick = onEpisodes)
            if (hasNext) Pill(tr("Épisode suivant"), Icons.Filled.SkipNext, primary = true, enabled = !finding, onClick = ::playNext)
        }
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (primary) Color.White else Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = if (primary) 0f else 0.18f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = if (primary) Color.Black else Color.White
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Text(label, style = Type.label(14, FontWeight.Bold), color = fg)
    }
}

private fun format(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Live TV: the bouquet picker, set to the current channel's, and its
 *  channels with the one playing marked; a tap zaps. */
@Composable
private fun ChannelList(request: PlayRequest, listFocus: FocusRequester, onClose: () -> Unit) {
    val p = VeltrioTheme.palette
    val playlist = request.playlist ?: return
    val playing = request.channel ?: return
    var channels by remember { mutableStateOf(IptvStore.loaded(playlist).orEmpty()) }
    LaunchedEffect(Unit) { if (channels.isEmpty()) channels = runCatching { IptvStore.items(playlist) }.getOrDefault(emptyList()) }
    val groups = remember(channels) { channels.map { it.group }.distinct() }
    var group by remember { mutableStateOf(playing.group) }
    var open by remember { mutableStateOf(false) }
    val filtered = remember(channels, group) { channels.filter { it.group == group } }
    val focusTargetId = remember(filtered, playing.id) {
        filtered.firstOrNull { it.id == playing.id }?.id ?: filtered.firstOrNull()?.id
    }
    LaunchedEffect(focusTargetId) {
        if (focusTargetId != null) {
            delay(50)
            runCatching { listFocus.requestFocus() }
        }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(tr("BOUQUET"), style = Type.eyebrow(9, 0.18f), color = Color.White.copy(alpha = 0.5f))
        Box {
            Row(
                Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.25f)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(group.ifEmpty { tr("Sans groupe") }, style = Type.label(13, FontWeight.SemiBold), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
            }
            DropdownMenu(open, { open = false }) {
                groups.forEach { g ->
                    DropdownMenuItem({ Text("$g  (${channels.count { it.group == g }})", fontWeight = if (g == group) FontWeight.Bold else FontWeight.Normal) }, {
                        group = g
                        open = false
                    })
                }
            }
        }
        Text(tr("EN COURS · %s %s", playing.numberLabel, playing.name).trim(), style = Type.mono(10, FontWeight.SemiBold), color = p.accent, modifier = Modifier.padding(top = 6.dp))
    }
    filtered.forEach { c ->
        val current = c.id == playing.id
        val focusMod = if (c.id == focusTargetId) Modifier.focusRequester(listFocus) else Modifier
        Row(
            focusMod.fillMaxWidth().background(if (current) p.accent.copy(alpha = 0.18f) else Color.Transparent)
                .clickable {
                    if (current) onClose() else Nav.zap(PlayRequest.forChannel(c, playlist))
                }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(c.numberLabel, style = Type.mono(10), color = if (current) p.accent else Color.White.copy(alpha = 0.5f), modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = Type.label(13, if (current) FontWeight.Bold else FontWeight.Normal), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                EpgStore.current(c)?.let { Text(it.title, style = Type.caption(11), color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (current) Icon(Icons.Filled.PlayArrow, tr("En cours"), Modifier.size(14.dp), tint = p.accent)
        }
    }
}

/** Chromecasts on the network; the local torrent server can't be cast. */
@Composable
private fun CastList(url: String?, state: ChromecastService.State, onPick: (ChromecastService.Device) -> Unit) {
    val devices by ChromecastService.devices.collectAsState()
    val p = VeltrioTheme.palette
    when {
        !ChromecastService.canCast(url) -> Text(
            tr("Ce flux est lu depuis cet appareil (torrent) et ne peut pas être envoyé à un Chromecast. Un service debrid le rend castable."),
            style = Type.label(13), color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(20.dp),
        )
        devices.isEmpty() -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(tr("Recherche de Chromecasts sur le réseau…"), style = Type.label(13), color = Color.White.copy(alpha = 0.75f))
        }
        else -> devices.forEach { d ->
            Option(d.name, d.host, state.deviceName == d.name && state.isCasting) { onPick(d) }
        }
    }
    state.error?.let { Text(it, style = Type.caption(12), color = p.danger, modifier = Modifier.padding(horizontal = 20.dp)) }
}

@Composable
private fun CastOverlay(state: ChromecastService.State, onStop: () -> Unit) {
    val p = VeltrioTheme.palette
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.Cast, null, Modifier.size(46.dp), tint = p.accent)
        Text(
            when (state.state) {
                ChromecastService.CastState.Connecting -> tr("Connexion à %s…", state.deviceName)
                ChromecastService.CastState.Casting -> tr("Lecture sur %s", state.deviceName)
                else -> state.error ?: tr("Échec du cast")
            },
            style = Type.displaySerif(24), color = Color.White, textAlign = TextAlign.Center,
        )
        Pill(if (state.state == ChromecastService.CastState.Failed) tr("Fermer") else tr("Arrêter"), Icons.Filled.Stop, primary = true, onClick = onStop)
    }
}
