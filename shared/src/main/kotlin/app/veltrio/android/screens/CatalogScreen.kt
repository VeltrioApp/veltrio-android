package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.veltrio.android.CatalogRequest
import app.veltrio.android.Nav
import app.veltrio.android.ui.LoadingRow
import app.veltrio.android.ui.PageHeader
import app.veltrio.android.ui.PosterCard
import app.veltrio.shared.Core
import app.veltrio.shared.PosterItem
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.VeltrioTheme

/** "Tout voir": a catalog as a grid, more pages as it scrolls (Stremio's skip). */
@Composable
fun CatalogScreen(request: CatalogRequest) {
    val p = VeltrioTheme.palette
    var items by remember { mutableStateOf<List<PosterItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var exhausted by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= items.size - 6 } ?: false } }

    LaunchedEffect(Unit) {
        items = Core.loadCatalog(request.addon, request.catalog)
        loading = false
    }
    LaunchedEffect(nearEnd, items.size) {
        if (!nearEnd || loading || exhausted || items.isEmpty()) return@LaunchedEffect
        loading = true
        val page = Core.loadCatalogPage(request.addon, request.catalog, items.size).filter { n -> items.none { it.id == n.id } }
        if (page.isEmpty()) exhausted = true else items = items + page
        loading = false
    }
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour"), tint = p.textPrimary)
            }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageHeader("${request.addon.name} · ${Core.typeLabel(request.catalog.type)}", request.catalog.name, "${items.size} titres", Modifier.padding(bottom = 8.dp), inset = 0.dp)
            }
            items(items, key = { it.id }) { PosterCard(it.title, it.posterUrl, it.subtitle, width = 200.dp) { openPoster(it) } }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { LoadingRow() }
        }
    }
}
