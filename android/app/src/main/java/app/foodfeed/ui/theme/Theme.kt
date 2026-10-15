package app.foodfeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Warm saffron brand, a nod to food apps like Swiggy without copying their colours.
val Saffron = Color(0xFFEF4F1A)
val SaffronDark = Color(0xFFFF8A5B)
val Turmeric = Color(0xFFF7A928)
val VegGreen = Color(0xFF1E8E3E)
val NonVegRed = Color(0xFFB3261E)
val GemPurple = Color(0xFF7B3FE4)

/** Instagram-like ring for category "stories" and avatars. */
val StoryRing = Brush.sweepGradient(
    listOf(Color(0xFFF9CE34), Color(0xFFEE2A7B), Color(0xFF6228D7), Color(0xFFF9CE34)),
)
val BrandGradient = Brush.linearGradient(listOf(Turmeric, Saffron, Color(0xFFD62976)))

private val Light = lightColorScheme(
    primary = Saffron,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF6B5B55),
    tertiary = VegGreen,
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF5EDE9),
    onSurfaceVariant = Color(0xFF5E5450),
    surfaceContainer = Color(0xFFF8F1EE),
    surfaceContainerHigh = Color(0xFFF2EAE6),
    outline = Color(0xFFD8CCC7),
    outlineVariant = Color(0xFFEADFDA),
    error = NonVegRed,
)

private val Dark = darkColorScheme(
    primary = SaffronDark,
    onPrimary = Color(0xFF3A0B00),
    primaryContainer = Color(0xFF7A2A0B),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFD9C2BA),
    tertiary = Color(0xFF6DD58C),
    background = Color(0xFF15110F),
    surface = Color(0xFF15110F),
    surfaceVariant = Color(0xFF2A2421),
    onSurfaceVariant = Color(0xFFD3C4BE),
    surfaceContainer = Color(0xFF1F1A18),
    surfaceContainerHigh = Color(0xFF2A2421),
    outline = Color(0xFF5A4F4B),
    outlineVariant = Color(0xFF3B3330),
    error = Color(0xFFFFB4AB),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold),
)

val CardTitle = TextStyle(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)

@Composable
fun FoodFeedTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = AppTypography, content = content)
}
