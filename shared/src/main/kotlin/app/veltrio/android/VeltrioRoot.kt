package app.veltrio.android

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import app.veltrio.android.screens.PlayerScreen
import app.veltrio.android.screens.SearchScreen
import app.veltrio.android.screens.SettingsScreen
import app.veltrio.shared.AppTheme
import app.veltrio.shared.MediaLibraryItem
import app.veltrio.shared.MediaServer
import app.veltrio.shared.PlayRequest
import app.veltrio.shared.iptv.ChannelItem
import app.veltrio.shared.iptv.PlaylistRef
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
                }
                // Debug builds only: `adb shell am start … --es play_url <url>`
                // opens the player straight away, to test it without an addon.
                val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                LaunchedEffect(Unit) {
                    val url = activity.intent.getStringExtra("play_url")
                    if (debuggable && url != null) {
                        Nav.play(PlayRequest(title = "Test", subtitle = url, url = url, videoId = activity.intent.getStringExtra("video_id")))
                    }
                }
            }
          }
        }
    }
}
