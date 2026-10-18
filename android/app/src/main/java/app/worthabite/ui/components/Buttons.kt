package app.worthabite.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.draw.drawBehind
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
import app.worthabite.data.Engagement
import app.worthabite.ui.theme.Ink
import app.worthabite.ui.theme.Mint
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.RollingNumber
import app.worthabite.ui.theme.Saffron
import app.worthabite.ui.theme.TextFamily
import app.worthabite.ui.theme.Turmeric
import app.worthabite.ui.theme.accent
import app.worthabite.ui.theme.pressScale
import kotlinx.coroutines.launch

/** The one filled button: a ember pill with ink text that squashes when pressed. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        interactionSource = interaction,
        shadowElevation = if (enabled) 2.dp else 0.dp,
        modifier = modifier.height(54.dp).pressScale(interaction, 0.95f),
    ) {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp))
        }
    }
}

/** Outlined pill for secondary actions. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
        interactionSource = interaction,
        modifier = modifier.height(54.dp).pressScale(interaction, 0.95f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** How an [EngageAction] is drawn. */
enum class ActionLook {
    /** Dark glass circle over a photo, white icon and label. */
    Glass,

    /** Rounded tile on a plain background, label underneath. */
    Tile,

    /** Just the icon, for compact rows. */
    Bare,
}

/** The colour an action fills with when it is on. */
@Composable
private fun fillFor(kind: Engagement?): Color = when (kind) {
    Engagement.Recommend -> accent()
    Engagement.Save -> Turmeric
    Engagement.Tried -> Mint
    Engagement.Pass -> MaterialTheme.colorScheme.onSurfaceVariant
    null -> Saffron
}

/**
 * Recommend / save / tried / share. Turning one on is a small celebration: the button
 * squashes, fills with its colour (chili, turmeric or mint), the icon springs back with a
 * wiggle, and a ring plus a spray of spice confetti bursts out. The count rolls like a
 * counter. Turning it off just drains the colour, quietly.
 */
@Composable
fun EngageAction(
    kind: Engagement?,
    active: Boolean,
    label: String,
    count: Int?,
    description: String,
    onClick: () -> Unit,
    look: ActionLook = ActionLook.Tile,
    iconSize: Dp = 24.dp,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill = fillFor(kind)
    val onFill = if (kind == Engagement.Save) Ink else Color.White
    val idleBg = when (look) {
        ActionLook.Glass -> Color.Black.copy(alpha = 0.30f)
        ActionLook.Tile -> MaterialTheme.colorScheme.surfaceContainer
        ActionLook.Bare -> Color.Transparent
    }
    val idleFg = if (look == ActionLook.Glass) Color.White else MaterialTheme.colorScheme.onSurface
    val bg by animateColorAsState(if (active && look != ActionLook.Bare) fill else idleBg, Motion.enter(Motion.MEDIUM), label = "bg")
    val fg by animateColorAsState(
        when {
            active && look == ActionLook.Bare -> if (kind == Engagement.Save) MaterialTheme.colorScheme.onSurface else fill
            active -> onFill
            else -> idleFg
        },
        Motion.enter(Motion.MEDIUM),
        label = "fg",
    )

    // Celebrate only on the off -> on transition, never on first composition.
    val pop = remember { Animatable(1f) }
    val wiggle = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    var previous by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active && !previous && kind == Engagement.Pass) {
            // "Not for me" is a quiet nod, never a celebration.
            launch { pop.snapTo(0.85f); pop.animateTo(1f, Motion.press()) }
        } else if (active && !previous) {
            launch { pop.snapTo(0.7f); pop.animateTo(1f, Motion.bouncy()) }
            launch { wiggle.snapTo(-18f); wiggle.animateTo(0f, Motion.pop()) }
            launch { burst.snapTo(0f); burst.animateTo(1f, tween(620, easing = LinearEasing)); burst.snapTo(0f) }
        }
        previous = active
    }

    val glyph = icon ?: when (kind) {
        Engagement.Recommend -> if (active) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder
        Engagement.Save -> if (active) AppIcons.Bookmark else AppIcons.BookmarkBorder
        Engagement.Tried -> if (active) Icons.Filled.Check else Icons.Outlined.CheckCircle
        Engagement.Pass -> if (active) AppIcons.ThumbDown else AppIcons.ThumbDownOutline
        null -> Icons.Outlined.Share
    }
    val burstColors = listOf(fill, Turmeric, Saffron, Mint)
    val lite = app.worthabite.ui.theme.LocalLiteMode.current
    val box = when (look) {
        ActionLook.Glass -> 48.dp
        ActionLook.Tile -> 56.dp
        ActionLook.Bare -> iconSize + 14.dp
    }
    val shape = if (look == ActionLook.Tile) RoundedCornerShape(18.dp) else CircleShape

    Column(
        Modifier
            .pressScale(interaction, 0.86f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(box)
                .drawBehind {
                    if (!lite) drawSpiceBurst(center, burst.value, burstColors, radius = size.minDimension * 0.95f, count = 10)
                }
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .background(bg, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                glyph, description, tint = fg,
                modifier = Modifier.size(iconSize).graphicsLayer {
                    rotationZ = wiggle.value
                    val s = 1f + (1f - pop.value) * 0.6f
                    scaleX = s
                    scaleY = s
                },
            )
        }
        if (look != ActionLook.Bare) {
            val shadow = if (look == ActionLook.Glass) Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 6f) else null
            val style = TextStyle(fontFamily = TextFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, shadow = shadow)
            val labelColor = if (look == ActionLook.Glass) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            Spacer(Modifier.height(4.dp))
            if (count != null && count > 0) {
                RollingNumber(count, style, labelColor, format = app.worthabite.ui.Format::compact)
            } else if (label.isNotEmpty()) {
                Text(label, style = style, color = labelColor)
            }
        }
    }
}

/** Fades a whole subtree in on first composition with a slight rise; used to stagger content. */
@Composable
fun Reveal(delayMillis: Int = 0, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, Motion.enter(520, delayMillis)) }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 48f
            val s = 0.98f + 0.02f * progress.value
            scaleX = s
            scaleY = s
        },
    ) { content() }
}
