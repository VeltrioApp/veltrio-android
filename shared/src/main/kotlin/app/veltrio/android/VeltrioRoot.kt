package app.veltrio.android

import app.veltrio.shared.i18n.tr
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.veltrio.android.screens.AddonCatalogScreen
import app.veltrio.android.screens.CatalogScreen
import app.veltrio.android.screens.DetailScreen
import app.veltrio.android.screens.IptvSeriesScreen
import app.veltrio.android.screens.MediaServerSeriesScreen
import app.veltrio.android.screens.OnboardingScreen
import app.veltrio.android.screens.PlayerScreen
import app.veltrio.android.screens.SearchScreen
import app.veltrio.android.screens.SettingsScreen
import app.veltrio.android.ui.GhostButton
import app.veltrio.android.ui.PrimaryButton
import app.veltrio.shared.AppPrefs
import app.veltrio.shared.AppTheme
import app.veltrio.shared.Core
import app.veltrio.shared.MediaLibraryItem
import app.veltrio.shared.MediaServer
import app.veltrio.shared.MediaServers
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.StremioAccount
import app.veltrio.shared.Updater
import app.veltrio.shared.iptv.ChannelItem
import app.veltrio.shared.iptv.IptvStore
import app.veltrio.shared.iptv.PlaylistRef
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme

/** True in the Android TV app: a remote, no touch screen. Screens adjust
 *  (player keys, focus) from it. */
val LocalTv = staticCompositionLocalOf { false }

/** The app below the window: theme, the destinations, and full screen while
 *  the player is shown. The phone and TV apps differ in [sections], what's
 *  shown at the top level (tabs or a side rail). */
@Composable
fun VeltrioRoot(activity: ComponentActivity, tv: Boolean, sections: @Composable () -> Unit) {
    val dark = when (Nav.theme) {
        AppTheme.Dark -> true
        AppTheme.Light -> false
        AppTheme.System -> isSystemInDarkTheme()
    }
    val window = activity.window
    // Dark icons over the light theme's paper, light ones over black.
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalTv provides tv) {
        VeltrioTheme(dark = dark) {
          CompositionLocalProvider(
              *(if (tv) arrayOf(androidx.compose.foundation.LocalIndication provides app.veltrio.android.ui.FocusRing(VeltrioTheme.palette.accent)) else emptyArray()),
          ) {
            val controller = rememberNavController()
            Nav.controller = controller
            // The player is full screen, in landscape, with the screen kept on.
            // Decided here from the destination, so zapping (one player
            // replacing another) doesn't flip back to portrait in between.
            val entry by controller.currentBackStackEntryAsState()
            val inPlayer = entry?.destination?.route?.startsWith("player/") == true
            LaunchedEffect(inPlayer) {
                val insets = WindowCompat.getInsetsController(window, window.decorView)
                if (inPlayer) {
                    if (!tv) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    if (!tv) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    insets.show(WindowInsetsCompat.Type.systemBars())
                }
                window.decorView.keepScreenOn = inPlayer
            }
            Box(Modifier.fillMaxSize().background(VeltrioTheme.palette.background)) {
                NavHost(controller, startDestination = "tabs") {
                    composable("tabs") { sections() }
                    composable("detail/{key}") { DetailScreen(Nav.arg(it.arguments?.getString("key"))!!) }
                    composable("catalog/{key}") { CatalogScreen(Nav.arg(it.arguments?.getString("key"))!!) }
                    composable("player/{key}") { PlayerScreen(Nav.arg(it.arguments?.getString("key"))!!) }
                    composable("iptvseries/{key}") {
                        val (playlist, series) = Nav.arg<Pair<PlaylistRef, ChannelItem>>(it.arguments?.getString("key"))!!
                        IptvSeriesScreen(playlist, series)
                    }
                    composable("serverseries/{key}") {
                        val (server, item) = Nav.arg<Pair<MediaServer, MediaLibraryItem>>(it.arguments?.getString("key"))!!
                        MediaServerSeriesScreen(server, item)
                    }
                    composable("addoncatalog") { AddonCatalogScreen() }
                    composable("pair") { app.veltrio.android.screens.PairScreen() }
                    composable("search") { SearchScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("onboarding") {
                        OnboardingScreen {
                            if (!controller.popBackStack()) {
                                controller.navigate("tabs") { popUpTo("onboarding") { inclusive = true } }
                            }
                        }
                    }
                }

                // Auto-update banner: announces an available release and asks before downloading.
                val updateState by Updater.state.collectAsState()
                var bannerDismissed by remember { mutableStateOf(false) }
                val updateScope = rememberCoroutineScope()
                val inOnboarding = entry?.destination?.route == "onboarding"
                val showBanner = !inPlayer && !inOnboarding && !bannerDismissed && updateState.step in setOf(
                    Updater.Step.Available, Updater.Step.Downloading, Updater.Step.Ready,
                )
                if (showBanner) {
                    val p = VeltrioTheme.palette
                    val ver = updateState.newVersion ?: ""
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .safeDrawingPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .background(p.surfaceElevated)
                            .border(1.dp, p.accent.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val message = when (updateState.step) {
                            Updater.Step.Available -> tr("Une mise à jour est disponible (Veltrio %s).", ver)
                            Updater.Step.Downloading -> tr("Téléchargement de Veltrio %s… %s %%", ver, (updateState.progress * 100).toInt())
                            else -> tr("Veltrio %s est prêt à installer.", ver)
                        }
                        Text(
                            message,
                            style = Type.label(13, FontWeight.SemiBold),
                            color = p.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        when (updateState.step) {
                            Updater.Step.Available -> {
                                PrimaryButton(tr("Mettre à jour")) {
                                    updateScope.launch { Updater.downloadAndInstall(activity) }
                                }
                                GhostButton(tr("Plus tard")) { bannerDismissed = true }
                            }
                            Updater.Step.Ready -> {
                                PrimaryButton(tr("Installer")) { Updater.install(activity) }
                                GhostButton(tr("Plus tard")) { bannerDismissed = true }
                            }
                            else -> Unit
                        }
                    }
                }

                // Launch hooks: Stremio history sync, silent background update check,
                // first-launch onboarding, and debug `play_url` / `onboarding` / `simulate_update` extras.
                val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                LaunchedEffect(Unit) {
                    StremioAccount.syncOnLaunchIfNeeded()
                    val simUpdate = if (debuggable) activity.intent.getStringExtra("simulate_update") else null
                    if (simUpdate != null) Updater.simulateAvailableForDebug(simUpdate)
                    val url = activity.intent.getStringExtra("play_url")
                    if (debuggable && url != null) {
                        Nav.play(PlayRequest(title = tr("Test"), subtitle = url, url = url, videoId = activity.intent.getStringExtra("video_id")))
                        return@LaunchedEffect
                    }
                    val forceOnboarding = debuggable && activity.intent.getBooleanExtra("onboarding", false)
                    if (forceOnboarding) {
                        controller.navigate("onboarding")
                    } else if (!AppPrefs.current.onboardingDone) {
                        val alreadyConfigured = Core.addons.value.isNotEmpty() || IptvStore.playlists.value.isNotEmpty() || MediaServers.all.any { it.state.value.connected }
                        if (alreadyConfigured) {
                            AppPrefs.update { it.copy(onboardingDone = true) }
                        } else {
                            controller.navigate("onboarding")
                        }
                    }
                    Updater.checkOnLaunchIfNeeded(activity)
                }
            }
          }
        }
    }
}
