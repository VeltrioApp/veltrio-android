package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.PageHeader
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.android.ui.SectionHeader
import app.veltrio.shared.AddonInfo
import app.veltrio.shared.Core
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Sources: install from URL, installed addons (enable, reorder, remove),
 *  suggestions — the macOS SourcesView. (The addon catalogue, IPTV and
 *  media servers join with their lots.) */
@Composable
fun SourcesScreen() {
    val p = VeltrioTheme.palette
    val addons by Core.addons.collectAsState()
    val disabled by Core.disabled.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val playlists by IptvStore.playlists.collectAsState()
    var addingPlaylist by remember { mutableStateOf(false) }
    if (addingPlaylist) AddPlaylistDialog { addingPlaylist = false }

    fun install(target: String) {
        installing = true
        error = null
        scope.launch {
            error = Core.installAddon(target)
            if (error == null) url = ""
            installing = false
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.xl)) {
        item { PageHeader(tr("Sources"), tr("Sources"), tr("Tous les catalogues, serveurs et résolveurs de flux qui alimentent l'app.")) }
        item {
            Box(Modifier.padding(start = Spacing.lg, top = Spacing.md)) {
                PrimaryButton(tr("Parcourir le catalogue d'addons"), icon = Icons.Outlined.Extension) { app.veltrio.android.Nav.addonCatalog() }
            }
        }
        item {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(1, tr("Installer depuis une URL"))
                Text(tr("Collez l'URL du manifest d'un addon Stremio (…/manifest.json ou un lien stremio://)."), style = Type.body(13), color = p.textSecondary)
                app.veltrio.android.ui.RemoteTextField(Modifier.fillMaxWidth().border(1.dp, p.borderStrong).padding(12.dp)) { fieldModifier ->
                    if (url.isEmpty()) Text("https://…/manifest.json", style = Type.mono(13), color = p.textQuaternary)
                    BasicTextField(
                        url, { url = it }, fieldModifier.fillMaxWidth(), singleLine = true,
                        textStyle = Type.mono(13).copy(color = p.textPrimary), cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                }
                PrimaryButton(if (installing) tr("Installation…") else tr("Installer"), Modifier.fillMaxWidth(), enabled = url.isNotBlank(), loading = installing) { install(url) }
                if (error != null) Text(error!!, style = Type.caption(12), color = p.danger)
            }
        }
        item {
            SectionHeader(2, tr("Addons installés"), tr("%s addon%s", addons.size, if (addons.size > 1) "s" else ""), Modifier.padding(horizontal = Spacing.lg))
            if (addons.isEmpty()) Text(tr("Aucun addon pour l'instant. Commencez par Cinemeta, juste en dessous."), style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg))
        }
        items(addons, key = { it.id }) { addon ->
            AddonRow(addon, enabled = addon.id !in disabled, index = addons.indexOf(addon), count = addons.size)
        }
        item {
            SectionHeader(3, tr("Playlists IPTV"), tr("%s source%s", playlists.size, if (playlists.size > 1) "s" else ""), Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xl)) {
                GhostButton(tr("Ajouter"), icon = Icons.Filled.Add) { addingPlaylist = true }
            }
            if (playlists.isEmpty()) Text(tr("Une playlist M3U ou un compte Xtream Codes alimente l'onglet Direct."), style = Type.body(13), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg))
        }
        items(playlists, key = { "pl-" + it.id }) { pl ->
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pl.name, style = Type.title(16), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(pl.sourceLabel, style = Type.mono(10), color = p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(40.dp).clickable { IptvStore.remove(pl) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Delete, tr("Supprimer"), tint = p.danger)
                    }
                }
                Box(Modifier.padding(horizontal = Spacing.lg).fillMaxWidth().height(1.dp).background(p.border))
            }
        }
        item {
            Column(Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(4, tr("Serveurs média"), tr("Jellyfin · Emby · Plex"))
                Text(tr("Votre bibliothèque apparaît dans la Collection, et ses copies en tête des flux d'un titre."), style = Type.body(13), color = p.textSecondary)
                app.veltrio.shared.MediaServers.all.forEach { MediaServerCard(it) }
            }
        }
        item { SectionHeader(5, tr("Suggérés"), modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xl)) }
        items(Core.suggestedAddons) { s ->
            val installed = Core.isInstalled(s.url)
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, style = Type.title(16), color = p.textPrimary)
                    Text(s.blurb, style = Type.caption(12), color = p.textSecondary)
                }
                if (installed) Text(tr("INSTALLÉ"), style = Type.eyebrow(9, 0.2f), color = p.success)
                else GhostButton(tr("Ajouter"), enabled = !installing) { install(s.url) }
            }
        }
    }
}

@Composable
private fun AddonRow(addon: AddonInfo, enabled: Boolean, index: Int, count: Int) {
    val p = VeltrioTheme.palette
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(p.surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(20.dp), tint = p.textQuaternary)
                AsyncImage(addon.logo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(addon.name, style = Type.title(16), color = if (enabled) p.textPrimary else p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "V${addon.version} · ${addon.types.joinToString(" · ") { Core.typeLabel(it, plural = true) }}".uppercase(),
                    style = Type.eyebrow(9, 0.14f), color = p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                addon.description?.let { Text(it, style = Type.caption(12), color = p.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Switch(
                        enabled, { Core.setEnabled(addon, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
                    )
                    Box(Modifier.size(40.dp).clickable(enabled = index > 0) { Core.move(addon, -1) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.KeyboardArrowUp, tr("Monter"), tint = if (index > 0) p.textSecondary else p.textQuaternary)
                    }
                    Box(Modifier.size(40.dp).clickable(enabled = index < count - 1) { Core.move(addon, 1) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.KeyboardArrowDown, tr("Descendre"), tint = if (index < count - 1) p.textSecondary else p.textQuaternary)
                    }
                    Box(Modifier.size(40.dp).clickable { Core.removeAddon(addon) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Delete, tr("Supprimer"), tint = p.danger)
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = Spacing.lg).fillMaxWidth().height(1.dp).background(p.border))
    }
}
