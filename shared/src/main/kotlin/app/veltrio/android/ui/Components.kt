package app.veltrio.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import app.veltrio.android.LocalTv
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage

/** A 2:3 poster with its title and a mono subtitle, as macOS's PosterCard. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    title: String, posterUrl: String?, subtitle: String?, width: Dp = 128.dp, progress: Float? = null,
    /** A channel logo: shown whole on a square, not cropped to a poster. */
    logo: Boolean = false,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val p = VeltrioTheme.palette
    val focusMod = if (onFocused != null) Modifier.onFocusChanged { if (it.isFocused) onFocused() } else Modifier
    Column(Modifier.width(width).then(focusMod).combinedClickable(onClick = onClick, onLongClick = onLongClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (logo) 1f else 2f / 3f).background(p.surface).border(1.dp, p.border)) {
            Text(
                title.take(1).uppercase(), style = Type.displaySerif(28), color = p.textQuaternary,
                modifier = Modifier.align(Alignment.Center),
            )
            AsyncImage(
                posterUrl, null, Modifier.fillMaxSize().padding(if (logo) 14.dp else 0.dp),
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
            )
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(p.accent))
            }
        }
        Text(title, style = Type.title(14), color = p.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = Type.mono(10), color = p.textTertiary, maxLines = 1)
    }
}

/** "01 — Popular" with "CINEMETA · FILM" under it: numeral, serif title,
 *  tracked caps (on one line on macOS; stacked on a phone's width). */
@Composable
fun SectionHeader(number: Int?, title: String, eyebrow: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val p = VeltrioTheme.palette
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (number != null) Text("%02d —".format(number), style = Type.mono(11, androidx.compose.ui.text.font.FontWeight.SemiBold), color = p.accentText)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.displaySerif(22, androidx.compose.ui.text.font.FontWeight.SemiBold), color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (eyebrow != null) Text(eyebrow.uppercase(), style = Type.eyebrow(9, 0.18f), color = p.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** An eyebrow: tracked caps, accent by default. */
@Composable
fun Eyebrow(text: String, color: Color = VeltrioTheme.palette.accentText, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Type.eyebrow(10, 0.28f), color = color, modifier = modifier)
}

/** The amber block button ("REGARDER"). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
    val p = VeltrioTheme.palette
    val tv = LocalTv.current
    val interaction = remember { MutableInteractionSource() }
    val indication = if (tv) FocusRing(Color.White, contrast = true) else LocalIndication.current
    Row(
        modifier.background(if (enabled) p.accent else p.accent.copy(alpha = 0.4f))
            .clickable(interactionSource = interaction, indication = indication, enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(14.dp), color = p.onAccent, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = p.onAccent)
        Text(text.uppercase(), style = Type.label(12, androidx.compose.ui.text.font.FontWeight.Bold), color = p.onAccent)
    }
}

/** The outlined button ("DÉTAILS"). */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = VeltrioTheme.palette.textPrimary, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.border(BorderStroke(1.5.dp, color.copy(alpha = if (enabled) 1f else 0.4f)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(15.dp), tint = color)
        Text(text.uppercase(), style = Type.label(12, androidx.compose.ui.text.font.FontWeight.Bold), color = color.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

/** A filter chip: amber when selected, outlined otherwise. */
@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = VeltrioTheme.palette
    val tv = LocalTv.current
    val interaction = remember { MutableInteractionSource() }
    val indication = if (selected && tv) FocusRing(Color.White, contrast = true) else LocalIndication.current
    Box(
        modifier.background(if (selected) p.accent else Color.Transparent)
            .border(1.dp, if (selected) p.accent else p.borderStrong)
            .clickable(interactionSource = interaction, indication = indication, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label.uppercase(), style = Type.label(11, androidx.compose.ui.text.font.FontWeight.Bold), color = if (selected) p.onAccent else p.textSecondary)
    }
}

/** The page header: eyebrow, serif title, a line of context, a rule. */
@Composable
fun PageHeader(eyebrow: String?, title: String, subtitle: String? = null, modifier: Modifier = Modifier, inset: Dp = Spacing.lg) {
    val p = VeltrioTheme.palette
    Column(modifier.fillMaxWidth().padding(horizontal = inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (eyebrow != null) Eyebrow(eyebrow)
        Text(title, style = Type.displaySerif(34), color = p.textPrimary)
        if (subtitle != null) Text(subtitle, style = Type.body(13), color = p.textSecondary)
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(p.hairline))
    }
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), color = VeltrioTheme.palette.accent, strokeWidth = 2.dp)
    }
}
