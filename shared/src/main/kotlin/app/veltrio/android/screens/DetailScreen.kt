package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.DetailRequest
import app.veltrio.android.LocalTv
import app.veltrio.android.Nav
import app.veltrio.android.ui.hero
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.LoadingRow
import app.veltrio.android.ui.PosterCard
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.shared.Core
import app.veltrio.shared.DebridService
import app.veltrio.shared.DownloadManager
import androidx.compose.foundation.combinedClickable
import app.veltrio.shared.DirectSources
import kotlinx.coroutines.async
import app.veltrio.shared.EpisodeItem
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.StreamItem
import app.veltrio.shared.TitleDetails
import app.veltrio.shared.WatchStore
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val qualities = listOf(tr("Tout"), "4K", "1080p", "720p")
private val CACHED get() = tr("⚡ En cache")

/** A title's page, as macOS's DetailView on a phone's width: the backdrop,
 *  poster and facts, Watch / Save, credits; for a series the seasons and
 *  episodes, then an episode's streams; for a film its streams. */
@Composable
fun DetailScreen(request: DetailRequest) {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var details by remember { mutableStateOf<TitleDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var episode by remember { mutableStateOf<EpisodeItem?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }
    var streams by remember { mutableStateOf<List<StreamItem>?>(null) }
    var showStreams by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(tr("Tout")) }
    var source by remember { mutableStateOf<String?>(null) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    val entries by WatchStore.entries.collectAsState()
    val saved = entries["${request.type}|${request.id}"]?.inLibrary == true

    fun loadStreams(id: String) {
        loadJob?.cancel()
        streams = null
        quality = tr("Tout")
        source = null
        loadJob = scope.launch {
            // The user's own copies (media servers, IPTV provider) are looked
            // up alongside and listed first once found.
            val parts = id.split(':')
            val direct = if (parts[0].startsWith("tt") && DirectSources.anyConfigured) async {
                runCatching {
                    DirectSources.find(
                        parts[0], details?.name ?: request.name, details?.year?.take(4)?.toIntOrNull(),
                        parts.getOrNull(1)?.toIntOrNull().takeIf { parts.size == 3 }, parts.getOrNull(2)?.toIntOrNull(),
                    )
                }.getOrDefault(emptyList())
            } else null
            val fetched = Core.fetchStreams(request.type, id)
            // A copy downloaded for offline playback comes first.
            val offline = DownloadManager.localFile(id)?.let { listOf(StreamItem.local(DownloadManager.item(id)!!)) }.orEmpty()
            streams = offline + fetched
            direct?.await()?.let { copies -> if (copies.isNotEmpty()) streams = offline + copies + fetched }
            // Ask the debrid services what they have cached: ⚡ first.
            if (DebridService.anyConnected) {
                DebridService.prefetch(fetched.mapNotNull { it.infoHash })
                streams = streams.orEmpty().sortedWith(compareByDescending<StreamItem> { it.origin != null }.thenByDescending { it.cachedOn != null })
            }
        }
    }

    LaunchedEffect(request) {
        try {
            val d = Core.fetchDetails(request.addon, request.type, request.id)
            details = d
            if (d.isSeries) {
                // Resume where the user was, else the first season.
                val lastId = WatchStore.entry(request.type, request.id)?.lastEpisodeId
                episode = d.episodes.firstOrNull { it.id == lastId }
                season = episode?.season ?: d.episodes.firstOrNull { (it.season ?: 0) > 0 }?.season ?: d.episodes.first().season
            } else {
                showStreams = true
                loadStreams(d.id)
            }
        } catch (e: Exception) {
            error = Core.humanize(e)
            showStreams = true
            loadStreams(request.id)
        }
    }

    val d = details
    fun nextEpisode(of: EpisodeItem): EpisodeItem? {
        val list = d?.episodes?.filter { (it.season ?: 0) > 0 } ?: return null
        val index = list.indexOfFirst { it.id == of.id }
        return if (index >= 0 && index + 1 < list.size) list[index + 1] else null
    }

    /** The player request for a stream, chained to the following episode. */
    fun playRequest(stream: StreamItem, ep: EpisodeItem?): PlayRequest {
        val next = ep?.let { nextEpisode(it) }
        return PlayRequest(
            title = d?.name ?: request.name,
            subtitle = if (ep == null) stream.name else "${ep.code} — ${ep.title}",
            url = stream.localPath,
            stream = stream,
            contentType = request.type, contentId = details?.id ?: request.id,
            videoId = ep?.id, season = ep?.season, episode = ep?.episode,
            poster = d?.poster ?: request.poster, background = d?.background ?: request.background,
            addon = d?.addon ?: request.addon,
            nextLabel = next?.let { "${it.code.replace(" · ", "")} · ${it.title}" },
            next = next?.let { n ->
                {
                    // Same release (bingeGroup), else same addon and quality, else the best.
                    val playable = Core.fetchStreams(request.type, n.id).filter { Core.externalUrl(it) == null }
                    val pick = stream.bingeGroup?.let { g -> playable.firstOrNull { it.bingeGroup == g } }
                        ?: playable.firstOrNull { it.addonName == stream.addonName && it.quality == stream.quality }
                        ?: playable.firstOrNull()
                    pick?.let { playRequest(it, n) }
                }
            },
        )
    }

    fun play(stream: StreamItem) {
        Core.externalUrl(stream)?.let {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
            return
        }
        Nav.play(playRequest(stream, episode))
    }

    // Android 13+: downloads show their progress in a notification.
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    val visible = streams.orEmpty().filter {
        (source == null || it.addonName == source) && when (quality) {
            tr("Tout") -> true
            CACHED -> it.cachedOn != null
            else -> it.quality == quality
        }
    }

    var selectedPerson by remember { mutableStateOf<String?>(null) }
    selectedPerson?.let { person ->
        PersonFilmographyDialog(person, onDismiss = { selectedPerson = null }) { item ->
            selectedPerson = null
            openPoster(item)
        }
    }

    if (LocalTv.current) {
        Box(Modifier.fillMaxSize().background(p.background)) {
            AsyncImage(d?.background ?: request.background ?: request.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.28f)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to p.background.copy(alpha = 0.92f), 0.5f to p.background.copy(alpha = 0.78f), 1f to p.background.copy(alpha = 0.92f))))
            Row(Modifier.fillMaxSize()) {
                // Left column: back, title, poster + facts, synopsis, primary actions, credits
                Column(
                    Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        Modifier.background(Color.Black.copy(alpha = 0.45f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f)).clickable { Nav.back() }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = Color.White)
                        Text(tr("RETOUR"), style = Type.eyebrow(10, 0.2f), color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(24.dp).height(1.dp).background(p.accent))
                        Eyebrow((d?.addon ?: request.addon).name)
                        Eyebrow("· " + Core.typeLabel(request.type), p.textTertiary)
                    }
                    Text(d?.name ?: request.name, style = Type.displaySerif(32), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.width(104.dp).aspectRatio(2f / 3f).background(p.surface)) {
                            AsyncImage(d?.poster ?: request.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Eyebrow(tr("Aperçu"), p.textTertiary)
                            Fact(tr("Sortie"), d?.year)
                            Fact(tr("Durée"), d?.runtime)
                            Fact(tr("Genres"), d?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", "))
                            d?.rating?.let { Text("★ $it  IMDb", style = Type.mono(12), color = p.accentText) }
                        }
                    }
                    d?.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.body(13), color = p.textSecondary) }
                    if (error != null) Text(error!!, style = Type.caption(12), color = p.danger)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val resume = WatchStore.resumePosition(request.type, request.id) > 0
                        val label = when {
                            d?.isSeries == true && !showStreams -> (episode ?: d.episodes.firstOrNull { it.season == season })?.let { tr("Lire %s", it.code.replace(" · ", "")) } ?: tr("Aucun épisode")
                            streams == null -> tr("Chargement des flux")
                            visible.isEmpty() -> tr("Aucun flux")
                            episode != null -> tr("Lire %s", episode!!.code.replace(" · ", ""))
                            resume -> tr("Reprendre")
                            else -> tr("Regarder")
                        }
                        val canPlay = if (d?.isSeries == true && !showStreams) d.episodes.isNotEmpty() else visible.isNotEmpty()
                        PrimaryButton(label, Modifier.weight(1f), icon = Icons.Filled.PlayArrow, enabled = canPlay, loading = showStreams && streams == null) {
                            if (d?.isSeries == true && !showStreams) {
                                (episode ?: d.episodes.firstOrNull { it.season == season })?.let {
                                    episode = it
                                    showStreams = true
                                    loadStreams(it.id)
                                }
                            } else visible.firstOrNull()?.let { play(it) }
                        }
                        GhostButton(
                            if (saved) tr("Enregistré") else tr("Enregistrer"), icon = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            color = if (saved) p.accentText else p.textPrimary,
                        ) {
                            WatchStore.toggleLibrary(request.type, request.id, d?.name ?: request.name, d?.poster ?: request.poster, d?.background ?: request.background, d?.addon ?: request.addon)
                        }
                    }
                    d?.trailerUrl?.let { url ->
                        GhostButton(tr("Bande-annonce"), icon = Icons.Filled.PlayArrow) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    }
                    if (d != null && (d.cast.isNotEmpty() || d.director.isNotEmpty())) {
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                            Eyebrow(tr("Générique"), p.textTertiary)
                            PersonFactRow(tr("Réalisation"), d.director.take(3)) { selectedPerson = it }
                            Fact(tr("Scénario"), d.writer.take(3).joinToString(", ").ifEmpty { null })
                            PersonFactRow(tr("Distribution"), d.cast.take(8)) { selectedPerson = it }
                        }
                    }
                }

                // Right column: episodes (for series) or streams list
                LazyColumn(
                    Modifier.weight(0.58f).fillMaxHeight(),
                    contentPadding = PaddingValues(top = Spacing.lg, bottom = Spacing.xl, end = Spacing.md),
                ) {
                    if (d?.isSeries == true && !showStreams) {
                        item {
                            Column(Modifier.padding(start = Spacing.lg, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Eyebrow(tr("Épisodes"))
                                    Eyebrow(tr("%s · %s au total", d.episodes.count { it.season == season }, d.episodes.size), p.textTertiary)
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    d.episodes.map { it.season }.distinct().forEach { s ->
                                        Chip(if (s == null || s == 0) tr("Bonus") else tr("Saison %s", s), s == season) { season = s }
                                    }
                                    Spacer(Modifier.width(Spacing.lg))
                                }
                            }
                        }
                        items(d.episodes.filter { it.season == season }, key = { it.id }) { ep ->
                            EpisodeRow(request, ep) {
                                episode = ep
                                showStreams = true
                                loadStreams(ep.id)
                            }
                        }
                    }
                    if (showStreams) {
                        item {
                            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (d?.isSeries == true && episode != null) {
                                    Row(
                                        Modifier.clickable { showStreams = false; loadJob?.cancel() },
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(12.dp), tint = p.textPrimary)
                                        Text(tr("ÉPISODES"), style = Type.eyebrow(10, 0.2f), color = p.textPrimary)
                                    }
                                    Text("${episode!!.code} — ${episode!!.title}", style = Type.title(20), color = p.textPrimary)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Eyebrow(tr("Flux"))
                                    if (streams != null) Eyebrow(tr("%s sur %s", visible.size, streams!!.size), p.textTertiary)
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val filters = if (DebridService.anyConnected) listOf(tr("Tout"), CACHED) + qualities.drop(1) else qualities
                                    filters.forEach { q -> Chip(q, q == quality) { quality = q } }
                                    streams.orEmpty().map { it.addonName }.distinct().takeIf { it.size > 1 }?.forEach { name ->
                                        Chip(name, name == source) { source = if (source == name) null else name }
                                    }
                                }
                            }
                        }
                        when {
                            streams == null -> item { LoadingRow() }
                            visible.isEmpty() -> item {
                                Text(
                                    if (streams!!.isEmpty()) {
                                        if (Core.enabledAddons.any { it.serves("stream", request.type) }) tr("Aucune source n'a répondu avec un flux pour ce titre.")
                                        else tr("Aucune source installée ne fournit de flux. Ajoutez-en une dans Sources.")
                                    } else tr("Aucun flux ne correspond à ces filtres."),
                                    style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg),
                                )
                            }
                            else -> items(visible) { stream ->
                                StreamRow(stream, onDownload = null) { play(stream) }
                            }
                        }
                    }
                    if (d != null && d.recommendations.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Eyebrow(tr("Titres similaires"), p.textTertiary, Modifier.padding(horizontal = Spacing.lg))
                                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(d.recommendations, key = { it.id }) { item ->
                                        PosterCard(item.title, item.posterUrl, item.subtitle) { openPoster(item) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().background(p.background), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Box(Modifier.hero(1.7f, 240.dp)) {
                AsyncImage(d?.background ?: request.background ?: request.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to p.background.copy(alpha = 0.35f), 0.4f to p.background.copy(alpha = 0.15f), 1f to p.background)))
                Row(
                    Modifier.statusBarsPadding().padding(Spacing.md).background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f)).clickable { Nav.back() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = Color.White)
                    Text(tr("RETOUR"), style = Type.eyebrow(10, 0.2f), color = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.width(28.dp).height(1.dp).background(p.accent))
                        Eyebrow((d?.addon ?: request.addon).name)
                        Eyebrow("· " + Core.typeLabel(request.type), p.textTertiary)
                    }
                    Text(d?.name ?: request.name, style = Type.displaySerif(32), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.width(110.dp).aspectRatio(2f / 3f).background(p.surface)) {
                        AsyncImage(d?.poster ?: request.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Eyebrow(tr("Aperçu"), p.textTertiary)
                        Fact(tr("Sortie"), d?.year)
                        Fact(tr("Durée"), d?.runtime)
                        Fact(tr("Genres"), d?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", "))
                        d?.rating?.let { Text("★ $it  IMDb", style = Type.mono(12), color = p.accentText) }
                    }
                }
                d?.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.body(14), color = p.textSecondary) }
                if (error != null) Text(error!!, style = Type.caption(12), color = p.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val resume = WatchStore.resumePosition(request.type, request.id) > 0
                    val label = when {
                        d?.isSeries == true && !showStreams -> (episode ?: d.episodes.firstOrNull { it.season == season })?.let { tr("Lire %s", it.code.replace(" · ", "")) } ?: tr("Aucun épisode")
                        streams == null -> tr("Chargement des flux")
                        visible.isEmpty() -> tr("Aucun flux")
                        episode != null -> tr("Lire %s", episode!!.code.replace(" · ", ""))
                        resume -> tr("Reprendre")
                        else -> tr("Regarder")
                    }
                    val canPlay = if (d?.isSeries == true && !showStreams) d.episodes.isNotEmpty() else visible.isNotEmpty()
                    PrimaryButton(label, Modifier.weight(1f), icon = Icons.Filled.PlayArrow, enabled = canPlay, loading = showStreams && streams == null) {
                        if (d?.isSeries == true && !showStreams) {
                            (episode ?: d.episodes.firstOrNull { it.season == season })?.let {
                                episode = it
                                showStreams = true
                                loadStreams(it.id)
                            }
                        } else visible.firstOrNull()?.let { play(it) }
                    }
                    GhostButton(
                        if (saved) tr("Enregistré") else tr("Enregistrer"), icon = if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        color = if (saved) p.accentText else p.textPrimary,
                    ) {
                        WatchStore.toggleLibrary(request.type, request.id, d?.name ?: request.name, d?.poster ?: request.poster, d?.background ?: request.background, d?.addon ?: request.addon)
                    }
                }
                d?.trailerUrl?.let { url ->
                    GhostButton(tr("Bande-annonce"), icon = Icons.Filled.PlayArrow) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                }
                if (d != null && (d.cast.isNotEmpty() || d.director.isNotEmpty())) {
                    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                        Eyebrow(tr("Générique"), p.textTertiary)
                        PersonFactRow(tr("Réalisation"), d.director.take(3)) { selectedPerson = it }
                        Fact(tr("Scénario"), d.writer.take(3).joinToString(", ").ifEmpty { null })
                        PersonFactRow(tr("Distribution"), d.cast.take(8)) { selectedPerson = it }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
            }
        }

        if (d?.isSeries == true && !showStreams) {
            item {
                Column(Modifier.padding(start = Spacing.lg, top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Eyebrow(tr("Épisodes"))
                        Eyebrow(tr("%s · %s au total", d.episodes.count { it.season == season }, d.episodes.size), p.textTertiary)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.episodes.map { it.season }.distinct().forEach { s ->
                            Chip(if (s == null || s == 0) tr("Bonus") else tr("Saison %s", s), s == season) { season = s }
                        }
                        Spacer(Modifier.width(Spacing.lg))
                    }
                }
            }
            items(d.episodes.filter { it.season == season }, key = { it.id }) { ep ->
                EpisodeRow(request, ep) {
                    episode = ep
                    showStreams = true
                    loadStreams(ep.id)
                }
            }
        }

        if (showStreams) {
            item {
                Column(Modifier.padding(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (d?.isSeries == true && episode != null) {
                        Row(
                            Modifier.clickable { showStreams = false; loadJob?.cancel() },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(12.dp), tint = p.textPrimary)
                            Text(tr("ÉPISODES"), style = Type.eyebrow(10, 0.2f), color = p.textPrimary)
                        }
                        Text("${episode!!.code} — ${episode!!.title}", style = Type.title(20), color = p.textPrimary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Eyebrow(tr("Flux"))
                        if (streams != null) Eyebrow(tr("%s sur %s", visible.size, streams!!.size), p.textTertiary)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // "⚡ En cache" only means something with a debrid service connected.
                        val filters = if (DebridService.anyConnected) listOf(tr("Tout"), CACHED) + qualities.drop(1) else qualities
                        filters.forEach { q -> Chip(q, q == quality) { quality = q } }
                        streams.orEmpty().map { it.addonName }.distinct().takeIf { it.size > 1 }?.forEach { name ->
                            Chip(name, name == source) { source = if (source == name) null else name }
                        }
                    }
                }
            }
            when {
                streams == null -> item { LoadingRow() }
                visible.isEmpty() -> item {
                    Text(
                        if (streams!!.isEmpty()) {
                            if (Core.enabledAddons.any { it.serves("stream", request.type) }) tr("Aucune source n'a répondu avec un flux pour ce titre.")
                            else tr("Aucune source installée ne fournit de flux. Ajoutez-en une dans Sources.")
                        } else tr("Aucun flux ne correspond à ces filtres."),
                        style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg),
                    )
                }
                else -> items(visible) { stream ->
                    StreamRow(
                        stream,
                        onDownload = if (stream.localPath == null && Core.externalUrl(stream) == null) ({
                            askNotifications()
                            val videoId = episode?.id ?: d?.id ?: request.id
                            DownloadManager.start(videoId, playRequest(stream, episode).copy(next = null, nextLabel = null))
                        }) else null,
                    ) { play(stream) }
                }
            }
        }
        if (d != null && d.recommendations.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Eyebrow(tr("Titres similaires"), p.textTertiary, Modifier.padding(horizontal = Spacing.lg))
                    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(d.recommendations, key = { it.id }) { item ->
                            PosterCard(item.title, item.posterUrl, item.subtitle) { openPoster(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonFactRow(label: String, names: List<String>, onSelect: (String) -> Unit) {
    if (names.isEmpty()) return
    val p = VeltrioTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = Type.eyebrow(9, 0.18f), color = p.textQuaternary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name ->
                Chip(name, false) { onSelect(name) }
            }
        }
    }
}

@Composable
private fun PersonFilmographyDialog(
    personName: String,
    onDismiss: () -> Unit,
    onOpen: (app.veltrio.shared.PosterItem) -> Unit,
) {
    val p = VeltrioTheme.palette
    var credits by remember(personName) { mutableStateOf<List<app.veltrio.shared.PosterItem>?>(null) }
    var error by remember(personName) { mutableStateOf<String?>(null) }
    LaunchedEffect(personName) {
        credits = null
        error = null
        try {
            credits = app.veltrio.shared.TmdbService.fetchPersonCredits(personName)
        } catch (e: Exception) {
            error = Core.humanize(e)
            credits = emptyList()
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.surface, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .border(1.dp, p.border, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow(tr("Filmographie"), p.accentText)
                    Text(personName, style = Type.displaySerif(22), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                GhostButton(tr("Fermer")) { onDismiss() }
            }
            when {
                credits == null -> LoadingRow()
                error != null -> Text(error!!, style = Type.caption(12), color = p.danger)
                credits!!.isEmpty() -> Text(tr("Aucun titre trouvé sur TMDB."), style = Type.caption(12), color = p.textTertiary)
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(credits!!, key = { "${it.type}:${it.id}" }) { item ->
                        PosterCard(item.title, item.posterUrl, item.subtitle) { onOpen(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val p = VeltrioTheme.palette
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = Type.eyebrow(9, 0.18f), color = p.textQuaternary)
        Text(value, style = Type.body(13), color = p.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EpisodeRow(request: DetailRequest, ep: EpisodeItem, onClick: () -> Unit) {
    val p = VeltrioTheme.palette
    val progress = WatchStore.episode(request.type, request.id, ep.id)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Spacing.lg, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).background(p.surface)) {
            AsyncImage(ep.thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (progress != null && progress.duration > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((progress.position / progress.duration).toFloat().coerceIn(0f, 1f)).height(3.dp).background(p.accent))
            }
            if (progress?.watched == true) {
                Icon(Icons.Filled.CheckCircle, tr("Vu"), Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = p.accent)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ep.code, style = Type.mono(10, FontWeight.SemiBold), color = p.accentText)
                Text(ep.releasedDate, style = Type.mono(10), color = p.textTertiary)
            }
            Text(ep.title, style = Type.title(15), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ep.overview?.let { Text(it, style = Type.caption(11), color = p.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A stream; a long press offers to download it for offline playback. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun StreamRow(stream: StreamItem, onDownload: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = VeltrioTheme.palette
    var menu by remember { mutableStateOf(false) }
    androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
        androidx.compose.material3.DropdownMenuItem({ Text(tr("Télécharger pour regarder hors ligne")) }, {
            menu = false
            onDownload?.invoke()
        })
    }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onDownload?.let { { menu = true } })) {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(64.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stream.quality, style = Type.mono(13, FontWeight.Bold), color = p.accentText)
                Text(stream.kind, style = Type.eyebrow(8, 0.16f), color = p.textTertiary)
                stream.cachedOn?.let { Text("⚡ ${it.short}", style = Type.mono(10, FontWeight.Bold), color = p.success) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stream.name, style = Type.label(13), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stream.title, style = Type.mono(11), color = p.textTertiary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.PlayArrow, null, Modifier.align(Alignment.CenterVertically).size(16.dp), tint = p.textQuaternary)
        }
        Box(Modifier.padding(horizontal = Spacing.lg).fillMaxWidth().height(1.dp).background(p.border))
    }
}
