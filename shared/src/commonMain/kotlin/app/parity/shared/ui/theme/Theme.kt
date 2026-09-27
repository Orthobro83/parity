package app.parity.shared.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Color tokens from design §4.2. Green and red are reserved for meaning (▲/▼, checked off). */
data class ParityColors(
    val bg: Color = Color(0xFF0B0D10),
    val surface: Color = Color(0xFF15181D),
    val surfaceRaised: Color = Color(0xFF1D2127),
    val glass: Color = Color(0xB815181D),
    val outline: Color = Color(0xFF2A2F37),
    val textPrimary: Color = Color(0xFFECEEF1),
    val textSecondary: Color = Color(0xFF9BA3AE),
    val accent: Color = Color(0xFF7C8CFF),
    val onAccent: Color = Color(0xFF0B0D10),
    val up: Color = Color(0xFF2BD67B),
    val down: Color = Color(0xFFFF5A5F),
    val neutral: Color = Color(0xFF6B7380),
    val sale: Color = Color(0xFFFFB547),
)

val LocalParityColors = staticCompositionLocalOf { ParityColors() }

/** Text styles from design §4.2. Prices use tabular figures so digits don't jitter. */
data class ParityType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val price: TextStyle,
    val priceSmall: TextStyle,
)

val LocalParityType = staticCompositionLocalOf<ParityType> { error("ParityTheme not set") }

object Parity {
    val colors: ParityColors @Composable get() = LocalParityColors.current
    val type: ParityType @Composable get() = LocalParityType.current
}

private const val TABULAR = "tnum"

@Composable
fun ParityTheme(fontFamily: FontFamily, trueBlack: Boolean, content: @Composable () -> Unit) {
    val colors = if (trueBlack) ParityColors(bg = Color.Black, surface = Color(0xFF0E1013)) else ParityColors()
    val type = ParityType(
        display = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp, fontFeatureSettings = TABULAR),
        headline = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        title = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        body = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
        label = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
        caption = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
        price = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp, fontFeatureSettings = TABULAR),
        priceSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = TABULAR),
    )
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        background = colors.bg,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.textSecondary,
        surfaceContainer = colors.surface,
        surfaceContainerLow = colors.surface,
        surfaceContainerHigh = colors.surfaceRaised,
        surfaceContainerHighest = colors.surfaceRaised,
        outline = colors.outline,
        outlineVariant = colors.outline,
        error = colors.down,
        onError = colors.onAccent,
    )
    val typography = Typography(
        displayLarge = type.display, headlineMedium = type.headline, headlineSmall = type.headline,
        titleLarge = type.title, titleMedium = type.title, titleSmall = type.label,
        bodyLarge = type.body, bodyMedium = type.body, bodySmall = type.caption,
        labelLarge = type.label, labelMedium = type.label, labelSmall = type.caption,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
    )
    CompositionLocalProvider(LocalParityColors provides colors, LocalParityType provides type) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
