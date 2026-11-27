package app.veltrio.android.screens

import app.veltrio.shared.i18n.tr
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.veltrio.android.Nav
import app.veltrio.android.ui.Chip
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.PageHeader
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.android.ui.SectionHeader
import app.veltrio.shared.AccountState
import app.veltrio.shared.AppPrefs
import app.veltrio.shared.SimklAccount
import app.veltrio.shared.StremioAccount
import app.veltrio.shared.TraktAccount
import app.veltrio.shared.AppTheme
import app.veltrio.shared.Core
import app.veltrio.shared.DebridAccount
import app.veltrio.shared.DebridService
import app.veltrio.shared.DownloadManager
import app.veltrio.shared.DownloadStatus
import app.veltrio.shared.PlayerPrefs
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings, the macOS / Windows sections in a column: Sources, Debrid,
 *  Accounts, Playback, Appearance, Storage, About. */
@Composable
fun SettingsScreen() {
    val p = VeltrioTheme.palette
    Column(Modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour"), tint = p.textPrimary)
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            PageHeader(tr("Préférences"), tr("Réglages"))
            Section(1, tr("Sources")) {
                Body(tr("Les addons Stremio et les playlists IPTV se gèrent dans l'onglet Sources."))
                GhostButton(tr("Ouvrir les sources")) { Nav.showTab(3) }
            }
            Section(2, tr("Debrid")) {
                Body(tr("Un service debrid lit les torrents en direct depuis ses serveurs, en HTTPS, sans partage pair à pair. Les flux qu'il a déjà en cache sont marqués ⚡ et passent en tête. Les clés restent sur cet appareil, chiffrées par Android."))
                val accounts by DebridService.accounts.collectAsState()
                accounts.values.forEach { DebridCard(it) }
            }
            Section(3, tr("Comptes")) {
                Body(tr("Importez votre bibliothèque dans la Collection et suivez ce que vous regardez. L'import ne fait qu'ajouter : il ne retire rien et ne remet aucune progression à zéro."))
                AccountsSection()
                TmdbCard()
            }
            Section(4, tr("Lecture")) { PlaybackSection() }
            Section(5, tr("Apparence")) {
                Body(tr("Langue de l'application (synchronise aussi les catalogues TMDB)."))
                val currentLang = app.veltrio.shared.i18n.Lang.code
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPrefs.appLanguages.forEach { (code, label) ->
                        Chip(label, currentLang == code) {
                            AppPrefs.update { it.copy(appLanguage = code) }
                            app.veltrio.shared.TmdbService.setLanguage(app.veltrio.shared.i18n.Lang.tmdbLanguage(code))
                        }
                    }
                }
                Body(tr("Sombre : le noir de salle de cinéma, l'expérience de référence. Clair : un papier chaud pour la journée."))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { t ->
                        Chip(t.label, Nav.theme == t) {
                            AppPrefs.update { it.copy(theme = t) }
                            Nav.theme = t
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(p.hairline))
                ParentalControlSection()
            }
            Section(6, tr("Stockage")) { StorageSection() }
            Section(7, tr("Autres appareils")) {
                if (app.veltrio.android.LocalTv.current) {
                    Body(tr("Plutôt que de tout reconfigurer à la télécommande : recevez les sources, comptes et la Collection d'un téléphone où Veltrio est installé, sur le même réseau."))
                    PrimaryButton(tr("Recevoir les réglages d'un téléphone")) { Nav.pair() }
                } else {
                    Body(tr("Configurez une TV (Android TV, Google TV) en un geste : vos sources, comptes et votre Collection, envoyés par le réseau local."))
                    PrimaryButton(tr("Envoyer vers une TV")) { Nav.pair() }
                }
            }
            Section(8, tr("À propos")) {
                val context = LocalContext.current
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                Text(tr("Veltrio %s", version), style = Type.title(18), color = p.textPrimary)
                UpdateRow()
                GhostButton(tr("Afficher l'assistant de bienvenue")) {
                    app.veltrio.shared.AppPrefs.update { it.copy(onboardingDone = false) }
                    Nav.controller?.navigate("onboarding")
                }
                Body(tr("Veltrio ne fournit aucun contenu : il affiche ce que renvoient les sources que vous installez."))
                Body(tr("Lecture par libmpv (LGPL 2.1+, mpv.io) et FFmpeg (LGPL 2.1+)."))
            }
        }
    }
}

