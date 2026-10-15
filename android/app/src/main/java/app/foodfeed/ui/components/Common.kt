package app.foodfeed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.foodfeed.ui.theme.NonVegRed
import app.foodfeed.ui.theme.VegGreen
import coil3.compose.AsyncImage

/** The Indian veg / non-veg mark: a square with a dot (green) or triangle-ish dot (red). */
@Composable
fun VegMark(isVeg: Boolean?, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    if (isVeg == null) return
    val color = if (isVeg) VegGreen else NonVegRed
    Box(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(3.dp))
            .border(1.5.dp, color, RoundedCornerShape(3.dp))
            .semantics { contentDescription = if (isVeg) "Vegetarian" else "Non-vegetarian" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size * 0.45f).background(color, if (isVeg) CircleShape else RoundedCornerShape(1.dp)))
    }
}

/** Lets screenshot tests and previews supply images without a network. */
val LocalImageOverride = staticCompositionLocalOf<((String) -> ImageBitmap?)?> { null }

/** A photo served by our API with its dominant colour shown while it loads. */
@Composable
fun DishImage(
    url: String?,
    placeholderColor: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bg = Format.parseColor(placeholderColor, MaterialTheme.colorScheme.surfaceVariant)
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
        } else {
            Icon(
                AppIcons.Restaurant, null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center).size(40.dp),
            )
        }
    }
}

@Composable
fun Avatar(handle: String, displayName: String?, size: Dp = 32.dp, modifier: Modifier = Modifier, ring: Color? = null) {
    Box(
        modifier
            .size(size)
            .then(if (ring != null) Modifier.border(2.dp, ring, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(Format.avatarColor(handle)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            Format.initials(handle, displayName),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

/** Overlapping faces of recent recommenders, like Instagram's "liked by" strip. */
@Composable
fun AvatarStack(people: List<UserRef>, ringColor: Color, size: Dp = 22.dp, max: Int = 3) {
    Box {
        people.take(max).forEachIndexed { i, p ->
            Avatar(p.handle, p.displayName, size, Modifier.offset(x = (size * 0.62f) * i), ring = ringColor)
        }
    }
}

@Composable
fun Pill(text: String, background: Color, contentColor: Color, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = contentColor, modifier = Modifier.size(14.dp))
        Text(text, color = contentColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

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
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
