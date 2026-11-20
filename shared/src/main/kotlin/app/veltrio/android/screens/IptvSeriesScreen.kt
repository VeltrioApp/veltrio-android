package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.Nav
import app.veltrio.android.ui.hero
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.LoadingRow
import app.veltrio.shared.Core
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.WatchStore
import app.veltrio.shared.iptv.ChannelItem
import app.veltrio.shared.iptv.ChannelKind
import app.veltrio.shared.iptv.PlaylistRef
import app.veltrio.shared.iptv.XtreamClient
import app.veltrio.shared.iptv.XtreamEpisode
import app.veltrio.shared.iptv.XtreamSeries
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage

/** An Xtream series: backdrop and plot, season chips, episodes with their
 *  progress; an episode plays from the provider, chained to the next. */
@Composable
fun IptvSeriesScreen(playlist: PlaylistRef, item: ChannelItem) {
    val p = VeltrioTheme.palette
    val account = playlist.xtream ?: return
    var series by remember { mutableStateOf<XtreamSeries?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }
    WatchStore.entries.collectAsState().value // redraw progress after playing
    LaunchedEffect(Unit) {
        try {
            val s = XtreamClient.seriesInfo(account, item.xtreamId!!)
            series = s
            season = s.episodes.firstOrNull()?.season
        } catch (e: Exception) {
            error = Core.humanize(e)
        }
    }

    fun request(ep: XtreamEpisode, all: List<XtreamEpisode>): PlayRequest {
        val next = all.getOrNull(all.indexOf(ep) + 1)
        val channel = ChannelItem(id = "xepisode-${ep.id}", name = item.name, streamUrl = ep.streamUrl, logo = item.logo, kind = ChannelKind.Movie)
        return PlayRequest.forChannel(channel, playlist).copy(
            subtitle = "${ep.code} — ${ep.title}", videoId = ep.id,
            nextLabel = next?.let { "${it.code.replace(" · ", "")} · ${it.title}" },
            next = next?.let { n -> { request(n, all) } },
        )
    }

    val s = series
    LazyColumn(Modifier.fillMaxSize().background(p.background), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Box(Modifier.hero(1.7f, 240.dp)) {
                AsyncImage(s?.backdrop ?: s?.cover ?: item.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to p.background.copy(alpha = 0.3f), 1f to p.background)))
                Box(Modifier.statusBarsPadding().padding(Spacing.md).size(40.dp).background(Color.Black.copy(alpha = 0.45f)).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour"), tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(tr("%s · Série", playlist.name))
                    Text(s?.name ?: item.name, style = Type.displaySerif(34), color = p.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(s?.rating?.let { "★ $it" }, s?.released?.take(4), s?.genre)
                    if (facts.isNotEmpty()) Text(facts.joinToString("   "), style = Type.mono(11), color = p.textSecondary)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                s?.plot?.let { Text(it, style = Type.body(14), color = p.textSecondary) }
                error?.let { Text(it, style = Type.body(13), color = p.danger) }
                if (s != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.episodes.map { it.season }.distinct().forEach { n -> Chip(tr("Saison %s", n), n == season) { season = n } }
                }
            }
        }
        if (s == null && error == null) item { LoadingRow() }
        if (s != null) items(s.episodes.filter { it.season == season }, key = { it.id }) { ep ->
            val progress = WatchStore.entry("iptv", "xepisode-${ep.id}")
            Row(
                Modifier.fillMaxWidth().clickable { Nav.play(request(ep, s.episodes)) }.padding(horizontal = Spacing.lg, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.width(140.dp).aspectRatio(16f / 9f).background(p.surface).border(1.dp, p.border)) {
                    AsyncImage(ep.image ?: s.cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (progress != null && progress.duration > 0) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.progressFraction.toFloat()).height(3.dp).background(p.accent))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ep.code, style = Type.mono(10, FontWeight.SemiBold), color = p.accentText)
                    Text(ep.title, style = Type.title(15), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ep.plot?.let { Text(it, style = Type.caption(11), color = p.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}
