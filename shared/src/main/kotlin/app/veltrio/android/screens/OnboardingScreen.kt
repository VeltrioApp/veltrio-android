package app.veltrio.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.veltrio.android.LocalTv
import app.veltrio.android.Nav
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.shared.AppPrefs
import app.veltrio.shared.Core
import app.veltrio.shared.PlayerPrefs
import app.veltrio.shared.StremioAccount
import app.veltrio.shared.i18n.tr
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import kotlinx.coroutines.launch

private const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"

/** First-launch onboarding for both Android phone/tablet and Android TV.
 *  - On TV: highlights LAN pairing ("Depuis un téléphone") + Stremio + Cinemeta.
 *  - On phone: 3-step editorial setup (Stremio sign-in first, Sources, Ready). */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val tv = LocalTv.current
    if (tv) TvOnboarding(onDone) else PhoneOnboarding(onDone)
}

@Composable
private fun TvOnboarding(onDone: () -> Unit) {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    val stremio by StremioAccount.state.collectAsState()
    var showStremioForm by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busyCinemeta by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun finish() {
        AppPrefs.update { it.copy(onboardingDone = true) }
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(p.background)
            .safeDrawingPadding()
            .padding(horizontal = 48.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Veltrio", style = Type.displaySerif(32, FontWeight.Bold), color = p.textPrimary)
                Spacer(Modifier.weight(1f))
                GhostButton(tr("Passer")) { finish() }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(tr("BIENVENUE SUR TV"), color = p.accentText)
                Text(tr("Toutes vos sources, un seul lecteur."), style = Type.displaySerif(38, FontWeight.Bold), color = p.textPrimary)
                Text(
                    tr("La façon la plus rapide : envoyez toute votre configuration depuis votre téléphone ou votre Mac en 10 secondes."),
                    style = Type.body(15),
                    color = p.textSecondary,
                )
            }

            if (!showStremioForm) {
                // Primary TV action: pair from phone/Mac
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.surface)
                        .border(1.dp, p.accent.copy(alpha = 0.5f))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.PhonelinkSetup, null, tint = p.accentText, modifier = Modifier.size(22.dp))
                        Text(tr("Configurer depuis un téléphone ou un Mac"), style = Type.label(16, FontWeight.Bold), color = p.textPrimary)
                    }
                    Text(
                        tr("Transfère vos addons, playlists IPTV, comptes débrideurs et votre Collection sur le réseau local avec un code à 6 chiffres."),
                        style = Type.body(13),
                        color = p.textSecondary,
                    )
                    PrimaryButton(tr("Afficher le code de transfert")) {
                        AppPrefs.update { it.copy(onboardingDone = true) }
                        onDone()
                        Nav.controller?.navigate("pair")
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        Modifier
                            .weight(1f)
                            .background(p.surface)
                            .border(1.dp, p.hairline)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(tr("Se connecter à Stremio"), style = Type.label(15, FontWeight.Bold), color = p.textPrimary)
                        Text(tr("Importe vos addons et votre historique Stremio."), style = Type.caption(12), color = p.textSecondary)
                        GhostButton(tr("Connexion Stremio")) { showStremioForm = true }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .background(p.surface)
                            .border(1.dp, p.hairline)
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(tr("Démarrer avec Cinemeta"), style = Type.label(15, FontWeight.Bold), color = p.textPrimary)
                        Text(tr("Installe le catalogue films et séries populaires."), style = Type.caption(12), color = p.textSecondary)
                        GhostButton(tr("Installer Cinemeta"), enabled = !busyCinemeta) {
                            scope.launch {
                                busyCinemeta = true
                                error = Core.installAddon(CINEMETA_URL)
                                busyCinemeta = false
                                if (error == null) finish()
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(p.surface)
                        .border(1.dp, p.accent.copy(alpha = 0.5f))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(tr("Se connecter à Stremio"), style = Type.label(16, FontWeight.Bold), color = p.textPrimary)
                    Field(email, { email = it }, tr("E-mail"))
                    Field(password, { password = it }, tr("Mot de passe"), visual = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(tr("Se connecter et importer"), loading = stremio.busy || stremio.importing) {
                            scope.launch {
                                StremioAccount.signIn(email.trim(), password).join()
                                if (StremioAccount.state.value.connected) {
                                    StremioAccount.installAccountAddons()
                                    StremioAccount.importLibrary()
                                    finish()
                                }
                            }
                        }
                        GhostButton(tr("Retour")) { showStremioForm = false }
                    }
                    stremio.error?.let { Text(it, style = Type.caption(12), color = p.danger) }
                }
            }

            error?.let { Text(it, style = Type.caption(12), color = p.danger) }
        }
    }
}

