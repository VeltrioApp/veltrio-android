package app.veltrio.android.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** On TV, what the remote is on must be obvious: every clickable element
 *  draws an amber frame and a light veil while it has the focus, a stronger
 *  veil while pressed. Installed as the app's indication in the TV app.
 *  When [contrast] is true (amber-filled buttons and selected chips), the ring
 *  switches to a crisp white frame with a dark inner separator so focus stays
 *  unmistakable against yellow. */
class FocusRing(private val color: Color, private val contrast: Boolean = false) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color, contrast)
    override fun equals(other: Any?) = other is FocusRing && other.color == color && other.contrast == contrast
    override fun hashCode() = 31 * color.hashCode() + contrast.hashCode()

    private class Node(
        private val source: InteractionSource,
        private val color: Color,
        private val contrast: Boolean,
    ) : androidx.compose.ui.Modifier.Node(), DrawModifierNode {
        private var focused = 0
        private var pressed = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is FocusInteraction.Focus -> focused++
                        is FocusInteraction.Unfocus -> focused = maxOf(0, focused - 1)
                        is PressInteraction.Press -> pressed++
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = maxOf(0, pressed - 1)
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed > 0) drawRect(color.copy(alpha = 0.25f))
            if (focused > 0) {
                if (contrast) {
                    drawRect(Color.White.copy(alpha = 0.16f))
                    val outer = 3.5.dp.toPx()
                    val inner = 1.5.dp.toPx()
                    val inset = outer + inner / 2
                    drawRect(
                        Color.Black.copy(alpha = 0.75f),
                        topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                        style = Stroke(inner),
                    )
                    drawRect(
                        Color.White,
                        topLeft = androidx.compose.ui.geometry.Offset(outer / 2, outer / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - outer, size.height - outer),
                        style = Stroke(outer),
                    )
                } else {
                    drawRect(color.copy(alpha = 0.12f))
                    val w = 3.dp.toPx()
                    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
                }
            }
        }
    }
}

/** A hero image's box: a ratio on a phone held upright, a fixed height on a
 *  wide screen (tablet, TV), where the ratio would fill several screens. */
@Composable
fun Modifier.hero(ratio: Float, wideHeight: Dp = 440.dp): Modifier =
    if (LocalConfiguration.current.screenWidthDp > 700) fillMaxWidth().height(wideHeight) else fillMaxWidth().aspectRatio(ratio)
