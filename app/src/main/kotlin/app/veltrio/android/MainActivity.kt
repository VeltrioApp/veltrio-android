package app.veltrio.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.veltrio.android.screens.CatalogScreen
import app.veltrio.android.screens.DetailScreen
import app.veltrio.android.screens.HomeScreen
import app.veltrio.android.screens.LibraryScreen
import app.veltrio.android.screens.LiveScreen
import app.veltrio.android.screens.PlayerScreen
import app.veltrio.android.screens.SearchScreen
import app.veltrio.android.screens.SettingsScreen
import app.veltrio.android.screens.SourcesScreen
import app.veltrio.shared.AppTheme
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (Nav.theme) {
                AppTheme.Dark -> true
                AppTheme.Light -> false
                AppTheme.System -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // Dark icons over the light theme's paper, light ones over black.
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            VeltrioTheme(dark = dark) {
                val controller = rememberNavController()
                Nav.controller = controller
                // The player is full screen, in landscape, with the screen kept
                // on. Decided here from the destination, so zapping (one player
                // replacing another) doesn't flip back to portrait in between.
                val entry by controller.currentBackStackEntryAsState()
                val inPlayer = entry?.destination?.route?.startsWith("player/") == true
                androidx.compose.runtime.LaunchedEffect(inPlayer) {
                    val insets = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    if (inPlayer) {
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        insets.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insets.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    } else {
                        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        insets.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    }
                    window.decorView.keepScreenOn = inPlayer
                }
                Box(Modifier.fillMaxSize().background(VeltrioTheme.palette.background)) {
                    NavHost(controller, startDestination = "tabs") {
                        composable("tabs") { Tabs() }
                        composable("detail/{key}") { DetailScreen(Nav.arg(it.arguments?.getString("key"))!!) }
                        composable("catalog/{key}") { CatalogScreen(Nav.arg(it.arguments?.getString("key"))!!) }
                        composable("player/{key}") { PlayerScreen(Nav.arg(it.arguments?.getString("key"))!!) }
                        composable("iptvseries/{key}") {
                            val (playlist, series) = Nav.arg<Pair<app.veltrio.shared.iptv.PlaylistRef, app.veltrio.shared.iptv.ChannelItem>>(it.arguments?.getString("key"))!!
                            app.veltrio.android.screens.IptvSeriesScreen(playlist, series)
                        }
                        composable("serverseries/{key}") {
                            val (server, item) = Nav.arg<Pair<app.veltrio.shared.MediaServer, app.veltrio.shared.MediaLibraryItem>>(it.arguments?.getString("key"))!!
                            app.veltrio.android.screens.MediaServerSeriesScreen(server, item)
                        }
                        composable("addoncatalog") { app.veltrio.android.screens.AddonCatalogScreen() }
                        composable("search") { SearchScreen() }
                        composable("settings") { SettingsScreen() }
                    }
                    // Debug builds only: `adb shell am start … --es play_url <url>`
                    // opens the player straight away, to test it without an addon.
                    val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        val url = intent.getStringExtra("play_url")
                        if (debuggable && url != null) {
                            Nav.play(app.veltrio.shared.PlayRequest(title = "Test", subtitle = url, url = url, videoId = intent.getStringExtra("video_id")))
                        }
                    }
                }
            }
        }
    }
}

private val tabs = listOf("Accueil", "Collection", "Direct", "Sources")

/** The four sections, as the macOS top bar: the wordmark with search and
 *  settings above, numbered text tabs below with an amber underline. */
@Composable
private fun Tabs() {
    val selected = Nav.tab
    val p = VeltrioTheme.palette
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Veltrio", style = Type.displaySerif(24), color = p.textPrimary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable { Nav.search() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, "Rechercher", tint = p.textPrimary)
            }
            Box(Modifier.size(44.dp).clickable { Nav.settings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Settings, "Réglages", tint = p.textPrimary)
            }
        }
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> HomeScreen()
                1 -> LibraryScreen()
                2 -> LiveScreen()
                else -> SourcesScreen()
            }
        }
        Row(
            Modifier.fillMaxWidth().background(p.background).border(0.dp, p.hairline).navigationBarsPadding()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, label ->
                val active = index == selected
                Column(
                    Modifier.weight(1f).clickable { Nav.tab = index }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(index + 1), style = Type.mono(9, FontWeight.SemiBold), color = p.accentText)
                        Text(label.uppercase(), style = Type.eyebrow(10, 0.16f), color = if (active) p.textPrimary else p.textTertiary)
                    }
                    Box(Modifier.width(32.dp).height(2.dp).background(if (active) p.accent else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}
