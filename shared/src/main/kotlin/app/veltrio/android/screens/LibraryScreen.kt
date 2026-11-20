package app.veltrio.android.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.PageHeader
import app.veltrio.android.ui.PosterCard
import app.veltrio.shared.WatchEntry
import app.veltrio.shared.MediaLibraryItem
import app.veltrio.shared.MediaLibrarySection
import app.veltrio.shared.MediaServer
import app.veltrio.shared.MediaServers
import app.veltrio.android.Nav
import app.veltrio.android.ui.LoadingRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import app.veltrio.shared.WatchStore
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme

private val filters = listOf("all" to "Tout", "progress" to "En cours", "saved" to "Enregistrés", "watched" to "Vus")
private val types = listOf("all" to "Tous les types", "movie" to "Films", "series" to "Séries")

/** The Collection: saved titles and everything started, with the macOS
 *  filters; a long press marks watched or removes. (Media server tabs come
 *  with the servers lot.) */
@Composable
fun LibraryScreen() {
    MediaServers.version.collectAsState().value
    val servers = MediaServers.connected
    val kind = Nav.libraryServer?.takeIf { k -> servers.any { it.kind == k } }
    Column(Modifier.fillMaxSize()) {
        if (servers.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Enregistrés", kind == null) { Nav.libraryServer = null }
                servers.forEach { s -> Chip(s.name, s.kind == kind) { Nav.libraryServer = s.kind } }
            }
        }
        if (kind != null) ServerLibrary(serverOf(kind)) else SavedLibrary()
    }
}

/** A media server's libraries: section chips, then its films and series,
 *  50 at a time. */
@Composable
private fun ServerLibrary(server: MediaServer) {
    val p = VeltrioTheme.palette
    var sections by remember(server) { mutableStateOf<List<MediaLibrarySection>?>(null) }
    var section by remember(server) { mutableStateOf<MediaLibrarySection?>(null) }
    var items by remember(server, section) { mutableStateOf<List<MediaLibraryItem>>(emptyList()) }
    var total by remember(server, section) { mutableStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state by server.state.collectAsState()
    LaunchedEffect(server) {
        sections = server.sections()
        section = sections?.firstOrNull()
    }
    fun loadMore() {
        val sec = section ?: return
        if (loading) return
        loading = true
        scope.launch {
            val page = server.items(sec, items.size, 50)
            if (sec == section) {
                items = items + page.items
                total = page.total
            }
            loading = false
        }
    }
    LaunchedEffect(section) { if (section != null && items.isEmpty()) loadMore() }
    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageHeader("${server.name} · ${state.user ?: ""}", section?.name ?: server.name, if (total >= 0) "$total titres" else null, inset = 0.dp)
                sections?.takeIf { it.size > 1 }?.let { list ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { s -> Chip(s.name, s == section) { section = s } }
                    }
                }
            }
        }
        when {
            sections == null || (items.isEmpty() && loading) -> item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
            sections!!.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Ce serveur n'a pas de bibliothèque de films ou de séries, ou ne répond pas.", style = Type.body(14), color = p.textSecondary)
            }
            items.isEmpty() && total == 0 -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Cette bibliothèque ne contient aucun film ni aucune série.", style = Type.body(14), color = p.textSecondary)
            }
        }
        items(items, key = { it.id }) { item -> PosterCard(item.title, item.posterUrl, item.subtitle, width = 200.dp) { openServerItem(server, item) } }
        if (items.isNotEmpty() && items.size < total) item(span = { GridItemSpan(maxLineSpan) }) {
            LaunchedEffect(items.size) { loadMore() }
            LoadingRow()
        }
    }
}

@Composable
private fun SavedLibrary() {
    val p = VeltrioTheme.palette
    val entries by WatchStore.entries.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    var type by rememberSaveable { mutableStateOf("all") }
    val all = remember(entries) { WatchStore.libraryItems }
    val shown = all.filter { type == "all" || it.type == type }.filter {
        when (filter) {
            "progress" -> it.isStarted && !it.watched
            "saved" -> it.inLibrary
            "watched" -> it.watched
            else -> true
        }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(108.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageHeader("N° ${all.size} dans votre collection", "Collection", "${WatchStore.continueWatching.size} en cours · choisis par vous", inset = 0.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    filters.forEach { (key, label) -> Chip(label, key == filter) { filter = key } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    types.forEach { (key, label) -> Chip(label, key == type) { type = key } }
                }
            }
        }
        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (all.isEmpty()) "Rien d'enregistré,\npour l'instant." else "Rien ne correspond.", style = Type.displaySerif(30), color = p.textPrimary)
                    Text(
                        if (all.isEmpty()) "Enregistrez un titre depuis sa fiche : il arrive ici, avec tout ce que vous avez commencé à regarder."
                        else "Aucun titre ne correspond à ces filtres.",
                        style = Type.body(14), color = p.textSecondary,
                    )
                }
            }
        }
        items(shown, key = { it.key }) { LibraryCard(it) }
    }
}

@Composable
private fun LibraryCard(entry: WatchEntry) {
    var menu by remember { mutableStateOf(false) }
    Box {
        PosterCard(
            entry.name, entry.poster, entry.statusLabel, width = 200.dp,
            progress = entry.progressFraction.toFloat().takeIf { entry.isStarted && !entry.watched },
            onLongClick = { menu = true },
        ) { openEntry(entry) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (entry.watched) "Marquer comme non vu" else "Marquer comme vu") }, {
                WatchStore.markWatched(entry.type, entry.contentId, !entry.watched)
                menu = false
            })
            if (entry.inLibrary) DropdownMenuItem({ Text("Retirer de la collection") }, {
                WatchStore.toggleLibrary(entry.type, entry.contentId, entry.name, null, null, null)
                menu = false
            })
            DropdownMenuItem({ Text("Supprimer de l'historique") }, {
                WatchStore.remove(entry.type, entry.contentId)
                menu = false
            })
        }
    }
}
