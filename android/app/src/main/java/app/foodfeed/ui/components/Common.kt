package app.foodfeed.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.data.UserRef
import app.foodfeed.ui.Format
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.Saffron
import app.foodfeed.ui.theme.TextFamily
import app.foodfeed.ui.theme.NonVegRed
import app.foodfeed.ui.theme.VegGreen
import coil3.compose.AsyncImage

/** The Indian veg / non-veg mark, kept small and quiet. */
@Composable
fun VegMark(isVeg: Boolean?, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    if (isVeg == null) return
    val color = if (isVeg) VegGreen else NonVegRed
    Box(
        modifier
            .size(size)
            .border(1.2.dp, color, RoundedCornerShape(2.dp))
            .semantics { contentDescription = if (isVeg) "Vegetarian" else "Non-vegetarian" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.42f).background(color, CircleShape))
    }
}

/** Lets screenshot tests and previews supply images without a network. */
val LocalImageOverride = staticCompositionLocalOf<((String) -> ImageBitmap?)?> { null }

/** A photo served by our API, showing its dominant colour while it loads (Coil crossfades it in). */
@Composable
fun DishImage(
    url: String?,
    placeholderColor: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bg = Format.parseColor(placeholderColor, MaterialTheme.colorScheme.surfaceContainerHigh)
    Box(modifier.background(bg)) {
        val override = url?.let { LocalImageOverride.current?.invoke(it) }
        if (override != null) {
            Image(override, contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val AvatarTints = listOf(
    Color(0xFFFFD8BF), Color(0xFFFFEDB0), Color(0xFFCDEFD9), Color(0xFFC9EEEA), Color(0xFFE5D9FF), Color(0xFFFFD1D1),
)

/** Turns a server path ("/media/...") into a full URL. Provided at the app root; tests use paths as-is. */
val LocalMediaUrl = staticCompositionLocalOf<(String?) -> String?> { { it } }

/**
 * A person's face: their profile photo when they have one, otherwise their initials on a
 * warm pastel picked from the username, so each person keeps a colour.
 */
@Composable
fun Avatar(
    handle: String,
    displayName: String?,
    size: Dp = 32.dp,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    photo: String? = null,
) {
    val tint = AvatarTints[(handle.hashCode() and 0x7fffffff) % AvatarTints.size]
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Format.initials(handle, displayName),
            color = Ink,
            fontFamily = TextFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp,
        )
        if (photo != null) {
            val url = LocalMediaUrl.current(photo)
            val override = url?.let { LocalImageOverride.current?.invoke(it) }
            if (override != null) {
                Image(override, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (url != null) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** A small text tag. Over photos it is a dark glass chip; elsewhere it is plain muted text on a tint. */
@Composable
fun Tag(text: String, onPhoto: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (onPhoto) Color.Black.copy(alpha = 0.38f) else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Empty, error and sign-in states: the icon sits on a little plate that drops in with a
 * bounce, then the text rises underneath, then the action. At most one main action.
 */
@Composable
fun CenteredMessage(
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(900, easing = LinearEasing)) }
    val plate = MaterialTheme.colorScheme.surfaceContainerHigh
    val rim = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        val p = Motion.window(t.value, 0f, 0.45f) { Motion.backOut(it, 2f) }
                        scaleX = 0.4f + 0.6f * p
                        scaleY = 0.4f + 0.6f * p
                        alpha = (p * 2f).coerceAtMost(1f)
                        rotationZ = (1f - p) * -40f
                    }
                    .drawBehind {
                        drawCircle(plate)
                        drawCircle(rim, radius = size.minDimension * 0.36f)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon ?: AppIcons.Restaurant, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(10.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.graphicsLayer {
                    val p = Motion.window(t.value, 0.2f, 0.5f)
                    alpha = p
                    translationY = (1f - p) * 40f
                },
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (body != null) {
                    Text(
                        body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.graphicsLayer {
                        val p = Motion.window(t.value, 0.4f, 0.6f) { Motion.backOut(it) }
                        alpha = p.coerceIn(0f, 1f)
                        scaleX = 0.85f + 0.15f * p
                        scaleY = 0.85f + 0.15f * p
                    },
                ) { PrimaryButton(actionLabel, onAction) }
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) { Text(secondaryLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
    )
}

/** The logo: "food" in ink, "feed" in saffron, with a chili full stop. */
@Composable
fun Wordmark(large: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = ink)) { append("food") }
            withStyle(SpanStyle(color = Saffron)) { append("feed") }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) { append(".") }
        },
        style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
    )
}
