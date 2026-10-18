package app.worthabite.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

/**
 * One motion vocabulary for the whole app, so everything moves like it belongs together.
 *
 * - Things that respond to a finger use springs (they can be interrupted mid-flight and
 *   never jump). Taps settle in ~250 ms; anything that celebrates (heart, save, stickers)
 *   overshoots, because it should feel happy.
 * - Things that enter or leave use Material's emphasized curves: fast out, long gentle landing.
 * - Content is choreographed, not dumped: when a card lands, the photo settles first, then
 *   the sticker slaps on, the title rises out of a mask and the rest follows 60-90 ms apart.
 *   One progress value drives each sequence ([window] cuts it into staggered steps), so a
 *   whole sequence costs a single animation and no recomposition.
 * - Nothing loops except the loading shimmer.
 */
/**
 * Lite mode (low-memory phones by default, or switched on in Settings): skips blur, confetti
 * and the card landing sequence, so the app stays smooth on any phone.
 */
val LocalLiteMode = androidx.compose.runtime.staticCompositionLocalOf { false }

object Motion {
    /** Material 3 emphasized curves. */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT = 150
    const val MEDIUM = 280
    const val LONG = 400

    /** Press feedback: quick and critically damped, no wobble. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1400f)

    /** A playful pop with a small overshoot, used for the heart and the save mark. */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.42f, stiffness = 520f)

    /** Layout and indicator movement: smooth with a hint of life. */
    fun <T> glide(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    fun <T> enter(duration: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> =
        tween(duration, delay, EmphasizedDecelerate)

    fun <T> exit(duration: Int = SHORT): FiniteAnimationSpec<T> = tween(duration, 0, EmphasizedAccelerate)

    /** Bigger overshoot for stickers and celebratory moments. */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 380f)

    /** Leading edge of the liquid tab blob: quick. */
    fun <T> liquidLead(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.75f, stiffness = 900f)

    /** Trailing edge of the liquid tab blob: lags behind, so the blob stretches while it travels. */
    fun <T> liquidTrail(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 260f)

    /**
     * The slice of a 0..1 sequence that belongs to one step: 0 before [start], 1 after
     * [start] + [length], eased in between.
     */
    fun window(t: Float, start: Float, length: Float, easing: (Float) -> Float = { EmphasizedDecelerate.transform(it) }): Float =
        easing(((t - start) / length).coerceIn(0f, 1f))

    /** Ease-out with overshoot ("back out"); goes a little past 1 before landing. */
    fun backOut(x: Float, overshoot: Float = 1.70158f): Float {
        val c3 = overshoot + 1f
        val p = x - 1f
        return 1f + c3 * p * p * p + overshoot * p * p
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}

/**
 * Shrinks slightly while pressed, like a physical button. Pass the same interaction source
 * to the clickable so the press is tracked.
 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.94f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, Motion.press(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A number that rolls up when it grows and down when it shrinks, like a counter. */
@Composable
fun RollingNumber(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, format: (Int) -> String = { it.toString() }) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val up = targetState > initialState
            val spec: ContentTransform =
                (slideInVertically(Motion.enter(220)) { h -> if (up) h else -h } + fadeIn(Motion.enter(220)))
                    .togetherWith(slideOutVertically(Motion.exit(140)) { h -> if (up) -h else h } + fadeOut(Motion.exit(120)))
            spec.using(SizeTransform(clip = false))
        },
        modifier = modifier,
        label = "rolling-number",
    ) { n ->
        androidx.compose.material3.Text(format(n), style = style, color = color)
    }
}

/** Loading placeholder: a soft light sweep, slow enough to feel calm. */
fun Modifier.shimmer(shape: Shape): Modifier = composed {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerLow
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "shimmer-x",
    )
    background(base, shape).drawWithContent {
        val w = size.width
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight.copy(alpha = 0.9f), Color.Transparent),
                start = Offset(w * x - w * 0.5f, 0f),
                end = Offset(w * x + w * 0.5f, size.height),
            ),
        )
        drawContent()
    }
}

@Composable
fun SkeletonBlock(modifier: Modifier, shape: Shape) {
    Box(modifier.shimmer(shape))
}
