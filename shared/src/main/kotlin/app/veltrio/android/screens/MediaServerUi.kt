package app.veltrio.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.DetailRequest
import app.veltrio.android.Nav
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.LoadingRow
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.shared.Core
import app.veltrio.shared.MediaBrowserServer
import app.veltrio.shared.MediaLibraryEpisode
import app.veltrio.shared.MediaLibraryItem
import app.veltrio.shared.MediaServer
import app.veltrio.shared.MediaServerKind
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.PlexServer
import app.veltrio.shared.WatchStore
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage

/** A media server's card in Sources: Jellyfin and Emby take an address,
 *  user and password; Plex a PIN at plex.tv/link. */
@Composable
internal fun MediaServerCard(server: MediaServer) {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    val state by server.state.collectAsState()
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().border(1.dp, p.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(server.color)))
            Text(server.name, style = Type.title(16), color = p.textPrimary, modifier = Modifier.weight(1f))
            if (state.connected) Text("CONNECTÉ", style = Type.eyebrow(9, 0.2f), color = p.success)
        }
        when {
            state.connected -> {
                Text(listOfNotNull(state.user, state.host).joinToString(" · "), style = Type.mono(12), color = p.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Parcourir") { Nav.showLibrary(server.kind) }
                    GhostButton("Déconnecter", color = p.danger) { server.disconnect() }
                }
            }
            state.pendingCode != null -> {
                Text("Ouvrez ${state.pendingUrl} et saisissez ce code :", style = Type.body(13), color = p.textSecondary)
                Text(state.pendingCode ?: "", style = Type.mono(28, FontWeight.Bold), color = p.accentText)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Ouvrir la page", icon = Icons.AutoMirrored.Filled.OpenInNew) { openUrl(context, state.pendingUrl ?: "") }
                    GhostButton("Annuler") { (server as PlexServer).cancelPinFlow() }
                }
            }
            server is PlexServer -> {
                Text("Connexion par code, comme sur une TV : votre serveur Plex est trouvé tout seul.", style = Type.body(13), color = p.textSecondary)
                PrimaryButton("Se connecter", loading = state.busy) { server.startPinFlow() }
            }
            server is MediaBrowserServer -> {
                TextInput(host, { host = it }, "Adresse (http://192.168.1.10:8096)")
                TextInput(user, { user = it }, "Utilisateur")
                TextInput(password, { password = it }, "Mot de passe", secret = true)
                PrimaryButton("Se connecter", enabled = host.isNotBlank() && user.isNotBlank(), loading = state.busy) { server.connect(host, user, password) }
            }
        }
        state.error?.let { Text(it, style = Type.caption(12), color = p.danger) }
    }
}

/** Identified titles open on their title page, where this copy sits among
 *  the other sources. Films the server couldn't identify play directly;
 *  such series list their episodes. */
internal fun openServerItem(server: MediaServer, item: MediaLibraryItem) {
    val imdb = item.imdbId
    val addon = Core.metaAddon(item.type)
    when {
        imdb != null && addon != null -> Nav.openTitle(DetailRequest(addon, item.type, imdb, item.title, item.posterUrl))
        item.type == "series" -> Nav.openServerSeries(server, item)
        else -> (item.streamUrl ?: server.streamUrl(item.id))?.let { url ->
            Nav.play(PlayRequest(title = item.title, subtitle = server.name, url = url, contentType = "server", contentId = "${server.kind}-${item.id}".lowercase(), poster = item.posterUrl))
        }
    }
}

/** A series the server couldn't identify: its episodes, played from the server. */
@Composable
fun MediaServerSeriesScreen(server: MediaServer, item: MediaLibraryItem) {
    val p = VeltrioTheme.palette
    var episodes by remember { mutableStateOf<List<MediaLibraryEpisode>?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }
    WatchStore.entries.collectAsState().value
    LaunchedEffect(Unit) {
        episodes = server.episodes(item).sortedWith(compareBy({ it.season }, { it.number }))
        season = episodes?.firstOrNull()?.season
    }
    fun request(ep: MediaLibraryEpisode, all: List<MediaLibraryEpisode>): PlayRequest? {
        val url = ep.streamUrl ?: server.streamUrl(ep.id) ?: return null
        val next = all.getOrNull(all.indexOf(ep) + 1)
        return PlayRequest(
            title = item.title, subtitle = "${ep.code} — ${ep.title}", url = url,
            contentType = "server", contentId = "${server.kind}-${ep.id}".lowercase(), videoId = ep.id, poster = item.posterUrl,
            nextLabel = next?.let { "${it.code.replace(" · ", "")} · ${it.title}" }, next = next?.let { n -> { request(n, all) } },
        )
    }
    LazyColumn(Modifier.fillMaxSize().background(p.background).statusBarsPadding(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = p.textPrimary)
                }
                Eyebrow("${server.name} · Série")
                Text(item.title, style = Type.displaySerif(32), color = p.textPrimary)
                episodes?.let { list ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.map { it.season }.distinct().forEach { n -> Chip("Saison $n", n == season) { season = n } }
                    }
                }
            }
        }
        val list = episodes
        if (list == null) item { LoadingRow() }
        else items(list.filter { it.season == season }, key = { it.id }) { ep ->
            val progress = WatchStore.entry("server", "${server.kind}-${ep.id}".lowercase())
            Row(
                Modifier.fillMaxWidth().clickable { request(ep, list)?.let(Nav::play) }.padding(horizontal = Spacing.lg, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.width(140.dp).aspectRatio(16f / 9f).background(p.surface)) {
                    AsyncImage(ep.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (progress != null && progress.duration > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.progressFraction.toFloat()).height(3.dp).background(p.accent))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ep.code, style = Type.mono(10, FontWeight.SemiBold), color = p.accentText)
                    Text(ep.title, style = Type.title(15), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

internal fun serverOf(kind: MediaServerKind) = app.veltrio.shared.MediaServers.all.first { it.kind == kind }
