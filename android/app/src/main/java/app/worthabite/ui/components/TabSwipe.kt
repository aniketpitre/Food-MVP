package app.worthabite.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Which way a finished drag moves between the bottom tabs: +1 to the next tab (finger moved
 * right to left), -1 to the previous one, 0 to stay. A swipe must be mostly sideways and
 * either long enough or quick enough, like flicking between screens on Instagram.
 */
fun tabSwipeDirection(dx: Float, dy: Float, velocityX: Float, distance: Float, flingVelocity: Float): Int {
    if (abs(dx) < abs(dy) * 2f) return 0
    val far = abs(dx) >= distance
    val quick = abs(dx) >= distance / 3f && abs(velocityX) >= flingVelocity && (velocityX < 0) == (dx < 0)
    if (!far && !quick) return 0
    return if (dx < 0) 1 else -1
}

/** The tab a swipe lands on, or null at either end (no wrapping, as on Instagram). */
fun <T> tabAfterSwipe(tabs: List<T>, current: T, direction: Int): T? {
    val i = tabs.indexOf(current)
    if (i < 0 || direction == 0) return null
    return tabs.getOrNull(i + direction)
}

/**
 * Swipe left or right anywhere on a tab's screen to move to the neighbouring tab. It only
 * listens after everything inside has had its turn (the final pass): a carousel, the map, a
 * photo pager or a pinch that uses the gesture keeps it, and vertical scrolling wins over a
 * slightly sideways drag. So the screens behave exactly as before, plus the swipe.
 */
fun Modifier.swipeBetweenTabs(enabled: Boolean, onSwipe: (direction: Int) -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onSwipe)
    if (!enabled) return@composed this
    pointerInput(Unit) {
        val distance = 72.dp.toPx()
        val flingVelocity = 900.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val tracker = VelocityTracker().apply { addPointerInputChange(down) }
            var dx = 0f
            var dy = 0f
            // Something inside took the gesture (scrolled, panned, zoomed): leave it alone.
            // (Not judged on the press itself: every tappable card consumes that.)
            var taken = false
            // Once clearly sideways, the swipe is ours: consuming the move cancels a tap on
            // the card underneath (a full-screen dish card would otherwise open on release).
            var claimed = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.count { it.pressed } > 1) taken = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) taken = true
                dx += change.position.x - change.previousPosition.x
                dy += change.position.y - change.previousPosition.y
                tracker.addPointerInputChange(change)
                if (!taken && change.pressed && (claimed || (abs(dx) > slop && abs(dx) > abs(dy) * 2f))) {
                    claimed = true
                    change.consume()
                }
                if (!change.pressed) {
                    if (!taken) {
                        val dir = tabSwipeDirection(dx, dy, tracker.calculateVelocity().x, distance, flingVelocity)
                        if (dir != 0) latest(dir)
                    }
                    break
                }
            }
        }
    }
}
