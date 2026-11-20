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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Extension
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.Nav
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.LoadingRow
import app.veltrio.android.ui.PageHeader
import app.veltrio.shared.AddonCatalogService
import app.veltrio.shared.CatalogAddon
import app.veltrio.shared.Core
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** The addon catalogue, as macOS: Stremio's official collection or the
 *  community's (stremio-addons.net), searchable, one tap to install. */
@Composable
fun AddonCatalogScreen() {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    var community by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<CatalogAddon>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var loadingMore by remember { mutableStateOf(false) }
    val busy = remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(mapOf<String, String>()) }
    Core.addons.collectAsState().value

    LaunchedEffect(community) {
        items = null
        error = null
        try {
            items = if (community) {
                if (AddonCatalogService.communityPagesLoaded == 0) AddonCatalogService.loadMoreCommunity()
                AddonCatalogService.community
            } else AddonCatalogService.official()
        } catch (e: Exception) {
            error = tr("Impossible de charger le catalogue. %s", e.message)
            items = emptyList()
        }
    }

    fun install(addon: CatalogAddon) {
        busy.value = addon.id
        scope.launch {
            val url = runCatching { AddonCatalogService.resolveTransport(addon) }.getOrNull()
            val result = if (url == null) tr("Pas de manifest trouvé pour cet addon.") else Core.installAddon(url)
            messages = messages + (addon.id to (result ?: tr("Installé")))
            busy.value = null
        }
    }

    val text = filter.trim()
    val visible = items.orEmpty().filter { text.isEmpty() || it.name.contains(text, true) || (it.description ?: "").contains(text, true) }
    LazyColumn(Modifier.fillMaxSize().background(p.background).statusBarsPadding(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(Spacing.md).size(40.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour"), tint = p.textPrimary)
                }
                PageHeader(tr("Sources"), tr("Catalogue d'addons"), tr("Les addons officiels de Stremio, et ceux de la communauté."))
                Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(tr("Officiels"), !community) { community = false }
                    Chip(tr("Communauté"), community) { community = true }
                }
                TextInput(filter, { filter = it }, tr("Rechercher un addon"), Modifier.padding(horizontal = Spacing.lg))
            }
        }
        when {
            items == null -> item { LoadingRow() }
            error != null -> item { Text(error!!, style = Type.body(13), color = p.danger, modifier = Modifier.padding(Spacing.lg)) }
        }
        items(visible, key = { it.id }) { addon ->
            val installed = addon.transportUrl?.let { Core.isInstalled(it) } == true
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).background(p.surface), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Extension, null, Modifier.size(20.dp), tint = p.textQuaternary)
                        AsyncImage(addon.logo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(addon.name, style = Type.title(16), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(addon.capabilities, addon.typesLabel.uppercase()).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.eyebrow(9, 0.14f), color = p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        addon.description?.let { Text(it, style = Type.caption(12), color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        messages[addon.id]?.let { Text(it, style = Type.mono(11), color = if (it == tr("Installé")) p.success else p.danger) }
                    }
                    if (installed) Text(tr("INSTALLÉ"), style = Type.eyebrow(9, 0.2f), color = p.success, modifier = Modifier.align(Alignment.CenterVertically))
                    else GhostButton(if (busy.value == addon.id) "…" else tr("Ajouter"), enabled = busy.value == null) { install(addon) }
                }
                Box(Modifier.padding(horizontal = Spacing.lg).fillMaxWidth().height(1.dp).background(p.border))
            }
        }
        if (community && items != null && AddonCatalogService.communityPagesLoaded < AddonCatalogService.COMMUNITY_PAGE_COUNT) item {
            Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                GhostButton(if (loadingMore) tr("Chargement…") else tr("Charger plus"), enabled = !loadingMore) {
                    loadingMore = true
                    scope.launch {
                        runCatching { AddonCatalogService.loadMoreCommunity() }
                        items = AddonCatalogService.community
                        loadingMore = false
                    }
                }
            }
        }
    }
}
