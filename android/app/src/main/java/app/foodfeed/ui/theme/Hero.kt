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

/** Marks this box as the photo of [dishId] for the hero morph. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.dishHero(dishId: String, corner: Dp = 28.dp): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@dishHero.sharedBounds(
            sharedContentState = rememberSharedContentState("dish-hero-$dishId"),
            animatedVisibilityScope = nav,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            boundsTransform = HeroBounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(corner)),
        )
    }
}
