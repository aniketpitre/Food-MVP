package app.foodfeed.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.foodfeed.ui.theme.Motion
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

/** A plain initial-letter avatar. */
@Composable
fun Avatar(handle: String, displayName: String?, size: Dp = 32.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Format.initials(handle, displayName),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp,
        )
    }
}

/** Overlapping faces of recent recommenders. */
@Composable
fun AvatarStack(people: List<UserRef>, ringColor: Color, size: Dp = 20.dp, max: Int = 3) {
    Box {
        people.take(max).forEachIndexed { i, p ->
            Avatar(p.handle, p.displayName, size, Modifier.offset(x = (size * 0.66f) * i), ring = ringColor)
        }
    }
}

/** A small text tag. Over photos it is a dark glass chip; elsewhere it is plain muted text on a tint. */
@Composable
fun Tag(text: String, onPhoto: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (onPhoto) Color.Black.copy(alpha = 0.38f) else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * Empty, error and sign-in states: a quiet line of text that rises gently into place,
 * with at most one action. No big coloured circles.
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
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(Motion.enter(Motion.LONG)) + slideInVertically(Motion.enter(Motion.LONG)) { it / 6 },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(4.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (body != null) {
                    Text(
                        body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(actionLabel, onAction)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) { Text(secondaryLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** The logo is just the name, set tight. */
@Composable
fun Wordmark(large: Boolean = false) {
    Text(
        "foodfeed",
        style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
}
