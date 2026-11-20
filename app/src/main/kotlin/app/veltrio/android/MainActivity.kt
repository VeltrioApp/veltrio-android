package app.veltrio.android

import app.veltrio.shared.i18n.tr
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
        setContent { VeltrioRoot(this, tv = false) { Tabs() } }
    }
}

private val tabs = listOf(tr("Accueil"), tr("Collection"), tr("Direct"), tr("Sources"))

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
            Text(tr("Veltrio"), style = Type.displaySerif(24), color = p.textPrimary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable { Nav.search() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Search, tr("Rechercher"), tint = p.textPrimary)
            }
            Box(Modifier.size(44.dp).clickable { Nav.settings() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Settings, tr("Réglages"), tint = p.textPrimary)
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
