package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import app.veltrio.android.LocalTv
import app.veltrio.android.Nav
import app.veltrio.shared.PlayerPrefs
import app.veltrio.shared.player.MpvPlayer
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.LoadingRow
import app.veltrio.android.ui.PosterCard
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.shared.Core
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.iptv.ChannelItem
import app.veltrio.shared.iptv.ChannelKind
import app.veltrio.shared.iptv.EpgStore
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.iptv.PlaylistRef
import app.veltrio.shared.iptv.XtreamClient
import app.veltrio.shared.iptv.XtreamProgramme
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun Instant.hm() = hm.format(atZone(ZoneId.systemDefault()))

/** Direct, as macOS's LiveView on a phone: the source and its modes (live,
 *  films, series for Xtream), list or TV guide, a bouquet picker and a
 *  search; the selected channel's card with what's on now and next, Watch
 *  and Replay; films and series as posters. */
@Composable
fun LiveScreen() {
    val p = VeltrioTheme.palette
    val playlists by IptvStore.playlists.collectAsState()
    val selectedId by IptvStore.selectedId.collectAsState()
    val playlist = playlists.firstOrNull { it.id == selectedId } ?: playlists.firstOrNull()
    var adding by remember { mutableStateOf(false) }
    if (adding) AddPlaylistDialog { adding = false }
    if (playlist == null) {
        Column(Modifier.fillMaxSize().padding(Spacing.lg).padding(top = 60.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Eyebrow(tr("Direct"))
            Text(tr("Silence radio."), style = Type.displaySerif(34), color = p.textPrimary)
            Text(tr("Connectez un fournisseur Xtream Codes ou collez l'URL d'une playlist M3U. Les identifiants ne quittent jamais cet appareil."), style = Type.body(14), color = p.textSecondary)
            PrimaryButton(tr("Ajouter une source"), icon = Icons.Filled.Add) { adding = true }
        }
        return
    }
    PlaylistView(playlist, playlists) { adding = true }
}

@Composable
private fun PlaylistView(playlist: PlaylistRef, playlists: List<PlaylistRef>, onAdd: () -> Unit) {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable(playlist.id) { mutableStateOf(ChannelKind.Live) }
    var guide by rememberSaveable { mutableStateOf(false) }
    var items by remember(playlist.id, kind) { mutableStateOf(IptvStore.loaded(playlist, kind)) }
    var error by remember(playlist.id, kind) { mutableStateOf<String?>(null) }
    var group by rememberSaveable(playlist.id, kind) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(playlist.id, kind) { mutableStateOf("") }
    var selected by remember(playlist.id) { mutableStateOf<ChannelItem?>(null) }
    var reload by remember { mutableStateOf(0) }
    val guideVersion by EpgStore.version.collectAsState()
    // Programmes move on: the "now" lines refresh every minute.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }

    LaunchedEffect(playlist.id, kind, reload) {
        error = null
        try {
            val loaded = IptvStore.items(playlist, kind, refresh = reload > 0)
            items = loaded
            if (kind == ChannelKind.Live) EpgStore.load(playlist, loaded, force = reload > 0)
        } catch (e: Exception) {
            items = emptyList()
            error = Core.humanize(e)
        }
    }

    val tv = LocalTv.current
    val context = LocalContext.current
    val all = items.orEmpty()
    val groups = remember(all) { all.groupBy { it.group }.map { it.key to it.value.size } }
    val text = filter.trim()
    val visible = all.filter { (group == null || it.group == group) && (text.isEmpty() || XtreamClient.titleMatches(it.name, text)) }
    val current = selected?.takeIf { s -> visible.any { it.id == s.id } } ?: visible.firstOrNull()

    // On TV live list: split-screen with embedded live mini-player on the left
    // and scrollable channel list on the right. 1st click plays in the left
    // preview; 2nd click on the same channel opens full screen.
    var previewChannel by remember(playlist.id) { mutableStateOf<ChannelItem?>(null) }
    var previewPlayer by remember { mutableStateOf<MpvPlayer?>(null) }

    fun releasePreview() {
        previewPlayer?.release()
        previewPlayer = null
        previewChannel = null
    }

    fun openFullScreen(c: ChannelItem) {
        releasePreview()
        Nav.play(PlayRequest.forChannel(c, playlist))
    }

    DisposableEffect(Unit) {
        onDispose { releasePreview() }
    }

    LaunchedEffect(tv, kind, guide) {
        if (!tv || kind != ChannelKind.Live || guide) releasePreview()
    }

    if (tv && kind == ChannelKind.Live && !guide && items != null && error == null && visible.isNotEmpty()) {
        val active = previewChannel ?: current
        Row(Modifier.fillMaxSize()) {
            // Left column: embedded live mini-player + EPG + Fullscreen CTA
            Column(
                Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .background(p.backgroundAlt)
                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (active != null) {
                    TvLivePreviewPane(
                        playlist = playlist,
                        channel = active,
                        playing = previewChannel?.id == active.id,
                        player = previewPlayer,
                        now = now,
                        onOpenFullScreen = { openFullScreen(active) },
                    )
                }
            }

            // Right column: compact header + scrollable channels
            LazyColumn(
                Modifier.weight(0.55f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                item {
                    Column(Modifier.padding(start = Spacing.md, end = Spacing.lg, top = Spacing.md, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlaylistPicker(playlist, playlists, Modifier.weight(1f))
                            Box(Modifier.size(38.dp).clickable { reload++ }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Refresh, tr("Actualiser"), tint = p.textSecondary)
                            }
                            Box(Modifier.size(38.dp).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Add, tr("Ajouter une source"), tint = p.textSecondary)
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (playlist.isXtream) {
                                listOf(tr("TV en direct") to ChannelKind.Live, tr("Films") to ChannelKind.Movie, tr("Séries") to ChannelKind.Series).forEach { (label, k) ->
                                    Chip(label, k == kind) { kind = k }
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                            Chip(tr("Liste"), !guide) { guide = false }
                            Chip(tr("Guide TV"), guide) { guide = true }
                        }
                        Picker(tr("Bouquet"), listOf(null to "${tr("Tous les bouquets")}  (${all.size})") + groups.map { (g, n) -> g to "$g  ($n)" }, group) { group = it }
                        TextInput(filter, { filter = it }, tr("Rechercher une chaîne"))
                    }
                }
                items(visible, key = { it.id }) { c ->
                    key(guideVersion) {
                        val isPlayingHere = previewChannel?.id == c.id
                        ChannelRow(c, isPlayingHere || c.id == active?.id, now) {
                            if (isPlayingHere) {
                                openFullScreen(c)
                            } else {
                                selected = c
                                previewChannel = c
                                val mpv = previewPlayer ?: MpvPlayer(context).also { previewPlayer = it }
                                PlayerPrefs.current.let { mpv.applyProfile(it.profile, it.toneMapping) }
                                PlayRequest.forChannel(c, playlist).url?.let { mpv.load(it, 0.0) }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow(
                    when (kind) {
                        ChannelKind.Movie -> tr("Films · %s", all.size)
                        ChannelKind.Series -> tr("Séries · %s", all.size)
                        ChannelKind.Live -> tr("Direct · %s chaînes", all.size)
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlaylistPicker(playlist, playlists, Modifier.weight(1f))
                    Box(Modifier.size(40.dp).clickable { reload++ }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Refresh, tr("Actualiser"), tint = p.textSecondary)
                    }
                    Box(Modifier.size(40.dp).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, tr("Ajouter une source"), tint = p.textSecondary)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (playlist.isXtream) {
                        listOf(tr("TV en direct") to ChannelKind.Live, tr("Films") to ChannelKind.Movie, tr("Séries") to ChannelKind.Series).forEach { (label, k) ->
                            Chip(label, k == kind) { kind = k }
                        }
                    }
                    if (kind == ChannelKind.Live) {
                        if (playlist.isXtream) Spacer(Modifier.width(8.dp))
                        Chip(tr("Liste"), !guide) { guide = false }
                        Chip(tr("Guide TV"), guide) { guide = true }
                    }
                }
                val allLabel = when (kind) { ChannelKind.Movie -> tr("Tous les films"); ChannelKind.Series -> tr("Toutes les séries"); else -> tr("Tous les bouquets") }
                Picker(tr("Bouquet"), listOf(null to "$allLabel  (${all.size})") + groups.map { (g, n) -> g to "$g  ($n)" }, group) { group = it }
                TextInput(filter, { filter = it }, when (kind) { ChannelKind.Movie -> tr("Rechercher un film"); ChannelKind.Series -> tr("Rechercher une série"); else -> tr("Rechercher une chaîne") })
            }
        }
        when {
            items == null -> item { LoadingRow() }
            error != null -> item { Text(error!!, style = Type.body(13), color = p.danger, modifier = Modifier.padding(Spacing.lg)) }
            visible.isEmpty() -> item {
                Text(if (text.isNotEmpty()) tr("Rien ne correspond à « %s ».", text) else tr("Rien dans ce bouquet."), style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg))
            }
            kind != ChannelKind.Live -> item { VodGrid(playlist, visible) }
            guide -> item {
                key(guideVersion) { Guide(playlist, visible.take(300), now) }
            }
            else -> {
                if (current != null) item(key = "hero-${current.id}-$guideVersion-$now") { ChannelHero(playlist, current, now) }
                items(visible, key = { it.id }) { c ->
                    key(guideVersion) {
                        ChannelRow(c, c.id == current?.id, now) {
                            if (c.id == current?.id) Nav.play(PlayRequest.forChannel(c, playlist))
                            else selected = c
                        }
                    }
                }
            }
        }
    }
}

/** Left-pane live mini-player + EPG for Android TV split-screen live TV. */
@Composable
private fun ColumnScope.TvLivePreviewPane(
    playlist: PlaylistRef,
    channel: ChannelItem,
    playing: Boolean,
    player: MpvPlayer?,
    now: Instant,
    onOpenFullScreen: () -> Unit,
) {
    val p = VeltrioTheme.palette
    var shortEpg by remember(channel.id) { mutableStateOf<List<XtreamProgramme>?>(null) }
    var replay by remember(channel.id) { mutableStateOf(false) }
    val programme = EpgStore.current(channel, now)
    val account = playlist.xtream
    LaunchedEffect(channel.id) {
        if (programme == null && account != null && channel.xtreamId != null) {
            shortEpg = XtreamClient.shortEpg(account, channel.xtreamId!!)
        }
    }
    val nowTitle: String?
    val nowTime: String?
    val progress: Float
    val upcoming: List<Pair<String, String>>
    if (programme != null) {
        nowTitle = programme.title; nowTime = programme.timeRange; progress = programme.progressAt(now)
        upcoming = EpgStore.upcoming(channel, now).take(3).map { it.start.hm() to it.title }
    } else {
        val s = shortEpg.orEmpty().firstOrNull { it.start <= now && now < it.end }
        nowTitle = s?.title; nowTime = s?.let { "${it.start.hm()} – ${it.end.hm()}" }
        progress = s?.let { ((now.epochSecond - it.start.epochSecond).toFloat() / (it.end.epochSecond - it.start.epochSecond)).coerceIn(0f, 1f) } ?: 0f
        upcoming = shortEpg.orEmpty().filter { it.start > now }.take(3).map { it.start.hm() to it.title }
    }

    // 16:9 mini-player surface (clicking opens fullscreen)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
            .border(1.dp, if (playing) p.accent else p.border)
            .clickable(onClick = onOpenFullScreen),
        contentAlignment = Alignment.Center,
    ) {
        if (playing && player != null) {
            val state by player.state.collectAsState()
            AndroidView(
                factory = { SurfaceView(it).apply { holder.addCallback(player) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (!state.loaded || state.buffering) {
                CircularProgressIndicator(Modifier.size(28.dp), color = p.accent, strokeWidth = 2.5.dp)
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(tr("OK · PLEIN ÉCRAN"), style = Type.mono(9, FontWeight.Bold), color = p.accentText)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(channel.logo, null, Modifier.size(width = 88.dp, height = 56.dp), contentScale = ContentScale.Fit)
                Text(tr("Sélectionnez une chaîne pour prévisualiser"), style = Type.caption(11), color = p.textTertiary)
            }
        }
    }

    // Channel info + EPG
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 52.dp, height = 36.dp).background(p.surfaceElevated), contentAlignment = Alignment.Center) {
                AsyncImage(channel.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("● EN DIRECT"), style = Type.eyebrow(9, 0.18f), color = p.danger)
                Text(channel.name, style = Type.title(17), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (nowTitle != null) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row { Eyebrow(tr("En ce moment"), modifier = Modifier.weight(1f)); Text(nowTime ?: "", style = Type.mono(10), color = p.textTertiary) }
                Text(nowTitle, style = Type.label(14, FontWeight.SemiBold), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(3.dp).background(p.hairline)) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(p.accent)) }
            }
        }
        if (upcoming.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Eyebrow(tr("À suivre"), p.textTertiary)
                upcoming.forEach { (time, title) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(time, style = Type.mono(10, FontWeight.SemiBold), color = p.accentText, modifier = Modifier.width(38.dp))
                        Text(title, style = Type.caption(12), color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(tr("Plein écran"), Modifier.weight(1f), icon = Icons.Filled.Fullscreen, onClick = onOpenFullScreen)
        if (channel.hasCatchup && account != null) GhostButton(tr("Revoir"), icon = Icons.Filled.History) { replay = true }
    }
    if (replay && account != null) CatchupDialog(playlist, channel) { replay = false }
}

@Composable
private fun key(@Suppress("UNUSED_PARAMETER") k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun PlaylistPicker(playlist: PlaylistRef, playlists: List<PlaylistRef>, modifier: Modifier) {
    val p = VeltrioTheme.palette
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clickable(enabled = playlists.size > 1) { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Text(playlist.name, style = Type.displaySerif(30), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (playlists.size > 1) Icon(Icons.Filled.ArrowDropDown, tr("Changer de source"), tint = p.textSecondary)
        }
        DropdownMenu(open, { open = false }) {
            playlists.forEach { pl ->
                DropdownMenuItem({ Text(pl.name, fontWeight = if (pl.id == playlist.id) FontWeight.Bold else FontWeight.Normal) }, {
                    IptvStore.select(pl)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun ChannelRow(c: ChannelItem, selected: Boolean, now: Instant, onClick: () -> Unit) {
    val p = VeltrioTheme.palette
    val programme = EpgStore.current(c, now)
    Row(
        Modifier.fillMaxWidth().background(if (selected) p.surface else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(c.numberLabel, style = Type.mono(10, FontWeight.SemiBold), color = if (selected) p.accentText else p.textQuaternary, modifier = Modifier.width(28.dp))
        Box(Modifier.size(width = 52.dp, height = 36.dp).background(p.surfaceElevated), contentAlignment = Alignment.Center) {
            AsyncImage(c.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.name, style = Type.label(14, FontWeight.SemiBold), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (programme != null) {
                Text("${programme.start.hm()}  ${programme.title}", style = Type.caption(11), color = p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(2.dp).background(p.hairline)) {
                    Box(Modifier.fillMaxWidth(programme.progressAt(now)).fillMaxHeight().background(p.accent))
                }
            } else Text(c.group, style = Type.caption(11), color = p.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.hasCatchup) Icon(Icons.Filled.History, tr("Replay"), Modifier.size(14.dp), tint = p.textQuaternary)
    }
}

/** The selected channel: what's on now (XMLTV, else Xtream's short EPG),
 *  what's next, Watch and Replay. */
@Composable
private fun ChannelHero(playlist: PlaylistRef, c: ChannelItem, now: Instant) {
    val p = VeltrioTheme.palette
    var shortEpg by remember(c.id) { mutableStateOf<List<XtreamProgramme>?>(null) }
    var replay by remember(c.id) { mutableStateOf(false) }
    val programme = EpgStore.current(c, now)
    val account = playlist.xtream
    LaunchedEffect(c.id) {
        // No XMLTV entry: Xtream's short EPG for this one channel.
        if (programme == null && account != null && c.xtreamId != null) shortEpg = XtreamClient.shortEpg(account, c.xtreamId!!)
    }
    val nowTitle: String?
    val nowTime: String?
    val progress: Float
    val description: String?
    val upcoming: List<Pair<String, String>>
    if (programme != null) {
        nowTitle = programme.title; nowTime = programme.timeRange; progress = programme.progressAt(now); description = programme.description
        upcoming = EpgStore.upcoming(c, now).take(5).map { it.start.hm() to it.title }
    } else {
        val s = shortEpg.orEmpty().firstOrNull { it.start <= now && now < it.end }
        nowTitle = s?.title; nowTime = s?.let { "${it.start.hm()} – ${it.end.hm()}" }
        progress = s?.let { ((now.epochSecond - it.start.epochSecond).toFloat() / (it.end.epochSecond - it.start.epochSecond)).coerceIn(0f, 1f) } ?: 0f
        description = s?.description
        upcoming = shortEpg.orEmpty().filter { it.start > now }.take(5).map { it.start.hm() to it.title }
    }
    Column(Modifier.fillMaxWidth().padding(Spacing.lg).border(1.dp, p.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(width = 72.dp, height = 48.dp).background(p.surfaceElevated), contentAlignment = Alignment.Center) {
                AsyncImage(c.logo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("● EN DIRECT"), style = Type.eyebrow(9, 0.18f), color = p.danger)
                    if (c.number > 0) Text(tr("CH %s", c.numberLabel), style = Type.mono(9, FontWeight.SemiBold), color = p.textTertiary)
                }
                Text(c.name, style = Type.title(18), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(c.group.uppercase(), style = Type.eyebrow(9, 0.14f), color = p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (nowTitle != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Eyebrow(tr("En ce moment"), modifier = Modifier.weight(1f)); Text(nowTime ?: "", style = Type.mono(11), color = p.textTertiary) }
                Text(nowTitle, style = Type.title(16), color = p.textPrimary)
                Box(Modifier.fillMaxWidth().height(3.dp).background(p.hairline)) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(p.accent)) }
                description?.let { Text(it, style = Type.caption(12), color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        } else Text(tr("Pas d'informations de programme pour cette chaîne."), style = Type.caption(12), color = p.textTertiary)
        if (upcoming.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(tr("À suivre"), p.textTertiary)
                upcoming.forEach { (time, title) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(time, style = Type.mono(11, FontWeight.SemiBold), color = p.accentText, modifier = Modifier.width(42.dp))
                        Text(title, style = Type.body(13), color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(tr("Regarder"), Modifier.weight(1f), icon = Icons.Filled.PlayArrow) { Nav.play(PlayRequest.forChannel(c, playlist)) }
            if (c.hasCatchup && account != null) GhostButton(tr("Revoir"), icon = Icons.Filled.History) { replay = true }
        }
    }
    if (replay && account != null) CatchupDialog(playlist, c) { replay = false }
}

/** Past programmes the provider keeps, to replay one. */
@Composable
internal fun CatchupDialog(playlist: PlaylistRef, c: ChannelItem, onPlay: (PlayRequest) -> Unit = Nav::play, onDismiss: () -> Unit) {
    val p = VeltrioTheme.palette
    val account = playlist.xtream ?: return
    var programmes by remember { mutableStateOf<List<XtreamProgramme>?>(null) }
    LaunchedEffect(Unit) { programmes = XtreamClient.catchup(account, c.xtreamId!!) }
    Dialog(onDismiss) {
        Column(Modifier.fillMaxWidth().background(p.backgroundAlt).border(1.dp, p.border).padding(vertical = 18.dp)) {
            Eyebrow(tr("Revoir"), modifier = Modifier.padding(horizontal = 20.dp))
            Text(c.name, style = Type.title(18), color = p.textPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            val list = programmes
            when {
                list == null -> LoadingRow()
                list.isEmpty() -> Text(tr("Aucun programme à revoir."), style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(20.dp))
                else -> LazyColumn(Modifier.height(420.dp)) {
                    items(list.take(80)) { prog ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                onPlay(
                                    PlayRequest(
                                        title = c.name, subtitle = tr("Replay · %s · %s", prog.timeRange, prog.title),
                                        url = XtreamClient.timeshiftUrl(account, c.xtreamId!!, prog), channel = c, playlist = playlist,
                                    ),
                                )
                            }.padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(prog.startLabel, style = Type.mono(11), color = p.accentText, modifier = Modifier.width(84.dp))
                            Text(prog.title, style = Type.body(13), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            TextButton(onDismiss, Modifier.align(Alignment.End).padding(end = 12.dp)) { Text(tr("FERMER"), style = Type.eyebrow(10, 0.18f), color = p.textPrimary) }
        }
    }
}

/** The TV guide: channels as rows, programmes as blocks on a time axis from
 *  half an hour back to six hours ahead; the channel column stays put while
 *  the grid scrolls sideways. */
@Composable
private fun Guide(playlist: PlaylistRef, channels: List<ChannelItem>, now: Instant) {
    val p = VeltrioTheme.palette
    val loading by EpgStore.loading.collectAsState()
    if (!EpgStore.hasGuide) {
        Text(
            if (loading) tr("Chargement du guide TV…") else EpgStore.error ?: tr("Cette source ne fournit pas de guide TV."),
            style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg),
        )
        return
    }
    val perMinute = 4.dp
    val rowHeight = 56.dp
    val nameWidth = 96.dp
    val start = now.minusSeconds(now.epochSecond % 1800).minusSeconds(1800)
    val end = start.plusSeconds(6 * 3600 + 1800)
    val minutes = (end.epochSecond - start.epochSecond) / 60
    Row(Modifier.padding(top = Spacing.md)) {
        Column(Modifier.width(nameWidth)) {
            Spacer(Modifier.height(28.dp))
            channels.forEach { c ->
                Row(
                    Modifier.height(rowHeight).fillMaxWidth().border(0.5.dp, p.hairline).clickable { Nav.play(PlayRequest.forChannel(c, playlist)) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.name, style = Type.label(11, FontWeight.SemiBold), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(perMinute * minutes.toInt())) {
                Box(Modifier.height(28.dp).fillMaxWidth()) {
                    (0 until minutes.toInt() step 30).forEach { m ->
                        Text(start.plusSeconds(m * 60L).hm(), style = Type.mono(10), color = p.textTertiary, modifier = Modifier.offset(x = perMinute * m + 4.dp, y = 6.dp))
                    }
                }
                channels.forEach { c ->
                    Box(Modifier.height(rowHeight).fillMaxWidth().border(0.5.dp, p.hairline)) {
                        EpgStore.schedule(c).filter { it.end > start && it.start < end }.forEach { prog ->
                            val from = maxOf(prog.start.epochSecond, start.epochSecond)
                            val to = minOf(prog.end.epochSecond, end.epochSecond)
                            val onNow = prog.start <= now && now < prog.end
                            Column(
                                Modifier.offset(x = perMinute * ((from - start.epochSecond) / 60f))
                                    .width(perMinute * ((to - from) / 60f)).fillMaxHeight().padding(1.dp)
                                    .background(if (onNow) p.surfaceElevated else p.surface)
                                    .clickable { Nav.play(PlayRequest.forChannel(c, playlist)) }.padding(horizontal = 6.dp, vertical = 5.dp),
                            ) {
                                Text(prog.title, style = Type.label(11, FontWeight.SemiBold), color = if (onNow) p.textPrimary else p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(prog.timeRange, style = Type.mono(9), color = p.textTertiary, maxLines = 1)
                            }
                        }
                    }
                }
            }
            // The "now" line across the grid.
            Box(Modifier.offset(x = perMinute * ((now.epochSecond - start.epochSecond) / 60f)).width(2.dp).height(28.dp + rowHeight * channels.size).background(p.accent))
        }
    }
}

@Composable
private fun VodGrid(playlist: PlaylistRef, items: List<ChannelItem>) {
    // A grid inside the page's list: laid out in rows of three.
    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.take(600).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item ->
                    Box(Modifier.weight(1f)) {
                        PosterCard(item.name, item.logo, item.subtitle, width = 200.dp) {
                            if (item.kind == ChannelKind.Series) Nav.openIptvSeries(playlist, item)
                            else Nav.play(PlayRequest.forChannel(item, playlist))
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Add an IPTV source: an M3U URL, an M3U file on the device, or an Xtream
 *  Codes account (checked before it's kept). */
@Composable
internal fun AddPlaylistDialog(onDismiss: () -> Unit) {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("xtream") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("ts") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            error = IptvStore.addLocalFile(context, name, uri)
            if (error == null) onDismiss()
        }
    }
    Dialog(onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(p.backgroundAlt).border(1.dp, p.border).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow(tr("Direct"))
            Text(tr("Ajouter une source"), style = Type.displaySerif(24), color = p.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tr("Xtream Codes"), mode == "xtream") { mode = "xtream" }
                Chip(tr("URL M3U"), mode == "m3u") { mode = "m3u" }
                Chip(tr("Fichier"), mode == "file") { mode = "file" }
            }
            TextInput(name, { name = it }, tr("Nom (facultatif)"))
            when (mode) {
                "xtream" -> {
                    TextInput(host, { host = it }, tr("Serveur (http://exemple.com:8080)"))
                    TextInput(user, { user = it }, tr("Utilisateur"))
                    TextInput(password, { password = it }, tr("Mot de passe"), secret = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("FORMAT DU DIRECT"), style = Type.eyebrow(9, 0.18f), color = p.textTertiary)
                        Chip("TS", output == "ts") { output = "ts" }
                        Chip("HLS", output == "m3u8") { output = "m3u8" }
                    }
                }
                "m3u" -> TextInput(url, { url = it }, "https://…/playlist.m3u")
                else -> Text(tr("Choisissez un fichier .m3u ou .m3u8 : il est copié dans l'app."), style = Type.body(13), color = p.textSecondary)
            }
            error?.let { Text(it, style = Type.caption(12), color = p.danger) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (mode == "file") tr("Choisir le fichier") else tr("Ajouter"), loading = busy) {
                    when (mode) {
                        "xtream" -> {
                            busy = true
                            scope.launch {
                                error = IptvStore.addXtream(name, host, user, password, output)
                                busy = false
                                if (error == null) onDismiss()
                            }
                        }
                        "m3u" -> {
                            error = IptvStore.addM3u(name, url)
                            if (error == null) onDismiss()
                        }
                        else -> pick.launch(arrayOf("*/*"))
                    }
                }
                GhostButton(tr("Annuler"), onClick = onDismiss)
            }
        }
    }
}
