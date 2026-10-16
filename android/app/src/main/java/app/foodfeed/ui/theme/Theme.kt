package app.foodfeed.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.R

/*
 * Design language: "street-food zine". Warm cream paper, tamarind-dark ink, a saffron
 * signature colour and a few loud spice accents used like stickers on a menu board.
 * Big characterful type (Bricolage Grotesque) does the talking; photos stay the hero.
 *
 * Colour roles:
 *  - Saffron: brand, primary buttons (ink text on saffron), selection, the liquid tab blob.
 *  - Chili: only "recommended" (the heart) and its count.
 *  - Turmeric: price tags and highlight stickers.
 *  - Mint: veg, "tried".
 */

val Saffron = Color(0xFFFF7A1A)
val SaffronDeep = Color(0xFFE85D04)
val Turmeric = Color(0xFFFFC23D)
val Chili = Color(0xFFE5362A)
val ChiliBright = Color(0xFFFF5A47)
val Mint = Color(0xFF13A05B)
val Lagoon = Color(0xFF16B3A6)
val Ink = Color(0xFF22140D)
val Cream = Color(0xFFFFF8F0)

val VegGreen = Mint
val NonVegRed = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Saffron,
    onPrimary = Ink,
    primaryContainer = Color(0xFFFFE2CC),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEDC2),
    onSecondaryContainer = Ink,
    tertiary = Chili,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF7E6D8),
    onSurfaceVariant = Color(0xFF7A6152),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF1E5),
    surfaceContainer = Color(0xFFFCEADB),
    surfaceContainerHigh = Color(0xFFF7E2D1),
    surfaceContainerHighest = Color(0xFFF1D9C6),
    inverseSurface = Ink,
    inverseOnSurface = Cream,
    outline = Color(0xFFE6CDBA),
    outlineVariant = Color(0xFFF2E1D3),
    error = NonVegRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF1A0F0A),
    primaryContainer = Color(0xFF4A2513),
    onPrimaryContainer = Color(0xFFFFE2CC),
    secondary = Color(0xFFFFC94D),
    onSecondary = Color(0xFF1A0F0A),
    secondaryContainer = Color(0xFF3D2E12),
    onSecondaryContainer = Color(0xFFFFF0C7),
    tertiary = ChiliBright,
    onTertiary = Color.White,
    background = Color(0xFF140D0A),
    onBackground = Color(0xFFFFF1E6),
    surface = Color(0xFF140D0A),
    onSurface = Color(0xFFFFF1E6),
    surfaceVariant = Color(0xFF2A1E18),
    onSurfaceVariant = Color(0xFFC2AA99),
    surfaceContainerLowest = Color(0xFF0D0806),
    surfaceContainerLow = Color(0xFF1C130F),
    surfaceContainer = Color(0xFF221813),
    surfaceContainerHigh = Color(0xFF2B1F19),
    surfaceContainerHighest = Color(0xFF34261F),
    inverseSurface = Color(0xFFFFF1E6),
    inverseOnSurface = Ink,
    outline = Color(0xFF4A3A30),
    outlineVariant = Color(0xFF33261F),
    error = Color(0xFFFFB4AB),
)

/** Bricolage Grotesque, cut at a large optical size for headlines. */
val DisplayFamily = FontFamily(
    Font(R.font.bricolage_display_extrabold, FontWeight.ExtraBold),
    Font(R.font.bricolage_display_extrabold, FontWeight.Black),
    Font(R.font.bricolage_display_bold, FontWeight.Bold),
)

/** Bricolage Grotesque at text size for everything else. */
val TextFamily = FontFamily(
    Font(R.font.bricolage_text_regular, FontWeight.Normal),
    Font(R.font.bricolage_text_medium, FontWeight.Medium),
    Font(R.font.bricolage_text_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_display_bold, FontWeight.Bold),
    Font(R.font.bricolage_display_extrabold, FontWeight.ExtraBold),
)

private val base = Typography()
private fun TextStyle.text() = copy(fontFamily = TextFamily)
private fun TextStyle.display(weight: FontWeight = FontWeight.ExtraBold, tracking: Float = -0.5f) =
    copy(fontFamily = DisplayFamily, fontWeight = weight, letterSpacing = tracking.sp)

private val AppTypography = Typography(
    displayLarge = base.displayLarge.display(tracking = -2f),
    displayMedium = base.displayMedium.display(tracking = -1.6f),
    displaySmall = base.displaySmall.display(tracking = -1.2f),
    headlineLarge = base.headlineLarge.display(tracking = -1f),
    headlineMedium = base.headlineMedium.display(tracking = -0.8f),
    headlineSmall = base.headlineSmall.display(tracking = -0.5f),
    titleLarge = base.titleLarge.display(FontWeight.Bold, -0.3f),
    titleMedium = base.titleMedium.text().copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = base.titleSmall.text().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.text().copy(letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.text().copy(letterSpacing = 0.sp),
    bodySmall = base.bodySmall.text().copy(letterSpacing = 0.1.sp),
    labelLarge = base.labelLarge.text().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.text().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = base.labelSmall.text().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The dish name on a feed card: poster-sized and tight. */
val CardTitle = TextStyle(
    fontFamily = DisplayFamily, fontWeight = FontWeight.ExtraBold,
    fontSize = 36.sp, lineHeight = 36.sp, letterSpacing = (-1.2).sp,
)

/** Small all-caps kicker above headings ("GOOD EVENING", "MORE AT …"). */
val Kicker = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.6.sp)

@Composable
fun FoodFeedTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** The recommend colour for the current theme. */
@Composable
@ReadOnlyComposable
fun accent(): Color = MaterialTheme.colorScheme.tertiary
