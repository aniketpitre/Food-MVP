package app.foodfeed.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Hero transitions: a dish photo morphs from wherever you tapped it (feed card, list row,
 * tile) into the big photo on the dish page, and back again. Screens get the scopes from
 * these locals; without them (previews, tests) the modifier does nothing.
 */

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** A soft spring for the morph: no bounce, so a photo never wobbles past its frame. */
@OptIn(ExperimentalSharedTransitionApi::class)
private val HeroBounds = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.9f, stiffness = 320f, visibilityThreshold = Rect.VisibilityThreshold)
}

/**
 * Which copy of a dish's photo was tapped last, per dish. Home can show the same dish in
 * several sections at once, and a profile grid can show several photos of one dish; only
 * the tapped copy may carry the shared key, or the copies get matched to each other and
 * jump around while the list scrolls.
 */
object HeroOrigin {
    private val tapped = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun tap(dishId: String, source: String) {
        tapped[dishId] = source
    }

    fun isTapped(dishId: String, source: String) = tapped[dishId] == source
}

/**
 * Marks this box as the photo of [dishId] for the hero morph. In lists where a dish can
 * appear more than once, pass a [source] unique to this copy (e.g. "home:nearby") and call
 * [HeroOrigin.tap] when it's tapped.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.dishHero(dishId: String, corner: Dp = 28.dp, source: String? = null): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    val key = if (source == null || HeroOrigin.isTapped(dishId, source)) "dish-hero-$dishId" else "dish-hero-$dishId@$source"
    return with(shared) {
        this@dishHero.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = nav,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            boundsTransform = HeroBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner)),
        )
    }
}