@Composable
private fun ParentalControlSection() {
    val p = VeltrioTheme.palette
    var kidsMode by remember { mutableStateOf(AppPrefs.current.kidsMode) }
    var savedPin by remember { mutableStateOf(AppPrefs.current.kidsPin) }
    var pinInput by remember { mutableStateOf("") }
    var unlocking by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }

    fun applyKidsMode(enabled: Boolean) {
        kidsMode = enabled
        AppPrefs.update { it.copy(kidsMode = enabled) }
        Nav.kidsMode = enabled
        Core.clearCatalogCache()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(tr("Contrôle parental"), p.accentText)
        Body(tr("Le Mode Enfants filtre les catalogues TMDB vers les films et séries familiaux et masque les bouquets IPTV adultes."))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Mode Enfants"), style = Type.label(14, FontWeight.SemiBold), color = p.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = kidsMode,
                onCheckedChange = { wantOn ->
                    pinError = null
                    if (wantOn) {
                        unlocking = false
                        applyKidsMode(true)
                    } else if (savedPin.isNotEmpty()) {
                        unlocking = true
                        pinInput = ""
                    } else {
                        applyKidsMode(false)
                    }
                },
                colors = SwitchDefaults.colors(checkedThumbColor = p.accent, checkedTrackColor = p.accent.copy(alpha = 0.35f)),
            )
        }
        if (unlocking) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter(Char::isDigit).take(4); pinError = null },
                    singleLine = true,
                    textStyle = Type.mono(15, FontWeight.Bold).copy(color = p.textPrimary),
                    cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.width(120.dp).background(p.surface).border(1.dp, p.border).padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (pinInput.isEmpty()) Text(tr("Code PIN"), style = Type.caption(12), color = p.textQuaternary)
                        inner()
                    },
                )
                PrimaryButton(tr("Déverrouiller")) {
                    if (pinInput == savedPin) {
                        unlocking = false
                        pinInput = ""
                        pinError = null
                        applyKidsMode(false)
                    } else {
                        pinError = tr("Code PIN incorrect.")
                    }
                }
                GhostButton(tr("Annuler")) { unlocking = false; pinInput = ""; pinError = null }
            }
        } else if (!kidsMode || savedPin.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it.filter(Char::isDigit).take(4); pinError = null },
                    singleLine = true,
                    textStyle = Type.mono(14).copy(color = p.textPrimary),
                    cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.width(140.dp).background(p.surface).border(1.dp, p.border).padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (pinInput.isEmpty()) {
                            Text(
                                if (savedPin.isEmpty()) tr("Code PIN (4 chiffres)") else tr("PIN actif (••••)"),
                                style = Type.caption(11),
                                color = p.textQuaternary,
                            )
                        }
                        inner()
                    },
                )
                GhostButton(tr("Définir le PIN")) {
                    if (pinInput.length == 4) {
                        savedPin = pinInput
                        AppPrefs.update { it.copy(kidsPin = savedPin) }
                        pinInput = ""
                        pinError = null
                    } else {
                        pinError = tr("Saisissez 4 chiffres.")
                    }
                }
                if (savedPin.isNotEmpty() && !kidsMode) {
                    GhostButton(tr("Retirer")) {
                        savedPin = ""
                        AppPrefs.update { it.copy(kidsPin = "") }
                        pinInput = ""
                    }
                }
            }
        }
        pinError?.let { Text(it, style = Type.caption(11), color = p.danger) }
    }
}

@Composable
private fun Section(number: Int, title: String, content: @Composable () -> Unit) {
    val p = VeltrioTheme.palette
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The page header already draws the first rule.
        if (number > 1) Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        SectionHeader(number, title)
        content()
    }
}

@Composable
private fun Body(text: String) = Text(text, style = Type.body(13), color = VeltrioTheme.palette.textSecondary)