@Composable
private fun PhoneOnboarding(onDone: () -> Unit) {
    val p = VeltrioTheme.palette
    val scope = rememberCoroutineScope()
    val stremio by StremioAccount.state.collectAsState()
    var step by remember { mutableIntStateOf(0) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var installCinemeta by remember { mutableStateOf(true) }
    var customUrl by remember { mutableStateOf("") }
    var installingAddon by remember { mutableStateOf(false) }
    var addonMessage by remember { mutableStateOf<String?>(null) }
    var addonError by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }

    fun finish(skipCinemeta: Boolean = false) {
        scope.launch {
            finishing = true
            if (!skipCinemeta && installCinemeta && !Core.isInstalled(CINEMETA_URL)) {
                Core.installAddon(CINEMETA_URL)
            }
            AppPrefs.update { it.copy(onboardingDone = true) }
            finishing = false
            onDone()
        }
    }

    Column(Modifier.fillMaxSize().background(p.background).safeDrawingPadding()) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(p.backgroundAlt).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Veltrio", style = Type.displaySerif(24, FontWeight.Bold), color = p.textPrimary)
            Spacer(Modifier.size(10.dp))
            Text("0${step + 1} / 03", style = Type.mono(11, FontWeight.SemiBold), color = p.accentText)
            Spacer(Modifier.weight(1f))
            Text(
                tr("PASSER"),
                style = Type.label(11, FontWeight.Bold),
                color = p.textTertiary,
                modifier = Modifier.clickable { finish(skipCinemeta = true) }.padding(6.dp),
            )
        }
        Rule()

        // Body
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (step) {
                0 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(tr("01 — BIENVENUE"), color = p.accentText)
                        Text(tr("Toutes vos sources, un seul lecteur natif."), style = Type.displaySerif(28, FontWeight.Bold), color = p.textPrimary)
                        Text(
                            tr("Connectez votre compte Stremio pour importer vos addons et votre bibliothèque en un geste, ou démarrez à zéro."),
                            style = Type.body(14),
                            color = p.textSecondary,
                        )
                    }

                    // Stremio primary card
                    Column(
                        Modifier.fillMaxWidth().background(p.surface).border(1.dp, p.accent.copy(alpha = 0.45f)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Se connecter à Stremio"), style = Type.label(15, FontWeight.Bold), color = p.textPrimary)
                            Spacer(Modifier.weight(1f))
                            if (stremio.connected) {
                                Text(tr("CONNECTÉ"), style = Type.mono(10, FontWeight.Bold), color = p.success)
                            }
                        }
                        if (stremio.connected) {
                            Text(
                                tr("Connecté en tant que %s.", stremio.user ?: "Stremio"),
                                style = Type.caption(12),
                                color = p.textSecondary,
                            )
                            PrimaryButton(tr("Importer addons et bibliothèque"), loading = stremio.busy || stremio.importing) {
                                StremioAccount.installAccountAddons()
                                StremioAccount.importLibrary()
                            }
                        } else {
                            Text(
                                tr("Importe automatiquement vos addons installés, votre collection et votre historique."),
                                style = Type.caption(12),
                                color = p.textSecondary,
                            )
                            Field(email, { email = it }, tr("E-mail"))
                            Field(password, { password = it }, tr("Mot de passe"), visual = PasswordVisualTransformation())
                            PrimaryButton(
                                tr("Se connecter et importer"),
                                loading = stremio.busy || stremio.importing,
                                enabled = email.isNotBlank() && password.isNotBlank(),
                            ) {
                                scope.launch {
                                    StremioAccount.signIn(email.trim(), password).join()
                                    if (StremioAccount.state.value.connected) {
                                        password = ""
                                        StremioAccount.installAccountAddons()
                                        StremioAccount.importLibrary()
                                    }
                                }
                            }
                        }
                        stremio.addonResult?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.CheckCircle, null, tint = p.success, modifier = Modifier.size(14.dp))
                                Text(it, style = Type.caption(12), color = p.success)
                            }
                        }
                        stremio.lastResult?.let {
                            Text(it, style = Type.caption(12), color = p.textSecondary)
                        }
                        stremio.error?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Filled.Warning, null, tint = p.danger, modifier = Modifier.size(14.dp))
                                Text(it, style = Type.caption(12), color = p.danger)
                            }
                        }
                    }

                    // Start fresh card
                    Column(
                        Modifier.fillMaxWidth().background(p.surface).border(1.dp, p.hairline).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Eyebrow(tr("OU DÉMARRER À ZÉRO"), color = p.textTertiary)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(tr("Installer le catalogue Cinemeta"), style = Type.label(14, FontWeight.SemiBold), color = p.textPrimary)
                                Text(
                                    tr("Remplit immédiatement l'Accueil avec les films et séries populaires."),
                                    style = Type.caption(12),
                                    color = p.textSecondary,
                                )
                            }
                            Switch(
                                checked = installCinemeta,
                                onCheckedChange = { installCinemeta = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
                            )
                        }
                    }
                }

                1 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(tr("02 — SOURCES"), color = p.accentText)
                        Text(tr("Connectez ce que vous utilisez déjà."), style = Type.displaySerif(28, FontWeight.Bold), color = p.textPrimary)
                        Text(
                            tr("Collez un lien d'addon Stremio maintenant, ou ajoutez plus tard vos playlists IPTV, serveurs Plex / Jellyfin / Emby et débrideurs dans Sources."),
                            style = Type.body(14),
                            color = p.textSecondary,
                        )
                    }

                    Column(
                        Modifier.fillMaxWidth().background(p.surface).border(1.dp, p.hairline).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(tr("Ajouter un addon Stremio"), style = Type.label(14, FontWeight.SemiBold), color = p.textPrimary)
                        Field(customUrl, { customUrl = it }, "https://example.com/manifest.json")
                        GhostButton(tr("Installer"), enabled = !installingAddon && customUrl.isNotBlank()) {
                            scope.launch {
                                installingAddon = true
                                addonError = null
                                addonMessage = null
                                val err = Core.installAddon(customUrl.trim())
                                installingAddon = false
                                if (err == null) {
                                    addonMessage = tr("Addon installé.")
                                    customUrl = ""
                                } else {
                                    addonError = err
                                }
                            }
                        }
                        addonMessage?.let { Text(it, style = Type.caption(12), color = p.success) }
                        addonError?.let { Text(it, style = Type.caption(12), color = p.danger) }
                    }

                    // Optional TMDB API key for localized movie & series metadata
                    val tmdb by app.veltrio.shared.TmdbService.state.collectAsState()
                    var tmdbKey by remember { mutableStateOf("") }
                    Column(
                        Modifier.fillMaxWidth().background(p.surface).border(1.dp, p.hairline).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(tr("Résumés & titres dans votre langue (TMDB)"), style = Type.label(14, FontWeight.SemiBold), color = p.textPrimary)
                        Text(
                            tr("Optionnel : ajoutez une clé API gratuite TMDB (themoviedb.org/settings/api) pour traduire les fiches Cinemeta. Transférée automatiquement vers votre TV."),
                            style = Type.caption(12),
                            color = p.textSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("fr-FR" to "FR", "en-US" to "EN", "es-ES" to "ES", "de-DE" to "DE").forEach { (code, lbl) ->
                                app.veltrio.android.ui.Chip(lbl, tmdb.language == code) {
                                    app.veltrio.shared.TmdbService.setLanguage(code)
                                }
                            }
                        }
                        if (tmdb.connected) {
                            Text(tr("Clé TMDB connectée ✓"), style = Type.caption(12), color = p.success)
                        } else {
                            Field(tmdbKey, { tmdbKey = it }, tr("Clé API ou jeton TMDB (optionnel)"))
                            GhostButton(tr("Enregistrer la clé TMDB"), enabled = !tmdb.busy && tmdbKey.isNotBlank()) {
                                scope.launch {
                                    if (app.veltrio.shared.TmdbService.connect(tmdbKey)) tmdbKey = ""
                                }
                            }
                        }
                        tmdb.error?.let { Text(it, style = Type.caption(12), color = p.danger) }
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(tr("03 — PRÊT"), color = p.accentText)
                        Text(tr("Lumière tamisée. Tout est prêt."), style = Type.displaySerif(28, FontWeight.Bold), color = p.textPrimary)
                        Text(
                            tr("Vous pouvez tout ajuster plus tard dans Sources et Réglages, ou envoyer votre configuration vers une TV depuis Réglages → Envoyer vers une TV."),
                            style = Type.body(14),
                            color = p.textSecondary,
                        )
                    }

                    Column(
                        Modifier.fillMaxWidth().background(p.surface).border(1.dp, p.hairline).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SummaryRow(tr("Catalogue"), if (installCinemeta || Core.isInstalled(CINEMETA_URL)) tr("Cinemeta activé") else tr("Aucun"))
                        Rule()
                        SummaryRow(tr("Addons installés"), "${Core.addons.value.size + if (installCinemeta && !Core.isInstalled(CINEMETA_URL)) 1 else 0}")
                        Rule()
                        SummaryRow(tr("Audio / Sous-titres"), "${PlayerPrefs.current.audioLang.ifEmpty { tr("Auto") }} / ${PlayerPrefs.current.subtitleLang.ifEmpty { tr("Auto") }}")
                    }
                }
            }
        }

        Rule()
        // Footer
        Row(
            Modifier.fillMaxWidth().background(p.backgroundAlt).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                GhostButton(tr("Retour"), icon = Icons.Filled.ChevronLeft) { step-- }
            }
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                PrimaryButton(tr("Continuer"), icon = Icons.AutoMirrored.Filled.ArrowForward) { step++ }
            } else {
                PrimaryButton(tr("Commencer à regarder"), icon = Icons.Filled.PlayArrow, loading = finishing) { finish() }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val p = VeltrioTheme.palette
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.label(13, FontWeight.Medium), color = p.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = Type.mono(12, FontWeight.SemiBold), color = p.textPrimary)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(VeltrioTheme.palette.hairline))
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visual: VisualTransformation = VisualTransformation.None,
) {
    val p = VeltrioTheme.palette
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = visual,
        textStyle = Type.body(14).copy(color = p.textPrimary),
        cursorBrush = SolidColor(p.accent),
        modifier = Modifier
            .fillMaxWidth()
            .background(p.background)
            .border(1.dp, p.hairline)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = Type.body(14), color = p.textTertiary)
                inner()
            }
        },
    )
}
