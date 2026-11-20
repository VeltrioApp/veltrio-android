package app.veltrio.android.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme

@Composable
fun SearchScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Search", style = Type.title(18), color = VeltrioTheme.palette.textTertiary)
    }
}
