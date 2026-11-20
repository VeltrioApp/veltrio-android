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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.veltrio.android.Nav
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.LoadingRow
import app.veltrio.android.ui.PosterCard
import app.veltrio.android.ui.SectionHeader
import app.veltrio.shared.CatalogRow
import app.veltrio.shared.Core
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.iptv.ChannelItem
import app.veltrio.shared.iptv.ChannelKind
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.iptv.PlaylistRef
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import kotlinx.coroutines.delay

/** Search across the addons' searchable catalogs and the IPTV sources, as
 *  macOS: a big serif field, then the matching channels and provider
 *  titles, and one row per catalog that answered. */
@Composable
fun SearchScreen() {
    val p = VeltrioTheme.palette
    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<CatalogRow>?>(null) }
    // IPTV channels, films and series matching, as macOS lists them under the addons.
    var channels by remember { mutableStateOf<List<Pair<PlaylistRef, ChannelItem>>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            rows = null
            channels = emptyList()
            return@LaunchedEffect
        }
        delay(350) // typing: wait for a pause
        searching = true
        val q = query.trim()
        coroutineScope {
            val iptv = async { IptvStore.search(q) }
            rows = Core.search(q)
            channels = iptv.await()
        }
        searching = false
    }
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour"), tint = p.textPrimary)
            }
        }
        Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(tr("Recherche"))
            Row(
                Modifier.fillMaxWidth().border(1.dp, if (query.isEmpty()) p.borderStrong else p.accent).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tr("On regarde quoi ?"), style = Type.displaySerif(26), color = p.textQuaternary)
                    BasicTextField(
                        query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = Type.displaySerif(26).copy(color = p.textPrimary), singleLine = true,
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(),
                    )
                }
                if (query.isNotEmpty()) Icon(Icons.Filled.Close, tr("Effacer"), Modifier.size(20.dp).clickable { query = "" }, tint = p.textTertiary)
            }
        }
        val current = rows
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = Spacing.lg, bottom = Spacing.xl)) {
            when {
                searching && current == null -> item { LoadingRow() }
                current == null -> item {
                    Text(tr("Tapez au moins deux lettres : films et séries de tous vos addons."), style = Type.body(13), color = p.textTertiary, modifier = Modifier.padding(Spacing.lg))
                }
                current.isEmpty() && channels.isEmpty() -> item {
                    Text(tr("Rien trouvé pour « %s ».", query.trim()), style = Type.body(14), color = p.textSecondary, modifier = Modifier.padding(Spacing.lg))
                }
                else -> {
                    if (channels.isNotEmpty()) item {
                        Column(Modifier.padding(bottom = Spacing.lg)) {
                            SectionHeader(null, tr("Direct"), tr("IPTV · %s", channels.size), Modifier.padding(horizontal = Spacing.lg))
                            Spacer(Modifier.height(12.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(channels, key = { (pl, c) -> pl.id + c.id }) { (pl, c) ->
                                    PosterCard(c.name, c.logo, listOf(pl.name, c.group).joinToString(" · "), logo = c.kind == ChannelKind.Live) {
                                        if (c.kind == ChannelKind.Series) Nav.openIptvSeries(pl, c) else Nav.play(PlayRequest.forChannel(c, pl))
                                    }
                                }
                            }
                        }
                    }
                    items(current) { row ->
                    Column(Modifier.padding(bottom = Spacing.lg)) {
                        SectionHeader(null, row.title, row.subtitle, Modifier.padding(horizontal = Spacing.lg))
                        Spacer(Modifier.height(12.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(row.items, key = { it.id }) { PosterCard(it.title, it.posterUrl, it.subtitle) { openPoster(it) } }
                        }
                    }
                    }
                }
            }
        }
    }
}
