package app.veltrio.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The macOS app's design tokens (Theme.swift), cinema-editorial: a
 *  true-black canvas, one tungsten amber, hairlines, a serif for titles
 *  and mono for counts. The light side is the same warm paper. */
@Immutable
data class Palette(
    val background: Color,
    val backgroundAlt: Color,
    val surface: Color,
    val surfaceHover: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderStrong: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    /** The accent as text: darker on paper, for contrast. */
    val accentText: Color,
    val isDark: Boolean,
) {
    val accent = Color(0xFFE6A84B)
    val accentHover = Color(0xFFF2BB64)
    val accentSoft = Color(0x24E6A84B)
    val onAccent = Color(0xFF1A1206)
    val danger = Color(0xFFE5484D)
    val success = Color(0xFF4F9E5F)

    companion object {
        val dark = Palette(
            background = Color.Black, backgroundAlt = Color(0xFF0A0A0A),
            surface = Color(0xFF111111), surfaceHover = Color(0xFF1A1A1A), surfaceElevated = Color(0xFF1F1F1F),
            border = Color.White.copy(alpha = 0.08f), borderStrong = Color.White.copy(alpha = 0.22f),
            hairline = Color.White.copy(alpha = 0.12f),
            textPrimary = Color(0xFFFAF7F2), textSecondary = Color.White.copy(alpha = 0.68f),
            textTertiary = Color.White.copy(alpha = 0.42f), textQuaternary = Color.White.copy(alpha = 0.26f),
            accentText = Color(0xFFE6A84B), isDark = true,
        )
        val light = Palette(
            background = Color(0xFFF7F3EC), backgroundAlt = Color(0xFFEFE9DF),
            surface = Color.White, surfaceHover = Color(0xFFECE6DB), surfaceElevated = Color(0xFFE4DDCF),
            border = Color.Black.copy(alpha = 0.10f), borderStrong = Color.Black.copy(alpha = 0.28f),
            hairline = Color.Black.copy(alpha = 0.14f),
            textPrimary = Color(0xFF0E0E12), textSecondary = Color.Black.copy(alpha = 0.70f),
            textTertiary = Color.Black.copy(alpha = 0.46f), textQuaternary = Color.Black.copy(alpha = 0.30f),
            accentText = Color(0xFF9C5E0C), isDark = false,
        )
    }
}

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 14.dp
    val lg = 22.dp
    val xl = 36.dp
    val xxl = 60.dp
}

/** Typography. Serif for marquee titles (New York on macOS), sans for UI,
 *  mono for counts and timecodes, tracked caps for eyebrows. */
object Type {
    fun displaySerif(size: Int = 40, weight: FontWeight = FontWeight.Bold) =
        TextStyle(fontFamily = FontFamily.Serif, fontWeight = weight, fontSize = size.sp, letterSpacing = (-0.02).em, lineHeight = (size * 1.08).sp)
    fun title(size: Int = 20) = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = size.sp)
    fun heading(size: Int = 16) = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = size.sp)
    fun body(size: Int = 14) = TextStyle(fontSize = size.sp, lineHeight = (size * 1.45).sp)
    fun label(size: Int = 12, weight: FontWeight = FontWeight.SemiBold) = TextStyle(fontWeight = weight, fontSize = size.sp)
    fun caption(size: Int = 11) = TextStyle(fontWeight = FontWeight.Medium, fontSize = size.sp)
    /** The house voice for eyebrows, counts, metadata rows: tracked caps. */
    fun eyebrow(size: Int = 10, tracking: Float = 0.24f) =
        TextStyle(fontWeight = FontWeight.Bold, fontSize = size.sp, letterSpacing = tracking.em)
    fun mono(size: Int = 11, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = FontFamily.Monospace, fontWeight = weight, fontSize = size.sp)
}

val LocalPalette = staticCompositionLocalOf { Palette.dark }

/** The palette in effect: `VeltrioTheme.palette.accent`. */
object VeltrioTheme {
    val palette: Palette @Composable get() = LocalPalette.current
}

@Composable
fun VeltrioTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (dark) Palette.dark else Palette.light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = palette.accent, onPrimary = palette.onAccent,
        background = palette.background, surface = palette.surface,
        onBackground = palette.textPrimary, onSurface = palette.textPrimary,
        surfaceVariant = palette.surfaceElevated, outline = palette.borderStrong,
    )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
