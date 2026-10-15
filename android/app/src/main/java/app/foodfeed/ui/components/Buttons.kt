package app.foodfeed.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.data.Engagement
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.RollingNumber
import app.foodfeed.ui.theme.accent
import app.foodfeed.ui.theme.pressScale

/** The one filled button style: ink pill, presses down slightly. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        interactionSource = interaction,
        modifier = modifier.height(50.dp).pressScale(interaction, 0.97f),
    ) {
        Row(Modifier.padding(horizontal = 22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Hairline outlined button for secondary actions. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        interactionSource = interaction,
        modifier = modifier.height(50.dp).pressScale(interaction, 0.97f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Icon + label action (recommend, save, tried, share). Pops with a small overshoot when it
 * turns on, the heart takes the accent colour, and the count rolls like a counter.
 *
 * @param onPhoto draws white with a soft shadow so it reads over any photo.
 */
@Composable
fun EngageAction(
    kind: Engagement?,
    active: Boolean,
    label: String,
    count: Int?,
    description: String,
    onClick: () -> Unit,
    onPhoto: Boolean = false,
    iconSize: Dp = 27.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurface
    val tint by animateColorAsState(
        if (active && kind == Engagement.Recommend) accent() else base,
        Motion.enter(Motion.SHORT),
        label = "tint",
    )
    // Pop only on the off -> on transition, never on first composition.
    val pop = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !previous) {
            pop.snapTo(0.72f)
            pop.animateTo(1f, Motion.pop())
        }
        previous = active
    }
    val icon = when (kind) {
        Engagement.Recommend -> if (active) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder
        Engagement.Save -> if (active) AppIcons.Bookmark else AppIcons.BookmarkBorder
        Engagement.Tried -> if (active) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle
        null -> Icons.Outlined.Share
    }
    val shadow = if (onPhoto) Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 8f) else null
    Column(
        Modifier
            .pressScale(interaction, 0.86f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(iconSize + 6.dp), contentAlignment = Alignment.Center) {
            if (onPhoto) {
                // A blurred dark copy behind the icon acts as its shadow.
                Icon(icon, null, tint = Color.Black.copy(alpha = 0.25f), modifier = Modifier.size(iconSize).graphicsLayer { translationY = 2f })
            }
            Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
        }
        val style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = shadow)
        if (count != null && count > 0) {
            RollingNumber(count, style, if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, format = app.foodfeed.ui.Format::compact)
        } else if (label.isNotEmpty()) {
            Text(label, style = style, color = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Fades a whole subtree in on first composition with a slight rise; used to stagger content. */
@Composable
fun Reveal(delayMillis: Int = 0, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, Motion.enter(Motion.LONG, delayMillis)) }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 24f
        },
    ) { content() }
}
