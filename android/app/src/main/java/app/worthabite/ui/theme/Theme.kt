package app.worthabite.ui.theme

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
import app.worthabite.R

/*
 * WorthAbite's look, taken from its logo: a white frying pan with a bite out of it on a
 * charcoal tile, a saffron-yellow sizzle and "Abite", and a chef's hat with a paprika
 * stripe on a beige band. The app keeps that calm: warm cream and beige paper, charcoal
 * ink, and the colours used sparingly, so it reads as food, not as a taxi.
 *
 * Colour roles:
 *  - primary: charcoal (light) / cream (dark). Buttons, links, focused fields: always readable.
 *  - secondary = Saffron: highlights only. The tab blob, selected chips, badges, "Abite".
 *  - tertiary = Paprika: love. The heart and its count.
 *  - Beige: price tags and highlight stickers. Mint: veg, "tried".
 */

/** The logo's sizzle and the "Abite" in the wordmark. */
val Saffron = Color(0xFFF5C518)
/** Saffron as text or lines on cream (readable where yellow alone isn't). */
val SaffronDeep = Color(0xFF9A7400)
/** The stripe on the chef's hat: the colour of love (the heart). */
val Paprika = Color(0xFFE2531E)
val PaprikaBright = Color(0xFFFF7A4A)
/** The hat's band: stickers and soft highlights. */
val Turmeric = Color(0xFFEED3AE)
val Mint = Color(0xFF13A05B)
val Lagoon = Color(0xFF16B3A6)
/** The logo's tile: charcoal ink. */
val Ink = Color(0xFF151413)
val Cream = Color(0xFFFBF7EF)

val VegGreen = Mint
val NonVegRed = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Ink,
    onPrimary = Cream,
    primaryContainer = Color(0xFFF1E6D6),
    onPrimaryContainer = Ink,
    secondary = Saffron,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFFFF1C4),
    onSecondaryContainer = Color(0xFF3A2C00),
    tertiary = Paprika,
    onTertiary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF2EADF),
    onSurfaceVariant = Color(0xFF6A6259),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F1E8),
    surfaceContainer = Color(0xFFF3EBE0),
    surfaceContainerHigh = Color(0xFFEFE4D5),
    surfaceContainerHighest = Color(0xFFE9DCC9),
    inverseSurface = Ink,
    inverseOnSurface = Cream,
    outline = Color(0xFFDCCFBD),
    outlineVariant = Color(0xFFEDE3D6),
    error = NonVegRed,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF6EFE3),
    onPrimary = Ink,
    primaryContainer = Color(0xFF34302A),
    onPrimaryContainer = Color(0xFFF6EFE3),
    secondary = Saffron,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF3B3112),
    onSecondaryContainer = Color(0xFFFFE38A),
    tertiary = PaprikaBright,
    onTertiary = Ink,
    background = Ink,
    onBackground = Color(0xFFF6F1EA),
    surface = Ink,
    onSurface = Color(0xFFF6F1EA),
    surfaceVariant = Color(0xFF2A2725),
    onSurfaceVariant = Color(0xFFB8AFA4),
    surfaceContainerLowest = Color(0xFF0D0C0B),
    surfaceContainerLow = Color(0xFF1C1A18),
    surfaceContainer = Color(0xFF221F1D),
    surfaceContainerHigh = Color(0xFF2B2826),
    surfaceContainerHighest = Color(0xFF34302D),
    inverseSurface = Color(0xFFF6F1EA),
    inverseOnSurface = Ink,
    outline = Color(0xFF4A4541),
    outlineVariant = Color(0xFF34312E),
    error = Color(0xFFFFB4AB),
)

/** Poppins: the WorthAbite wordmark's typeface (SIL Open Font License). */
val WordmarkFamily = FontFamily(
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
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
