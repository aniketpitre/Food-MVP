package app.foodfeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Design language: quiet and editorial. Ink on warm paper, photos do the talking.
 * One accent colour, used only for "recommended" (the heart) and nothing else.
 */

/** The single accent. Reserved for the recommend action and its count. */
val Accent = Color(0xFFE5482C)
val AccentDark = Color(0xFFFF6B4F)
val VegGreen = Color(0xFF1E8E3E)
val NonVegRed = Color(0xFFB3261E)

private val Light = lightColorScheme(
    primary = Color(0xFF141414),          // ink: primary buttons are black, not orange
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1EEEA),
    onPrimaryContainer = Color(0xFF141414),
    secondary = Color(0xFF6E6A66),
    // Selected chips and segmented buttons use these; keep them neutral (no Material lavender).
    secondaryContainer = Color(0xFFE9E5E0),
    onSecondaryContainer = Color(0xFF141414),
    tertiary = Accent,
    background = Color(0xFFFBFAF8),
    onBackground = Color(0xFF141414),
    surface = Color(0xFFFBFAF8),
    onSurface = Color(0xFF141414),
    surfaceVariant = Color(0xFFF1EEEA),
    onSurfaceVariant = Color(0xFF6E6A66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F4F1),
    surfaceContainer = Color(0xFFF3F0EC),
    surfaceContainerHigh = Color(0xFFEDE9E5),
    outline = Color(0xFFDCD7D2),
    outlineVariant = Color(0xFFECE8E4),
    error = NonVegRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF4F2EF),
    onPrimary = Color(0xFF141414),
    primaryContainer = Color(0xFF242220),
    onPrimaryContainer = Color(0xFFF4F2EF),
    secondary = Color(0xFF9A958F),
    secondaryContainer = Color(0xFF2C2A28),
    onSecondaryContainer = Color(0xFFF4F2EF),
    tertiary = AccentDark,
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF4F2EF),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFF4F2EF),
    surfaceVariant = Color(0xFF1C1B1A),
    onSurfaceVariant = Color(0xFF9A958F),
    surfaceContainerLowest = Color(0xFF080808),
    surfaceContainerLow = Color(0xFF141414),
    surfaceContainer = Color(0xFF181817),
    surfaceContainerHigh = Color(0xFF201F1E),
    outline = Color(0xFF3A3836),
    outlineVariant = Color(0xFF262422),
    error = Color(0xFFFFB4AB),
)

private val base = Typography()
private val AppTypography = base.copy(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.2.sp),
)

/** The dish name on a feed card. */
val CardTitle = TextStyle(fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)

@Composable
fun FoodFeedTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = AppTypography, content = content)
}

/** The accent for the current theme. */
@Composable
fun accent(): Color = MaterialTheme.colorScheme.tertiary