@Composable
internal fun TextInput(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, secret: Boolean = false,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    val p = VeltrioTheme.palette
    app.veltrio.android.ui.RemoteTextField(
        modifier.fillMaxWidth().border(1.dp, p.borderStrong, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
    ) { fieldModifier ->
        if (value.isEmpty()) Text(placeholder, style = Type.mono(13), color = p.textQuaternary)
        BasicTextField(
            value, onChange, fieldModifier.fillMaxWidth(), singleLine = true,
            textStyle = Type.mono(13).copy(color = p.textPrimary), cursorBrush = SolidColor(p.accent),
            keyboardOptions = keyboardOptions,
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
    }
}

internal fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun DebridCard(account: DebridAccount) {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var key by remember { mutableStateOf("") }
    val provider = account.provider
    Column(Modifier.fillMaxWidth().border(1.dp, p.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(provider.color)))
            Text(provider.label, style = Type.title(16), color = p.textPrimary, modifier = Modifier.weight(1f))
            if (account.isConnected) Text(tr("CONNECTÉ"), style = Type.eyebrow(9, 0.2f), color = p.success)
        }
        when {
            account.isConnected -> {
                Text(
                    listOfNotNull(account.user?.ifEmpty { null }, account.premium?.let { if (it) tr("Premium") else tr("Compte gratuit") }).joinToString(" · ").ifEmpty { tr("Connecté") },
                    style = Type.mono(12), color = p.textSecondary,
                )
                GhostButton(tr("Déconnecter"), color = p.danger) { DebridService.disconnect(provider) }
            }
            account.pendingCode != null -> {
                Body(tr("Ouvrez %s et saisissez ce code :", account.pendingUrl))
                Text(account.pendingCode ?: "", style = Type.mono(28, FontWeight.Bold), color = p.accentText,
                    modifier = Modifier.clickable { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("code", account.pendingCode).toClipEntry()) } })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(tr("Ouvrir la page"), icon = Icons.AutoMirrored.Filled.OpenInNew) { openUrl(context, account.pendingUrl ?: provider.keyPage) }
                    GhostButton(tr("Annuler")) { DebridService.cancelCodeFlow() }
                }
                Text(tr("En attente de la validation…"), style = Type.caption(12), color = p.textTertiary)
            }
            provider.usesCode -> {
                Body(provider.tagline)
                PrimaryButton(tr("Se connecter"), loading = account.busy) { DebridService.startCodeFlow(provider) }
            }
            else -> {
                Body(provider.tagline)
                TextInput(key, { key = it }, tr("Clé d'API"), secret = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(tr("Connecter"), enabled = key.isNotBlank(), loading = account.busy) {
                        scope.launch { DebridService.connectWithKey(provider, key) }
                    }
                    GhostButton(tr("Obtenir la clé"), icon = Icons.AutoMirrored.Filled.OpenInNew) { openUrl(context, provider.keyPage) }
                }
            }
        }
        account.error?.let { Text(it, style = Type.caption(12), color = p.danger) }
    }
}

