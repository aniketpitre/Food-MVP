package app.worthabite

import app.worthabite.data.DishCard
import app.worthabite.data.RestaurantRef
import app.worthabite.ui.components.DishFilter
import app.worthabite.ui.components.DishTags
import app.worthabite.ui.components.OpenHours
import app.worthabite.ui.dish.ratingWhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Opening hours, filters and tags: the details people asked for in the AI user test. */
class DetailsLogicTest {
    // 2026-11-09 is a Monday.
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 9 + day, h, m)

    @Test
    fun openNowFromSimpleHours() {
        val s = OpenHours.stateAt("Mo-Su 08:00-22:00", at(0, 12))!!
        assertTrue(s.open)
        assertEquals("Open now · until 10 pm", s.label)
        val closed = OpenHours.stateAt("Mo-Su 08:00-22:00", at(0, 23))!!
        assertFalse(closed.open)
        assertEquals("Closed now · opens tomorrow 8 am", closed.label)
        assertEquals("Closed now · opens 8 am", OpenHours.stateAt("Mo-Su 08:00-22:00", at(0, 6, 30))!!.label)
    }

    @Test
    fun daysOffSplitHoursAndLateNights() {
        val h = "Mo-Sa 09:00-21:00; Su off"
        assertFalse(OpenHours.stateAt(h, at(6, 12))!!.open) // Sunday
        assertEquals("Closed now · opens tomorrow 9 am", OpenHours.stateAt(h, at(6, 12))!!.label)
        val split = "Mo-Fr 11:00-15:00,19:00-23:30"
        assertTrue(OpenHours.stateAt(split, at(1, 12))!!.open)
        assertEquals("Closed now · opens 7 pm", OpenHours.stateAt(split, at(1, 16))!!.label)
        // Open past midnight: still open at 1 am the next day.
        val late = "Mo-Su 18:00-02:00"
        assertTrue(OpenHours.stateAt(late, at(2, 1))!!.open)
        assertEquals("Open now · until 2 am", OpenHours.stateAt(late, at(2, 1))!!.label)
        assertEquals("Open 24 hours", OpenHours.stateAt("24/7", at(3, 3))!!.label)
    }

    @Test
    fun unreadableHoursAreUnknownNotGuessed() {
        assertNull(OpenHours.stateAt("sunrise till late", at(0, 12)))
        assertNull(OpenHours.parse(""))
    }

    @Test
    fun hoursPickerWritesWhatTheServerAccepts() {
        assertEquals("Mo-Su 09:00-22:00", OpenHours.build("Mo-Su", "09:00", "22:00", false))
        assertEquals("Mo-Sa 09:00-21:00; Su off", OpenHours.build("Mo-Sa", "09:00", "21:00", false))
        assertEquals("Mo-Fr 10:00-18:00; Sa,Su off", OpenHours.build("Mo-Fr", "10:00", "18:00", false))
        assertEquals("24/7", OpenHours.build("Mo-Su", "", "", true))
        // And it reads back what it wrote.
        assertFalse(OpenHours.stateAt("Mo-Fr 10:00-18:00; Sa,Su off", at(5, 12))!!.open)
    }

    private fun dish(id: String, veg: Boolean?, price: Int?, dist: Int?, tags: List<String> = emptyList()) =
        DishCard(id = id, name = id, isVeg = veg, priceInr = price, distanceM = dist, tags = tags, restaurant = RestaurantRef(id = "r", name = "R"))

    @Test
    fun filtersKeepOnlyWhatMatches() {
        val list = listOf(
            dish("a", true, 80, 900, listOf("jain")),
            dish("b", false, 150, 200, listOf("spicy")),
            dish("c", true, null, 50),
            dish("d", true, 450, null, listOf("healthy")),
        )
        assertEquals(listOf("a", "b", "c", "d"), DishFilter().apply(list).map { it.id })
        assertEquals(listOf("a", "c", "d"), DishFilter(vegOnly = true).apply(list).map { it.id })
        assertEquals(listOf("a"), DishFilter(maxPrice = 100).apply(list).map { it.id }) // no price, no match
        assertEquals(listOf("c", "b", "a", "d"), DishFilter(nearest = true).apply(list).map { it.id })
        assertEquals(listOf("a"), DishFilter(tag = "jain").apply(list).map { it.id })
        assertFalse(DishFilter().active)
        assertTrue(DishFilter(tag = "spicy").active)
    }

    @Test
    fun spicyAndMildExcludeEachOther() {
        assertEquals(setOf("jain", "mild"), DishTags.toggle(setOf("jain", "spicy"), "mild"))
        assertEquals(setOf("spicy"), DishTags.toggle(setOf("mild"), "spicy"))
        assertEquals(emptySet<String>(), DishTags.toggle(setOf("healthy"), "healthy"))
    }

    @Test
    fun ratingLineSaysWhatTheNumbersAre() {
        assertTrue(ratingWhy(0, 0).startsWith("No verdicts yet"))
        assertTrue(ratingWhy(1, 0).startsWith("100% of 1 person who tried it loved it"))
        assertTrue(ratingWhy(28, 6).startsWith("82% of 34 people who tried it loved it"))
    }
}
