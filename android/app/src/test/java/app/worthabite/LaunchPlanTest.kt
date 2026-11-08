package app.worthabite

import app.worthabite.data.AppConfig
import app.worthabite.data.CravingResult
import app.worthabite.data.DishCard
import app.worthabite.data.Rating
import app.worthabite.data.RestaurantRef
import app.worthabite.ui.diary.diaryMonth
import app.worthabite.ui.diary.diaryVerdict
import app.worthabite.ui.feed.FeedTab
import app.worthabite.ui.feed.FeedUiState
import app.worthabite.ui.home.bestLink
import app.worthabite.ui.home.isEarlyDays
import app.worthabite.ui.home.shareText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The launch plan in the app: focus mode, sharing a craving list, early days, the diary. */
class LaunchPlanTest {
    private fun card(name: String, place: String, loves: Int, passes: Int) = DishCard(
        id = "$name-$place", name = name, restaurant = RestaurantRef(id = place, name = place),
        rating = Rating(loves, passes, if (loves + passes > 0) Math.round(100f * loves / (loves + passes)) else null),
    )

    @Test
    fun focusModeKeepsExploreToTopNearbyAndPlaces() {
        val focused = FeedUiState(signedIn = true, focus = true)
        assertEquals(listOf(FeedTab.Top, FeedTab.Nearby, FeedTab.Places), focused.tabs)
        assertEquals(FeedTab.entries, FeedUiState(signedIn = true, focus = false).tabs)
        assertFalse(AppConfig().hides(AppConfig.STORIES))
        assertTrue(AppConfig(focusMode = true, hidden = listOf(AppConfig.STORIES)).hides(AppConfig.STORIES))
    }

    @Test
    fun shareTextListsTheTopFiveWithVerdictsAndTheLink() {
        val r = CravingResult(
            query = "pav bhaji", label = "Pav Bhaji",
            results = listOf(card("Pav Bhaji", "Sardar", 9, 1), card("Pav Bhaji", "Bhagat", 1, 0), card("Butter Pav Bhaji", "New Stall", 0, 0)),
        )
        val link = bestLink("https://api.example.com/", 7, "pav bhaji")
        assertEquals("https://api.example.com/best?city=7&q=pav%20bhaji", link)
        val text = shareText(r, "Nashik", link)
        assertEquals(
            "Best Pav Bhaji in Nashik, ranked by people who ate it:\n" +
                "1. Pav Bhaji at Sardar (90% loved, 10 votes)\n" +
                "2. Pav Bhaji at Bhagat (100% loved, 1 vote)\n" +
                "3. Butter Pav Bhaji at New Stall\n\nFull list: $link",
            text,
        )
        assertEquals("https://x/best?q=misal", bestLink("https://x", null, "misal"))
    }

    @Test
    fun earlyDaysWhileFewVotes() {
        val few = CravingResult(results = listOf(card("A", "1", 3, 0), card("A", "2", 2, 0), card("A", "3", 1, 0)))
        assertTrue(isEarlyDays(few))
        val busy = CravingResult(results = listOf(card("A", "1", 8, 1), card("A", "2", 3, 0), card("A", "3", 0, 0)))
        assertFalse(isEarlyDays(busy))
        assertTrue(isEarlyDays(CravingResult(results = listOf(card("A", "1", 50, 0)))))
    }

    @Test
    fun diaryGroupsByMonthAndNamesVerdicts() {
        val ist = ZoneId.of("Asia/Kolkata")
        assertEquals("November 2026", diaryMonth("2026-11-08T10:00:00.000Z", ist))
        // Just after midnight in India on 1 December is still 30 November in UTC.
        assertEquals("December 2026", diaryMonth("2026-11-30T19:00:00.000Z", ist))
        assertEquals("", diaryMonth("not a date", ist))
        assertEquals("Loved it", diaryVerdict("loved"))
        assertEquals("Not for me", diaryVerdict("not_for_me"))
        assertNull(diaryVerdict(null))
    }
}
