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
 * WorthAbite's look, taken from its logo: a charcoal frying pan with a bite out of it, a
 * red-orange sizzle and a chef's hat on warm cream. Charcoal ink, ember accents, cream
 * paper. Big characterful type (Bricolage Grotesque) does the talking; photos stay the hero.
 *
 * Colour roles:
 *  - Ember: brand, primary buttons (charcoal text on ember), selection, the liquid tab blob.
 *  - Chili: only "recommended" (the heart) and its count.
 *  - Turmeric: price tags and highlight stickers.
 *  - Mint: veg, "tried".
 */

/** The logo's sizzle and the "Abite" in the wordmark. */
val EmberLogo = Color(0xFFF04E23)
/** Ember for surfaces with charcoal text on them (4.8:1). */
val Ember = Color(0xFFF47040)
val EmberDeep = Color(0xFFD23D17)
val Turmeric = Color(0xFFFFC23D)
val Chili = Color(0xFFD61F45)
val ChiliBright = Color(0xFFFF5C7A)
val Mint = Color(0xFF13A05B)
val Lagoon = Color(0xFF16B3A6)
/** The pan: charcoal ink. */
val Ink = Color(0xFF2B2B2B)
val Cream = Color(0xFFFBF6EF)

val VegGreen = Mint
val NonVegRed = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Ember,
    onPrimary = Ink,
    primaryContainer = Color(0xFFFCE1D6),
    onPrimaryContainer = Color(0xFF3B1206),
    secondary = Color(0xFF4A4744),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE6DD),
    onSecondaryContainer = Ink,
    tertiary = Chili,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EBE3),
    onSurfaceVariant = Color(0xFF6B645D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EB),
    surfaceContainer = Color(0xFFF2ECE4),
    surfaceContainerHigh = Color(0xFFECE5DC),
    surfaceContainerHighest = Color(0xFFE5DDD3),
    inverseSurface = Ink,
    inverseOnSurface = Cream,
    outline = Color(0xFFDCD3C8),
    outlineVariant = Color(0xFFEAE3DA),
    error = NonVegRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF7A4D),
    onPrimary = Color(0xFF1A0C06),
    primaryContainer = Color(0xFF4A1D0D),
    onPrimaryContainer = Color(0xFFFFDCCF),
    secondary = Color(0xFFD6D0C9),
    onSecondary = Color(0xFF1B1A19),
    secondaryContainer = Color(0xFF34312E),
    onSecondaryContainer = Color(0xFFF1ECE6),
    tertiary = ChiliBright,
    onTertiary = Color.White,
    background = Color(0xFF141312),
    onBackground = Color(0xFFF6F1EA),
    surface = Color(0xFF141312),
    onSurface = Color(0xFFF6F1EA),
    surfaceVariant = Color(0xFF2A2826),
    onSurfaceVariant = Color(0xFFB5ADA4),
    surfaceContainerLowest = Color(0xFF0E0D0C),
    surfaceContainerLow = Color(0xFF1B1A18),
    surfaceContainer = Color(0xFF211F1D),
    surfaceContainerHigh = Color(0xFF2A2826),
    surfaceContainerHighest = Color(0xFF33302D),
    inverseSurface = Color(0xFFF6F1EA),
    inverseOnSurface = Ink,
    outline = Color(0xFF4A4541),
    outlineVariant = Color(0xFF34312E),
    error = Color(0xFFFFB4AB),
)

/** Poppins SemiBold: the WorthAbite wordmark's typeface (SIL Open Font License). */
val WordmarkFamily = FontFamily(Font(R.font.poppins_semibold, FontWeight.SemiBold))

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
fun WorthAbiteTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
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
