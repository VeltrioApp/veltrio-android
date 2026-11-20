package app.veltrio.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.DetailRequest
import app.veltrio.android.Nav
import app.veltrio.android.CatalogRequest
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.PosterCard
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.android.ui.SectionHeader
import app.veltrio.shared.AddonInfo
import app.veltrio.shared.CatalogInfo
import app.veltrio.shared.Core
import app.veltrio.shared.PosterItem
import app.veltrio.shared.WatchEntry
import app.veltrio.shared.WatchStore
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

fun openPoster(item: PosterItem) =
    Nav.openTitle(DetailRequest(item.addon, item.type, item.id, item.title, item.posterUrl, item.backgroundUrl))

/** Reopens a library entry with the addon that surfaced it, or any
 *  metadata addon if that one is gone. False when none can. */
fun openEntry(entry: WatchEntry): Boolean {
    val addon = Core.metaAddon(entry.type, entry.addonId) ?: return false
    Nav.openTitle(DetailRequest(addon, entry.type, entry.contentId, entry.name, entry.poster, entry.background))
    return true
}

private class Row(val addon: AddonInfo, val catalog: CatalogInfo) {
    var items by mutableStateOf<List<PosterItem>?>(null)
    var error by mutableStateOf<String?>(null)
}

/** Home: an editorial hero, then Continue watching and one numbered row per
 *  catalog of the enabled addons — the macOS HomeView. */
@Composable
fun HomeScreen() {
    val p = VeltrioTheme.palette
    val addons by Core.addons.collectAsState()
    val disabled by Core.disabled.collectAsState()
    val entries by WatchStore.entries.collectAsState()
    val continueWatching = remember(entries) { WatchStore.continueWatching }
    val rows = remember(addons, disabled) { Core.homeCatalogs().map { (a, c) -> Row(a, c) } }

    if (Core.enabledAddons.isEmpty()) {
        EmptyHome()
        return
    }
    rows.forEach { row ->
        LaunchedEffect(row) {
            try {
                row.items = Core.loadCatalog(row.addon, row.catalog)
            } catch (e: Exception) {
                row.error = Core.humanize(e)
                row.items = emptyList()
            }
        }
    }
    // The first row with artwork gives the hero, as on macOS.
    val hero = rows.firstNotNullOfOrNull { r -> r.items?.firstOrNull { it.backgroundUrl != null } }
    val offset = if (continueWatching.isEmpty()) 1 else 2

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        // Its place is kept from the start, so the list doesn't jump when
        // the first row's artwork arrives.
        item(key = "hero") {
            if (hero != null) Hero(hero)
            else Box(Modifier.fillMaxWidth().aspectRatio(0.78f))
        }
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader(1, "Reprendre", "${continueWatching.size} en cours", Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueWatching, key = { it.key }) { ContinueCard(it) }
                }
            }
        }
        rows.forEachIndexed { index, row ->
            item(key = "${row.addon.id}|${row.catalog.type}|${row.catalog.id}") {
                Column(Modifier.padding(top = Spacing.xl)) {
                    SectionHeader(
                        offset + index, row.catalog.name,
                        "${row.addon.name} · ${Core.typeLabel(row.catalog.type)}",
                        Modifier.padding(horizontal = Spacing.lg),
                    ) {
                        if ((row.items?.size ?: 0) >= 10) {
                            Row(
                                Modifier.clickable { Nav.openCatalog(CatalogRequest(row.addon, row.catalog)) }.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("TOUT VOIR", style = Type.eyebrow(9, 0.18f), color = p.textPrimary)
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.width(12.dp), tint = p.textPrimary)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val items = row.items
                    when {
                        items == null -> Text("Chargement…", style = Type.caption(12), color = p.textTertiary, modifier = Modifier.padding(horizontal = Spacing.lg))
                        items.isEmpty() -> Text(row.error ?: "Rien dans ce catalogue.", style = Type.caption(12), color = p.textTertiary, modifier = Modifier.padding(horizontal = Spacing.lg))
                        else -> LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(items, key = { it.id }) { PosterCard(it.title, it.posterUrl, it.subtitle) { openPoster(it) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(item: PosterItem) {
    val p = VeltrioTheme.palette
    val started = WatchStore.entry(item.type, item.id)?.isStarted == true
    Box(Modifier.fillMaxWidth().aspectRatio(0.78f)) {
        AsyncImage(item.backgroundUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to p.background.copy(alpha = 0.35f), 1f to p.background)))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(28.dp).height(1.dp).background(p.accent))
                Eyebrow("À l'affiche")
                Eyebrow("· via ${item.addon.name}", p.textTertiary)
            }
            Text(item.title, style = Type.displaySerif(40), color = p.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(item.rating?.let { "★ $it" }, item.year, Core.typeLabel(item.type).uppercase(), item.genres.take(2).joinToString(" · ").ifEmpty { null })
            Text(facts.joinToString("   "), style = Type.mono(11), color = p.textSecondary)
            if (!item.description.isNullOrBlank()) {
                Text(item.description!!, style = Type.body(13), color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                PrimaryButton(if (started) "Reprendre" else "Regarder", icon = Icons.Filled.PlayArrow) { openPoster(item) }
                GhostButton("Détails", icon = Icons.Outlined.Info) { openPoster(item) }
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: WatchEntry) {
    val p = VeltrioTheme.palette
    Column(Modifier.width(240.dp).clickable { openEntry(entry) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(p.surface)) {
            AsyncImage(entry.background ?: entry.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
            if (entry.episodeCode.isNotEmpty()) {
                Text(entry.episodeCode, style = Type.mono(10, androidx.compose.ui.text.font.FontWeight.SemiBold), color = p.accent, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.08f)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(entry.progressFraction.toFloat()).height(3.dp).background(p.accent))
        }
        Text(entry.name, style = Type.title(14), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entry.resumeLabel, style = Type.mono(10), color = p.textTertiary)
    }
}

/** Nothing installed: the macOS empty state, with Cinemeta one tap away. */
@Composable
private fun EmptyHome() {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(Spacing.lg).padding(top = 60.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Eyebrow("Bienvenue")
        Text("Une pièce calme,\nrien à l'écran.", style = Type.displaySerif(34), color = p.textPrimary)
        Text("Veltrio affiche ce que proposent les addons Stremio que vous installez. Commencez par Cinemeta, les catalogues officiels de films et de séries.", style = Type.body(14), color = p.textSecondary)
        PrimaryButton("Installer Cinemeta", loading = busy) {
            busy = true
            scope.launch {
                error = Core.installAddon(Core.suggestedAddons[0].url)
                busy = false
            }
        }
        if (error != null) Text(error!!, style = Type.caption(12), color = p.danger)
    }
}
