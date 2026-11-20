package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.veltrio.android.LocalTv
import app.veltrio.android.Nav
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.PageHeader
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.shared.Core
import app.veltrio.shared.Pairing
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import kotlinx.coroutines.launch

/** Settings between devices on the same network: a TV waits with a code, a
 *  phone finds it and sends its sources, accounts, keys and Collection. */
@Composable
fun PairScreen() {
    val p = VeltrioTheme.palette
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Box(Modifier.padding(Spacing.md).size(40.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour"), tint = p.textPrimary)
        }
        if (LocalTv.current) Receive() else Send()
    }
}

@Composable
private fun Receive() {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    val receiver = remember { Pairing.Receiver(context) }
    DisposableEffect(Unit) {
        receiver.start()
        onDispose { receiver.stop() }
    }
    val state by receiver.state.collectAsState()
    PageHeader(tr("Autres appareils"), tr("Recevoir les réglages"), tr("Sur votre téléphone, ouvrez Veltrio, Réglages, « Envoyer vers une TV », choisissez cette TV (ou tapez son adresse) et ce code."))
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Eyebrow(tr("Code"))
        Text(state.code.chunked(3).joinToString(" "), style = Type.mono(72, FontWeight.Bold), color = p.accentText)
        Text(state.name, style = Type.mono(13), color = p.textTertiary)
        if (state.addresses.isNotEmpty()) Text("Adresse : ${state.addresses.joinToString("  ·  ")}", style = Type.mono(13), color = p.textTertiary)
        when {
            state.busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(tr("Réception et installation…"), style = Type.body(15), color = p.textPrimary)
            }
            state.result != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.result!!, style = Type.body(16), color = p.success)
                PrimaryButton(tr("Terminé")) { Nav.showTab(0) }
            }
            else -> Text(tr("En attente d'un téléphone sur le même réseau…"), style = Type.body(15), color = p.textSecondary)
        }
        state.error?.let { Text(it, style = Type.caption(13), color = p.danger) }
        Text(
            tr("Sont envoyés : les addons, les playlists IPTV, les services debrid, les comptes (Stremio, Trakt, SIMKL), les serveurs média, la Collection et les préférences de lecture. Rien de ce qui est déjà ici n'est retiré. Tout passe chiffré, avec une clé liée à ce code."),
            style = Type.caption(12), color = p.textTertiary, modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

@Composable
private fun Send() {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finder = remember { Pairing.Finder(context) }
    DisposableEffect(Unit) {
        finder.start()
        onDispose { finder.stop() }
    }
    val targets by finder.targets.collectAsState()
    var target by remember { mutableStateOf<Pairing.Target?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    PageHeader(tr("Autres appareils"), tr("Envoyer vers une TV"), tr("Sur la TV, ouvrez Veltrio, « Depuis un téléphone » (en bas du menu de gauche) : elle apparaît ici et affiche un code."))
    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(tr("Sur ce réseau"))
        if (targets.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = p.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(tr("Recherche d'une TV en attente…"), style = Type.body(14), color = p.textSecondary)
        }
        targets.forEach { t ->
            val selected = t == target
            Row(
                Modifier.fillMaxWidth().border(1.dp, if (selected) p.accent else p.border).clickable { target = t; result = null; error = null }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Tv, null, tint = if (selected) p.accent else p.textSecondary)
                Text(t.name, style = Type.title(15), color = p.textPrimary)
            }
        }
        var manual by remember { mutableStateOf("") }
        Text(tr("Pas dans la liste ? L'adresse affichée sur la TV :"), style = Type.caption(12), color = p.textTertiary)
        TextInput(manual, { v ->
            manual = v
            target = Pairing.manualTarget(v)
        }, "192.168.1.20:8765")
        target?.let { t ->
            Text(tr("Code affiché sur %s", t.name), style = Type.eyebrow(9, 0.18f), color = p.textTertiary)
            TextInput(code, { v -> code = v.filter(Char::isDigit).take(6) }, "000000", keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
            PrimaryButton(tr("Envoyer"), enabled = code.length == 6, loading = busy) {
                busy = true
                error = null
                scope.launch {
                    try {
                        result = Pairing.send(t, code)
                    } catch (e: Exception) {
                        error = e.message ?: Core.humanize(e)
                    }
                    busy = false
                }
            }
        }
        result?.let { Text(tr("Reçu par la TV : %s.", it), style = Type.body(14), color = p.success) }
        error?.let { Text(it, style = Type.caption(12), color = p.danger) }
        Text(
            tr("Sont envoyés : les addons, les playlists IPTV, les services debrid, les comptes, les serveurs média, la Collection et les préférences de lecture, chiffrés avec une clé liée au code de la TV."),
            style = Type.caption(12), color = p.textTertiary,
        )
        GhostButton(tr("Retour")) { Nav.back() }
    }
}
