package app.veltrio.android

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veltrio.android.screens.HomeScreen
import app.veltrio.android.screens.LibraryScreen
import app.veltrio.android.screens.LiveScreen
import app.veltrio.android.screens.SettingsScreen
import app.veltrio.android.screens.SourcesScreen
import app.veltrio.shared.i18n.tr
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { VeltrioRoot(this, tv = false) { Tabs() } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Nav.isInPlayer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Nav.inPipMode = isInPictureInPictureMode
    }
}

private data class TabItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val tabs = listOf(
    TabItem(tr("Accueil"), Icons.Outlined.Home, Icons.Filled.Home),
    TabItem(tr("Collection"), Icons.Outlined.Bookmarks, Icons.Filled.Bookmarks),
    TabItem(tr("Direct"), Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
    TabItem(tr("Sources"), Icons.Outlined.Extension, Icons.Filled.Extension),
    TabItem(tr("Réglages"), Icons.Outlined.Settings, Icons.Filled.Settings),
)

/** Classic Android bottom navigation bar with icon + label items and an amber
 *  active pill indicator, paired with a minimal wordmark + search top bar. */
@Composable
private fun Tabs() {
    val selected = Nav.tab.coerceIn(0, tabs.lastIndex)
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
        }
        Box(Modifier.weight(1f)) {
            when (selected) {
                0 -> HomeScreen()
                1 -> LibraryScreen()
                2 -> LiveScreen()
                3 -> SourcesScreen()
                else -> SettingsScreen()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        Row(
            Modifier
                .fillMaxWidth()
                .background(p.backgroundAlt)
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, item ->
                val active = index == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { Nav.tab = index }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (active) p.accent.copy(alpha = 0.16f) else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (active) item.selectedIcon else item.icon,
                            contentDescription = item.label,
                            tint = if (active) p.accentText else p.textTertiary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        item.label,
                        style = Type.label(11, if (active) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (active) p.textPrimary else p.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