@Composable
private fun PlaybackSection() {
    val p = VeltrioTheme.palette
    var prefs by remember { mutableStateOf(PlayerPrefs.current) }
    fun set(change: (PlayerPrefs) -> PlayerPrefs) {
        PlayerPrefs.update(change)
        prefs = PlayerPrefs.current
    }
    Body(tr("Le lecteur s'appuie sur mpv, comme sur macOS : MKV, HEVC, HDR, pistes audio et sous-titres intégrés, décodage matériel quand l'appareil le permet. Ces choix valent pour la prochaine vidéo ouverte."))
    Picker(tr("Langue audio préférée"), PlayerPrefs.languages, prefs.audioLang) { v -> set { it.copy(audioLang = v) } }
    Picker(tr("Sous-titres préférés"), PlayerPrefs.languages, prefs.subtitleLang) { v -> set { it.copy(subtitleLang = v) } }
    Picker(tr("Taille des sous-titres"), PlayerPrefs.subtitleScales, prefs.subtitleScale) { v -> set { it.copy(subtitleScale = v) } }
    Picker(tr("Couleur des sous-titres"), PlayerPrefs.subtitleColors, prefs.subtitleColor) { v -> set { it.copy(subtitleColor = v) } }
    Picker(tr("Décodage vidéo"), PlayerPrefs.decoders, prefs.hwdec) { v -> set { it.copy(hwdec = v) } }
    Picker(tr("Mémoire tampon (flux réseau)"), PlayerPrefs.cacheSizes.map { it to tr("%s Mo", it) }, prefs.cacheMiB) { v -> set { it.copy(cacheMiB = v) } }
    Picker(tr("Profil de lecture"), app.veltrio.shared.PlaybackProfile.entries.map { it to it.label }, prefs.profile) { v -> set { it.copy(profile = v) } }
    Text(prefs.profile.blurb, style = Type.caption(12), color = p.textTertiary)
    Picker(tr("Tone mapping HDR (écran SDR)"), PlayerPrefs.toneMappings, prefs.toneMapping) { v -> set { it.copy(toneMapping = v) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Enchaîner l'épisode suivant (compte à rebours de 10 s en fin d'épisode)"), style = Type.body(14), color = p.textPrimary, modifier = Modifier.weight(1f))
        Switch(prefs.autoNextEpisode, { v -> set { it.copy(autoNextEpisode = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent))
    }
    Text(
        tr("Des sous-titres d'addons dans la langue choisie s'ajoutent quand le fichier n'en a pas et que l'audio n'est pas déjà dans cette langue. Gestes : toucher pour afficher les commandes, double toucher à gauche ou à droite pour reculer ou avancer de 10 s, au centre pour pause."),
        style = Type.caption(12), color = p.textTertiary,
    )
}

@Composable
internal fun <T> Picker(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val p = VeltrioTheme.palette
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = Type.eyebrow(9, 0.18f), color = p.textTertiary)
        Box {
            Row(
                Modifier.fillMaxWidth().border(1.dp, p.borderStrong).clickable { open = true }.padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "—", style = Type.body(14), color = p.textPrimary, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null, tint = p.textSecondary)
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem({ Text(text, fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal) }, {
                        onSelect(value)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun StorageSection() {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { Core.torrentCacheSize() } }
    Body(tr("Addons, playlists, comptes, collection et progression sont enregistrés sur cet appareil uniquement."))
    DownloadsSection()
    Body(tr("Les torrents lus sans debrid sont téléchargés dans le cache de l'app, gardé pour reprendre plus vite. Le vider libère la place ; un torrent en cours de lecture n'est pas touché."))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GhostButton(tr("Vider le cache des torrents")) {
            scope.launch {
                cacheSize = withContext(Dispatchers.IO) {
                    Core.clearTorrentCache()
                    Core.torrentCacheSize()
                }
            }
        }
        cacheSize?.let { Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, it), style = Type.mono(12), color = p.textTertiary) }
    }
}

/** Offline copies: progress, pause / resume, play, delete. */
@Composable
private fun DownloadsSection() {
    val p = VeltrioTheme.palette
    val items by DownloadManager.items.collectAsState()
    Text(tr("TÉLÉCHARGEMENTS"), style = Type.eyebrow(9, 0.18f), color = p.textTertiary)
    if (items.isEmpty()) {
        Body(tr("Aucun téléchargement. Appui long sur un flux, dans la fiche d'un titre, puis « Télécharger pour regarder hors ligne »."))
        return
    }
    items.sortedByDescending { it.createdAt }.forEach { d ->
        Column(Modifier.fillMaxWidth().border(1.dp, p.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.title, style = Type.title(15), color = p.textPrimary)
            d.subtitle?.let { Text(it, style = Type.caption(11), color = p.textTertiary) }
            Box(Modifier.fillMaxWidth().height(3.dp).background(p.hairline)) {
                Box(Modifier.fillMaxWidth(d.progress.toFloat()).height(3.dp).background(if (d.status == DownloadStatus.Failed) p.danger else p.accent))
            }
            val ctx = LocalContext.current
            Text(
                when (d.status) {
                    DownloadStatus.Downloading -> "${(d.progress * 100).toInt()} % · ${android.text.format.Formatter.formatShortFileSize(ctx, d.bytesDownloaded)}"
                    DownloadStatus.Paused -> tr("En pause · %s %%", (d.progress * 100).toInt())
                    DownloadStatus.Completed -> tr("Prêt · %s", android.text.format.Formatter.formatShortFileSize(ctx, d.bytesDownloaded))
                    DownloadStatus.Failed -> d.error ?: tr("Échec")
                },
                style = Type.mono(11), color = if (d.status == DownloadStatus.Failed) p.danger else p.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (d.status) {
                    DownloadStatus.Downloading -> GhostButton(tr("Pause")) { DownloadManager.pause(d.id) }
                    DownloadStatus.Paused, DownloadStatus.Failed -> GhostButton(tr("Reprendre")) { DownloadManager.resume(d.id) }
                    DownloadStatus.Completed -> PrimaryButton(tr("Lire")) {
                        Nav.play(
                            app.veltrio.shared.PlayRequest(
                                title = d.title, subtitle = d.subtitle, url = d.localPath, contentType = d.contentType, contentId = d.contentId,
                                videoId = d.id.takeIf { d.season != null }, season = d.season, episode = d.episode, poster = d.poster,
                            ),
                        )
                    }
                }
                GhostButton(tr("Supprimer"), color = p.danger) { DownloadManager.remove(d.id) }
            }
        }
    }
}

@Composable
private fun AccountsSection() {
    StremioCard()
    val trakt by TraktAccount.state.collectAsState()
    CodeAccountCard(
        tr("Trakt"), 0xFFED1C24, trakt,
        tr("Suit ce que vous regardez (scrobbling) et importe votre watchlist et votre historique."),
        onConnect = TraktAccount::startDeviceAuth, onCancel = TraktAccount::cancelFlow,
        onImport = TraktAccount::importLibrary, onDisconnect = TraktAccount::disconnect,
    )
    val simkl by SimklAccount.state.collectAsState()
    CodeAccountCard(
        "SIMKL", 0xFF0B0F10, simkl,
        tr("Suit ce que vous regardez et importe vos listes (à voir, en cours, terminé)."),
        onConnect = SimklAccount::startPinFlow, onCancel = SimklAccount::cancelFlow,
        onImport = SimklAccount::importLibrary, onDisconnect = SimklAccount::disconnect,
    )
}

@Composable
private fun AccountCard(name: String, color: Long, connected: Boolean, content: @Composable () -> Unit) {
    val p = VeltrioTheme.palette
    Column(Modifier.fillMaxWidth().border(1.dp, p.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(color)).border(1.dp, p.border, CircleShape))
            Text(name, style = Type.title(16), color = p.textPrimary, modifier = Modifier.weight(1f))
            if (connected) Text(tr("CONNECTÉ"), style = Type.eyebrow(9, 0.2f), color = p.success)
        }
        content()
    }
}

