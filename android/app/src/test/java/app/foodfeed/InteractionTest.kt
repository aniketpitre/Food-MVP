package app.foodfeed

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.foodfeed.data.Cover
import app.foodfeed.data.DishCard
import app.foodfeed.data.DishStats
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.data.MyActions
import app.foodfeed.data.RestaurantRef
import app.foodfeed.data.toggled
import app.foodfeed.ui.components.EngageAction
import app.foodfeed.ui.feed.DishPage
import app.foodfeed.ui.feed.FeedTab
import app.foodfeed.ui.feed.FeedTabs
import app.foodfeed.ui.theme.WorthAbiteTheme
import app.foodfeed.data.Comment
import app.foodfeed.data.UserRef as Person
import app.foodfeed.ui.comments.CommentsContent
import app.foodfeed.ui.comments.CommentsUiState
import app.foodfeed.ui.theme.RollingNumber
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real composables with touch input and a controlled animation clock:
 * gestures fire the right actions exactly once, state changes show up, and animations
 * run frame by frame without crashing (with frames captured to review the motion).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class InteractionTest {
    @get:Rule
    val compose = createComposeRule()

    private val card = DishCard(
        id = "d1", name = "Kolhapuri Misal", priceInr = 90, isVeg = true,
        restaurant = RestaurantRef("r1", "Godavari Misal House", "Panchavati"),
        cover = Cover("p1", "/media/godavari_kolhapuri_misal", "/media/godavari_kolhapuri_misal", "#c2410c"),
        stats = DishStats(recommenders = 12, saves = 3),
        distanceM = 1200,
    )

    private fun setContent(content: @Composable () -> Unit) = compose.setContent {
        WorthAbiteTheme { Surface(color = MaterialTheme.colorScheme.background) { content() } }
    }

    @Test
    fun doubleTapRecommendsOnceAndTapOpens() {
        val toggles = mutableListOf<Engagement>()
        var opened = 0
        var state by mutableStateOf(EngagementState(card.stats, card.me))
        setContent {
            Box(Modifier.width(380.dp).height(700.dp).testTag("card")) {
                DishPage(
                    card = card, engagement = state, imageUrl = null,
                    onOpen = { opened++ },
                    onRestaurant = {},
                    onToggle = { k -> toggles += k; state = state.toggled(k) },
                    onShare = {},
                )
            }
        }
        compose.onNodeWithTag("card").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(listOf(Engagement.Recommend), toggles)
        assertEquals(13, state.stats.recommenders)
        // A second double-tap shows the heart again but never un-recommends.
        compose.onNodeWithTag("card").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(1, toggles.size)
        compose.onNodeWithContentDescription("Remove recommendation").assertIsDisplayed()
        // A single tap only counts once the double-tap window (300 ms) has passed.
        compose.onNodeWithText("Kolhapuri Misal").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun railButtonsFireTheirOwnAction() {
        val toggles = mutableListOf<Engagement>()
        var shared = 0
        setContent {
            Box(Modifier.width(380.dp).height(700.dp)) {
                DishPage(card, EngagementState(card.stats, MyActions()), null, {}, {}, { toggles += it }, { shared++ })
            }
        }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.onNodeWithContentDescription("I tried this").performClick()
        compose.onNodeWithContentDescription("Send to friends").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Engagement.Save, Engagement.Tried), toggles)
        assertEquals(1, shared)
    }

    @Test
    fun tabsSwitch() {
        var selected by mutableStateOf(FeedTab.Top)
        setContent { FeedTabs(selected) { selected = it } }
        compose.onNodeWithText("Trending").performClick()
        compose.waitForIdle()
        assertEquals(FeedTab.Trending, selected)
        compose.onNodeWithText("Nearby").performClick()
        compose.waitForIdle()
        assertEquals(FeedTab.Nearby, selected)
    }

    @Test
    fun countRollsToTheNewValue() {
        var n by mutableIntStateOf(41)
        setContent { RollingNumber(n, TextStyle.Default, Color.Black) }
        compose.onNodeWithText("41").assertIsDisplayed()
        n = 42
        compose.waitForIdle()
        compose.onNodeWithText("42").assertIsDisplayed()
    }

    @Test
    fun recommendButtonPopsAndTurnsAccent() {
        var active by mutableStateOf(false)
        setContent {
            Box(Modifier.width(120.dp).height(90.dp)) {
                EngageAction(Engagement.Recommend, active, "Recommend", 12, if (active) "Remove recommendation" else "Recommend", { active = !active })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Recommend").performClick()
        compose.mainClock.advanceTimeBy(16)
        compose.onRoot().captureRoboImage("screenshots/motion_heart_pop_0016ms.png")
        compose.mainClock.advanceTimeBy(100)
        compose.onRoot().captureRoboImage("screenshots/motion_heart_pop_0116ms.png")
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("screenshots/motion_heart_pop_0716ms.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Remove recommendation").assertIsDisplayed()
    }

    /** Frames of the double-tap heart: spring in, hold, drift up and fade. */
    @Test
    fun doubleTapHeartFrames() {
        setContent {
            Box(Modifier.fillMaxSize().testTag("card")) {
                DishPage(card, EngagementState(card.stats, card.me), null, {}, {}, {}, {})
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("card").performTouchInput { doubleClick(center) }
        var elapsed = 0L
        for (t in listOf(80L, 200L, 560L, 760L)) {
            compose.mainClock.advanceTimeBy(t - elapsed)
            elapsed = t
            compose.onRoot().captureRoboImage("screenshots/motion_double_tap_${t.toString().padStart(4, '0')}ms.png")
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun commentsLikeReplyAndSend() {
        val liked = mutableListOf<String>()
        val replied = mutableListOf<String>()
        var sent = 0
        var state by mutableStateOf(
            CommentsUiState(
                loading = false, count = 1,
                comments = listOf(Comment("c1", null, "Go early", "2026-10-16T06:00:00Z", Person("diya_k"), likes = 2)),
            ),
        )
        setContent {
            Box(Modifier.width(393.dp).height(700.dp)) {
                CommentsContent(
                    state = state, me = "me",
                    onDraft = { state = state.copy(draft = it) }, onSend = { sent++ },
                    onReply = { replied += it.id }, onCancelReply = {}, onLike = { liked += it.id }, onDelete = {},
                    onReport = {}, onRetry = {}, onOpenUser = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Like comment").performClick()
        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithContentDescription("Post comment").performClick() // nothing to send yet
        state = state.copy(draft = "Thanks!")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Post comment").performClick()
        compose.waitForIdle()
        assertEquals(listOf("c1"), liked)
        assertEquals(listOf("c1"), replied)
        assertEquals(1, sent)
    }
}
