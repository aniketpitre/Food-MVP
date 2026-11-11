package app.worthabite

import app.worthabite.ui.components.tabAfterSwipe
import app.worthabite.ui.components.tabSwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Swiping between the bottom tabs, as on Instagram. */
class TabSwipeTest {
    private val distance = 200f
    private val fling = 2500f

    @Test
    fun aLongSidewaysDragMovesOneTab() {
        assertEquals(1, tabSwipeDirection(dx = -250f, dy = 20f, velocityX = -300f, distance, fling))
        assertEquals(-1, tabSwipeDirection(dx = 250f, dy = -30f, velocityX = 300f, distance, fling))
    }

    @Test
    fun aShortQuickFlickAlsoCounts() {
        assertEquals(1, tabSwipeDirection(dx = -90f, dy = 5f, velocityX = -3000f, distance, fling))
        // Too short even when quick, or quick the other way: no move.
        assertEquals(0, tabSwipeDirection(dx = -40f, dy = 0f, velocityX = -3000f, distance, fling))
        assertEquals(0, tabSwipeDirection(dx = -90f, dy = 0f, velocityX = 3000f, distance, fling))
    }

    @Test
    fun mostlyVerticalOrShortDragsStay() {
        assertEquals(0, tabSwipeDirection(dx = -250f, dy = 200f, velocityX = -3000f, distance, fling))
        assertEquals(0, tabSwipeDirection(dx = -120f, dy = 0f, velocityX = -100f, distance, fling))
    }

    @Test
    fun tabsStopAtEitherEnd() {
        val tabs = listOf("home", "feed", "add", "activity", "me")
        assertEquals("feed", tabAfterSwipe(tabs, "home", 1))
        assertEquals("activity", tabAfterSwipe(tabs, "me", -1))
        assertNull(tabAfterSwipe(tabs, "home", -1))
        assertNull(tabAfterSwipe(tabs, "me", 1))
        assertNull(tabAfterSwipe(tabs, "dish/1", 1))
    }
}