@Composable
private fun AccountFooter(state: AccountState) {
    val p = VeltrioTheme.palette
    state.lastResult?.let { Text(it, style = Type.mono(11), color = p.textTertiary) }
    state.addonResult?.let { Text(it, style = Type.mono(11), color = p.textTertiary) }
    state.error?.let { Text(it, style = Type.caption(12), color = p.danger) }
}

@Composable
private fun StremioCard() {
    val state by StremioAccount.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AccountCard(tr("Stremio"), 0xFF7B5BF5, state.connected) {
        if (state.connected) {
            Text(state.user ?: "", style = Type.mono(12), color = VeltrioTheme.palette.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(tr("Importer la bibliothèque"), loading = state.importing) { StremioAccount.importLibrary() }
                GhostButton(tr("Installer ses addons"), enabled = !state.busy) { StremioAccount.installAccountAddons() }
                GhostButton(tr("Déconnecter"), color = VeltrioTheme.palette.danger) { StremioAccount.signOut() }
            }
        } else {
            Body(tr("Importe les titres enregistrés, ce que vous avez vu, et vos addons. Rien n'est renvoyé à Stremio."))
            TextInput(email, { email = it }, "E-mail")
            TextInput(password, { password = it }, tr("Mot de passe"), secret = true)
            PrimaryButton(tr("Se connecter"), enabled = email.isNotBlank() && password.isNotEmpty(), loading = state.busy) {
                StremioAccount.signIn(email.trim(), password)
            }
        }
        AccountFooter(state)
    }
}

@Composable
private fun CodeAccountCard(
    name: String, color: Long, state: AccountState, blurb: String,
    onConnect: () -> Unit, onCancel: () -> Unit, onImport: () -> Unit, onDisconnect: () -> Unit,
) {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    AccountCard(name, color, state.connected) {
        when {
            !state.available -> Body(tr("Indisponible dans cette version de l'app."))
            state.connected -> {
                state.user?.let { Text(it, style = Type.mono(12), color = p.textSecondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(tr("Importer"), loading = state.importing, onClick = onImport)
                    GhostButton(tr("Déconnecter"), color = p.danger, onClick = onDisconnect)
                }
            }
            state.pendingCode != null -> {
                Body(tr("Ouvrez %s et saisissez ce code :", state.pendingUrl))
                Text(state.pendingCode ?: "", style = Type.mono(28, FontWeight.Bold), color = p.accentText)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(tr("Ouvrir la page"), icon = Icons.AutoMirrored.Filled.OpenInNew) { openUrl(context, state.pendingUrl ?: "") }
                    GhostButton(tr("Annuler"), onClick = onCancel)
                }
            }
            else -> {
                Body(blurb)
                PrimaryButton(tr("Se connecter"), loading = state.busy, onClick = onConnect)
            }
        }
        AccountFooter(state)
    }
}

