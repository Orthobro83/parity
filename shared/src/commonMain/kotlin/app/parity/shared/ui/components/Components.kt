package app.parity.shared.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parity.core.fx.FxDirection
import app.parity.core.fx.FxIndicator
import app.parity.shared.ui.theme.Parity

/** Fully rounded pill button (design §4.2): accent fill for primary, raised surface for secondary. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    destructive: Boolean = false,
    enabled: Boolean = true,
    height: Dp = if (primary) 52.dp else 44.dp,
    icon: ImageVector? = null,
) {
    val c = Parity.colors
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Parity.type.label, maxLines = 1)
    }
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(height),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) c.down else c.accent,
                contentColor = c.onAccent,
                disabledContainerColor = c.surfaceRaised,
                disabledContentColor = c.textSecondary,
            ),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(height),
            shape = CircleShape,
            border = BorderStroke(1.dp, c.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = c.surfaceRaised,
                contentColor = if (destructive) c.down else c.textPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) { content() }
    }
}

/** Round icon button used for ✕, steppers and camera controls. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = Parity.colors.surfaceRaised,
    tint: Color = Parity.colors.textPrimary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Parity.colors.outline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else Parity.colors.textSecondary, modifier = Modifier.size(size * 0.5f))
    }
}

/** Translucent panel for overlays on the camera feed. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Parity.colors.glass,
        border = BorderStroke(1.dp, Parity.colors.outline.copy(alpha = 0.7f)),
        content = content,
    )
}

/** Pill chip; tappable when [onClick] is given. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Parity.colors.textPrimary,
    background: Color = Parity.colors.surfaceRaised,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = Parity.colors
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.18f) else background)
            .border(1.dp, if (selected) color else c.outline, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Parity.type.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** ▲ / ▼ / = with the percentage, colored by meaning (design §9). */
@Composable
fun FxBadge(indicator: FxIndicator, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Parity.colors
    val (glyph, color) = when (indicator.direction) {
        FxDirection.UP -> "▲" to c.up
        FxDirection.DOWN -> "▼" to c.down
        FxDirection.SAME -> "=" to c.neutral
    }
    val scale = remember(indicator) { Animatable(0.6f) }
    LaunchedEffect(indicator) { scale.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f)) }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(glyph, color = color, style = Parity.type.label, modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        })
        Text(indicator.badgeText, color = color, style = Parity.type.label.copy(fontFeatureSettings = "tnum"))
    }
}

/**
 * Text that rolls each changed character vertically like an odometer (design §4.2), used for the
 * running total.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, char ->
            androidx.compose.runtime.key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(220)) { h -> if (up) h else -h } + fadeIn(tween(220)))
                            .togetherWith(slideOutVertically(tween(220)) { h -> if (up) -h else h } + fadeOut(tween(160)))
                    },
                    label = "digit",
                ) { ch -> Text(ch.toString(), style = style, color = color) }
            }
        }
    }
}

/** Draws the animated green strikethrough across its content (design §8.3). */
fun Modifier.strikeThrough(progress: Float, color: Color): Modifier = drawWithContent {
    drawContent()
    if (progress > 0f) {
        val y = size.height / 2f
        drawLine(color, Offset(0f, y), Offset(size.width * progress, y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Parity.type.caption.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)),
        color = Parity.colors.textSecondary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
