package app.veltrio.android.screens

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
import app.veltrio.android.Nav
import app.veltrio.shared.AddonSubtitle
import app.veltrio.shared.Core
import app.veltrio.shared.PlayerPrefs
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
private val aspects = listOf("fit" to "Adapter", "fill" to "Remplir l'écran", "stretch" to "Étirer")

private enum class Panel { Channels, Audio, Subtitles, Speed, Aspect, Info }

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
    var attempt by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var aspect by remember { mutableStateOf("fit") }
    var scrub by remember { mutableStateOf<Double?>(null) }
    var endOverlay by remember { mutableStateOf(false) }
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
    LaunchedEffect(controls, touched, state.paused, panel) {
        if (controls && !state.paused && panel == null) {
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

    BackHandler {
        when {
            panel != null -> panel = null
            else -> Nav.back()
        }
    }

    val p = VeltrioTheme.palette
    Box(Modifier.fillMaxSize().background(Color.Black)) {
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

        val error = resolveError ?: if (state.failed) "Le flux n'a pas pu être lu. Il est peut-être hors ligne, ou dans un format que ce lecteur ne prend pas en charge." else null
        when {
            error != null -> ErrorState(error, onRetry = { attempt++ }, onBack = { Nav.back() })
            resolving || !state.loaded || state.buffering -> Loading(
                when {
                    resolving && request.stream?.infoHash != null && request.stream?.cachedOn == null -> "Connexion aux pairs…"
                    resolving -> "Résolution du flux…"
                    !state.loaded -> "Ouverture…"
                    else -> "Mise en mémoire tampon…"
                },
                request.title,
            )
        }

        AnimatedVisibility(controls && error == null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(request, state, Modifier.align(Alignment.TopCenter)) {
                    panel = if (panel == it) null else it
                    touched++
                }
                BottomBar(
                    request, state, scrub, Modifier.align(Alignment.BottomCenter),
                    onScrub = { scrub = it; touched++ },
                    onCommit = { player.seek(it); scrub = null; touched++ },
                    onToggle = { player.togglePause(); touched++ },
                    onSkip = { player.skip(it); touched++ },
                )
            }
        }

        panel?.let { current ->
            SidePanel(
                when (current) {
                    Panel.Channels -> "Chaînes"
                    Panel.Audio -> "Audio"
                    Panel.Subtitles -> "Sous-titres"
                    Panel.Speed -> "Vitesse"
                    Panel.Aspect -> "Format de l'image"
                    Panel.Info -> "Infos média"
                },
                onClose = { panel = null },
                Modifier.align(Alignment.CenterEnd),
            ) {
                when (current) {
                    Panel.Channels -> ChannelList(request)
                    Panel.Audio -> state.audio.forEach { t ->
                        Option(t.label, t.codec?.uppercase(), t.id == state.audioId) { player.selectAudio(t.id) }
                    }
                    Panel.Subtitles -> {
                        Option("Désactivés", null, state.subtitleId == null) { player.selectSubtitle(null) }
                        state.subtitles.forEach { t ->
                            Option(t.label, if (t.external) "EXTERNE" else t.codec?.uppercase(), t.id == state.subtitleId) { player.selectSubtitle(t.id) }
                        }
                        addonSubtitles.filter { it.url !in addedSubtitles }.forEach { sub ->
                            Option(sub.language, sub.addonName.uppercase(), false) {
                                player.addSubtitle(sub.url, "${sub.language} · ${sub.addonName}")
                                addedSubtitles = addedSubtitles + sub.url
                            }
                        }
                    }
                    Panel.Speed -> speeds.forEach { s ->
                        Option(if (s == 1.0) "Normale" else "×${s.toString().trimEnd('0').trimEnd('.')}", null, kotlin.math.abs(state.speed - s) < 0.01) { player.setSpeed(s) }
                    }
                    Panel.Aspect -> aspects.forEach { (key, label) ->
                        Option(label, null, key == aspect) { aspect = key; player.setAspect(key) }
                    }
                    Panel.Info -> {
                        InfoLine("Résolution", if (state.width > 0) "${state.width} × ${state.height}" else "—")
                        InfoLine("Vidéo", state.videoCodec ?: "—")
                        InfoLine("Décodage", state.hwdec?.takeIf { it != "no" && it.isNotEmpty() }?.let { "Matériel ($it)" } ?: "Logiciel")
                        InfoLine("Pistes audio", "${state.audio.size}")
                        InfoLine("Sous-titres", "${state.subtitles.size}")
                        request.stream?.let { InfoLine("Source", it.addonName) }
                    }
                }
            }
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
private fun TopBar(request: PlayRequest, state: PlayerState, modifier: Modifier, onPanel: (Panel) -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
            .safeDrawingPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .clickable { Nav.back() }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.ChevronLeft, null, Modifier.size(18.dp), tint = Color.White)
            Text("Retour", style = Type.label(13, FontWeight.SemiBold), color = Color.White)
        }
        if (request.isLive) LiveBadge()
        Column(Modifier.weight(1f)) {
            Text(request.title, style = Type.label(14, FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val onNow = request.channel?.takeIf { request.isLive }?.let { EpgStore.current(it) }
            (onNow?.let { "${it.timeRange} · ${it.title}" } ?: request.subtitle)?.let {
                Text(it, style = Type.label(11, FontWeight.Medium), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (request.channel != null && request.playlist != null && request.isLive) GlassIcon(Icons.AutoMirrored.Filled.List, "Chaînes") { onPanel(Panel.Channels) }
        if (state.audio.size > 1) GlassIcon(Icons.Filled.Audiotrack, "Audio") { onPanel(Panel.Audio) }
        GlassIcon(Icons.Filled.Subtitles, "Sous-titres") { onPanel(Panel.Subtitles) }
        if (!request.isLive) GlassIcon(Icons.Filled.Speed, "Vitesse") { onPanel(Panel.Speed) }
        GlassIcon(Icons.Filled.AspectRatio, "Format") { onPanel(Panel.Aspect) }
        GlassIcon(Icons.Outlined.Info, "Infos média") { onPanel(Panel.Info) }
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
        Text("DIRECT", style = Type.label(11, FontWeight.Black), color = Color.White)
    }
}

@Composable
private fun BottomBar(
    request: PlayRequest, state: PlayerState, scrub: Double?, modifier: Modifier,
    onScrub: (Double) -> Unit, onCommit: (Double) -> Unit, onToggle: () -> Unit, onSkip: (Double) -> Unit,
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
                SeekBar(shown, state.duration, state.chapters.map { it.start }, Modifier.weight(1f), onScrub, onCommit)
                Text("-" + format((state.duration - shown).coerceAtLeast(0.0)), style = Type.mono(11, FontWeight.Bold), color = Color.White.copy(alpha = 0.75f), modifier = Modifier.width(58.dp), textAlign = TextAlign.End)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!request.isLive) ControlButton(Icons.Filled.Replay10, "Reculer de 10 s") { onSkip(-10.0) }
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (state.paused) "Lecture" else "Pause", Modifier.size(26.dp), tint = Color.Black)
            }
            if (!request.isLive) ControlButton(Icons.Filled.Forward10, "Avancer de 10 s") { onSkip(10.0) }
            Spacer(Modifier.weight(1f))
            if (request.isLive) Text(if (state.buffering) "Mise en mémoire tampon…" else "En direct", style = Type.label(12, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f))
            else request.nextLabel?.let { Text("Ensuite · $it", style = Type.label(11, FontWeight.Medium), color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(26.dp), tint = Color.White)
    }
}

/** The macOS slider: a 4 dp rail that thickens with a knob while dragged,
 *  the played part in amber, chapter marks in the rail. */
@Composable
private fun SeekBar(value: Double, duration: Double, chapters: List<Double>, modifier: Modifier, onScrub: (Double) -> Unit, onCommit: (Double) -> Unit) {
    val p = VeltrioTheme.palette
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableDoubleStateOf(0.0) }
    val max = duration.coerceAtLeast(0.000001)
    BoxWithConstraints(
        modifier.height(28.dp)
            .then(
                if (duration <= 0) Modifier
                else Modifier.pointerInput(duration) {
                    detectTapGestures { onCommit((it.x / size.width).coerceIn(0f, 1f) * max) }
                }.pointerInputDrag(max, onStart = { dragging = true; dragValue = it; onScrub(it) }, onMove = { dragValue = it; onScrub(it) }, onEnd = { dragging = false; onCommit(dragValue) }),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        val fraction = (value / max).coerceIn(0.0, 1.0).toFloat()
        val rail = if (dragging) 8.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(rail).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)))
        Box(Modifier.fillMaxWidth(fraction).height(rail).clip(RoundedCornerShape(50)).background(p.accent))
        chapters.filter { it > 0 && it < max }.forEach { start ->
            Box(Modifier.offset(x = maxWidth * (start / max).toFloat() - 1.dp).width(2.dp).height(rail + 4.dp).background(Color.White.copy(alpha = 0.65f)))
        }
        if (dragging) Box(Modifier.offset(x = maxWidth * fraction - 7.dp).size(14.dp).clip(CircleShape).background(Color.White))
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
private fun SidePanel(title: String, onClose: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val p = VeltrioTheme.palette
    Column(
        modifier.fillMaxHeight().width(320.dp).background(Color(0xF0101010)).safeDrawingPadding().padding(vertical = 18.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = Type.eyebrow(10, 0.24f), color = p.accentText, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Close, "Fermer", Modifier.size(22.dp).clickable(onClick = onClose), tint = Color.White)
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
        Text("Impossible d'ouvrir ce flux", style = Type.title(18), color = Color.White)
        Text(message, style = Type.label(12), color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FrameButton("Réessayer", Icons.Filled.Refresh, p.accent, filled = true, onClick = onRetry)
            FrameButton("Retour", null, Color.White, onClick = onBack)
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
                error = error ?: "Aucun flux trouvé pour l'épisode suivant."
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
        Text("Épisode terminé", style = Type.displaySerif(28), color = Color.White)
        request.nextLabel?.let { Text(it, style = Type.label(14, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center) }
        Text(
            error ?: when {
                finding -> "Recherche du flux…"
                !auto -> ""
                hasNext -> "Épisode suivant dans ${countdown.coerceAtLeast(0)} s"
                else -> "Retour aux épisodes dans ${countdown.coerceAtLeast(0)} s"
            },
            style = Type.label(13, FontWeight.SemiBold), color = Color.White.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            Pill("Rester", Icons.Filled.Stop, primary = false, onClick = onStay)
            Pill("Épisodes", Icons.AutoMirrored.Filled.List, primary = !hasNext, onClick = onEpisodes)
            if (hasNext) Pill("Épisode suivant", Icons.Filled.SkipNext, primary = true, enabled = !finding, onClick = ::playNext)
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
private fun ChannelList(request: PlayRequest) {
    val p = VeltrioTheme.palette
    val playlist = request.playlist ?: return
    val playing = request.channel ?: return
    var channels by remember { mutableStateOf(IptvStore.loaded(playlist).orEmpty()) }
    LaunchedEffect(Unit) { if (channels.isEmpty()) channels = runCatching { IptvStore.items(playlist) }.getOrDefault(emptyList()) }
    val groups = remember(channels) { channels.map { it.group }.distinct() }
    var group by remember { mutableStateOf(playing.group) }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("BOUQUET", style = Type.eyebrow(9, 0.18f), color = Color.White.copy(alpha = 0.5f))
        Box {
            Row(
                Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.25f)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(group.ifEmpty { "Sans groupe" }, style = Type.label(13, FontWeight.SemiBold), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text("EN COURS · ${playing.numberLabel} ${playing.name}".trim(), style = Type.mono(10, FontWeight.SemiBold), color = p.accent, modifier = Modifier.padding(top = 6.dp))
    }
    channels.filter { it.group == group }.forEach { c ->
        val current = c.id == playing.id
        Row(
            Modifier.fillMaxWidth().background(if (current) p.accent.copy(alpha = 0.18f) else Color.Transparent)
                .clickable(enabled = !current) { Nav.zap(PlayRequest.forChannel(c, playlist)) }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(c.numberLabel, style = Type.mono(10), color = if (current) p.accent else Color.White.copy(alpha = 0.5f), modifier = Modifier.width(30.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, style = Type.label(13, if (current) FontWeight.Bold else FontWeight.Normal), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                EpgStore.current(c)?.let { Text(it.title, style = Type.caption(11), color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (current) Icon(Icons.Filled.PlayArrow, "En cours", Modifier.size(14.dp), tint = p.accent)
        }
    }
}