/** Updates from the GitHub releases (see [app.veltrio.shared.Updater]). */
@Composable
private fun UpdateRow() {
    val p = VeltrioTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.veltrio.shared.Updater.state.collectAsState()
    val step = state.step
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        when (step) {
            app.veltrio.shared.Updater.Step.Available -> {
                PrimaryButton(tr("Mettre à jour (%s)", state.newVersion ?: "")) {
                    scope.launch { app.veltrio.shared.Updater.downloadAndInstall(context) }
                }
            }
            app.veltrio.shared.Updater.Step.Ready -> {
                PrimaryButton(tr("Installer la version %s", state.newVersion ?: "")) { app.veltrio.shared.Updater.install(context) }
            }
            else -> {
                GhostButton(tr("Rechercher une mise à jour"), enabled = step != app.veltrio.shared.Updater.Step.Checking && step != app.veltrio.shared.Updater.Step.Downloading) {
                    scope.launch { app.veltrio.shared.Updater.check(context) }
                }
            }
        }
    }
    val status = when (step) {
        app.veltrio.shared.Updater.Step.Checking -> tr("Recherche…")
        app.veltrio.shared.Updater.Step.UpToDate -> tr("Veltrio %s est à jour.", app.veltrio.shared.Updater.currentVersion(context))
        app.veltrio.shared.Updater.Step.Available -> tr("Une mise à jour est disponible (Veltrio %s).", state.newVersion ?: "")
        app.veltrio.shared.Updater.Step.Downloading -> tr("Téléchargement de la version %s… %s %%", state.newVersion ?: "", (state.progress * 100).toInt())
        app.veltrio.shared.Updater.Step.Ready -> tr("La version %s est prête : Android demande de confirmer l'installation.", state.newVersion ?: "")
        app.veltrio.shared.Updater.Step.Failed -> state.error
        app.veltrio.shared.Updater.Step.Development -> tr("Version de développement : les mises à jour concernent la version publiée sur GitHub.")
        else -> null
    }
    status?.let { Text(it, style = Type.caption(12), color = if (step == app.veltrio.shared.Updater.Step.Failed) p.danger else p.textTertiary) }
}

/** Optional TMDB API key + metadata language for localized titles & synopses. */
@Composable
private fun TmdbCard() {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    val state by app.veltrio.shared.TmdbService.state.collectAsState()
    var keyInput by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().border(1.dp, p.border).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TMDB", style = Type.title(16), color = p.textPrimary, modifier = Modifier.weight(1f))
            Text(
                if (state.connected) tr("CONNECTÉ") else tr("REQUIS POUR LES CATALOGUES"),
                style = Type.mono(10, FontWeight.Bold),
                color = if (state.connected) p.success else p.accentText,
            )
        }
        Body(tr("Fournit les catalogues de films et séries, le hero, la recherche et les résumés dans votre langue (clé API v3 ou jeton v4 gratuit sur themoviedb.org/settings/api)."))
        if (state.connected) {
            Picker(
                tr("Langue des fiches"),
                app.veltrio.shared.TmdbService.languages,
                state.language,
            ) { app.veltrio.shared.TmdbService.setLanguage(it) }
            GhostButton(tr("Déconnecter TMDB")) { app.veltrio.shared.TmdbService.disconnect() }
        } else {
            TextInput(keyInput, { keyInput = it }, tr("Clé API ou jeton TMDB"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(tr("Connecter"), loading = state.busy, enabled = keyInput.isNotBlank()) {
                    scope.launch {
                        if (app.veltrio.shared.TmdbService.connect(keyInput)) keyInput = ""
                    }
                }
            }
        }
        state.error?.let { Text(it, style = Type.caption(12), color = p.danger) }
    }
}
