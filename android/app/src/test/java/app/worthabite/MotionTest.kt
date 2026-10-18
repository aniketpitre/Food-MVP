package app.worthabite

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.worthabite.data.Cover
import app.worthabite.data.DishCard
import app.worthabite.data.DishStats
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.data.RestaurantRef
import app.worthabite.data.UserRef
import app.worthabite.data.toggled
import app.worthabite.ui.LiquidDock
import app.worthabite.ui.Routes
import app.worthabite.ui.components.EngageAction
import app.worthabite.ui.components.LocalImageOverride
import app.worthabite.ui.feed.DishPage
import app.worthabite.ui.feed.FeedTab
import app.worthabite.ui.feed.FeedTabs
import app.worthabite.ui.theme.WorthAbiteTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Records the signature animations frame by frame on a frozen clock. Each frame is saved as
 * screenshots/gif_<name>_<step>ms_<index>.png; the Screenshots workflow stitches them into
 * docs/motion/<name>.gif so the motion can be reviewed without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class MotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val images = File("../../backend/seed/images")
    private fun load(url: String): ImageBitmap? {
        val f = File(images, url.substringAfterLast('/') + ".jpg")
        return if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
    }

    private val card = DishCard(
        id = "d1", name = "Kolhapuri Misal", priceInr = 90, isVeg = true,
        restaurant = RestaurantRef("r1", "Godavari Misal House", "Panchavati"),
        cover = Cover("p1", "/media/godavari_kolhapuri_misal", "/media/godavari_kolhapuri_misal", "#c2410c"),
        stats = DishStats(recommenders = 38, saves = 12),
        recentRecommenders = listOf(UserRef("diya_k", "Diya K"), UserRef("arjun", "Arjun"), UserRef("myra_s", "Myra S")),
        distanceM = 1200,
    )

    private fun stage(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            WorthAbiteTheme {
                CompositionLocalProvider(LocalImageOverride provides ::load) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
    }

    private fun frames(name: String, stepMs: Long, count: Int) {
        repeat(count) { i ->
            compose.onNodeWithTag("stage").captureRoboImage("screenshots/gif_${name}_${stepMs}ms_${i.toString().padStart(3, '0')}.png")
            compose.mainClock.advanceTimeBy(stepMs)
        }
    }

    /** A card lands: photo settles, title rises out of its mask, sticker slaps on, rail cascades in. */
    @Test
    fun cardLanding() {
        var active by mutableStateOf(false)
        val crowd = card.copy(stats = DishStats(recommenders = 38))
        stage {
            Box(Modifier.width(360.dp).height(620.dp).testTag("stage")) {
                DishPage(crowd, EngagementState(crowd.stats, crowd.me), crowd.cover?.imageUrl, {}, {}, {}, {}, active = active)
            }
        }
        compose.mainClock.advanceTimeBy(50)
        active = true
        frames("card_landing", 50, 28)
    }

    /** Double-tap: chili heart springs up at the finger with a ring and spice confetti. */
    @Test
    fun doubleTapHeart() {
        var state by mutableStateOf(EngagementState(card.stats, card.me))
        stage {
            Box(Modifier.width(360.dp).height(620.dp).testTag("stage")) {
                DishPage(card, state, card.cover?.imageUrl, {}, {}, { state = state.toggled(it) }, {})
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("stage").performTouchInput { doubleClick(Offset(width * 0.42f, height * 0.4f)) }
        frames("double_tap", 40, 26)
    }

    /** The dock's ember blob stretches toward the new tab and snaps back round. */
    @Test
    fun liquidDock() {
        var route by mutableStateOf(Routes.FEED)
        stage {
            Box(Modifier.fillMaxWidth().testTag("stage")) { LiquidDock(route) {} }
        }
        compose.mainClock.advanceTimeBy(300)
        route = Routes.SAVED
        frames("liquid_dock", 24, 26)
        route = Routes.SEARCH
        frames("liquid_dock_back", 24, 22)
    }

    /** Switching feed tabs: the squiggle slides, rolls its wave, and redraws from the left. */
    @Test
    fun tabSquiggle() {
        var tab by mutableStateOf(FeedTab.Top)
        stage {
            Box(Modifier.fillMaxWidth().testTag("stage")) { FeedTabs(tab) { tab = it } }
        }
        compose.mainClock.advanceTimeBy(300)
        tab = FeedTab.Places
        frames("tab_squiggle", 32, 22)
    }

    /** Recommending on the dish page: the tile squashes, fills chili and bursts. */
    @Test
    fun recommendTile() {
        var active by mutableStateOf(false)
        stage {
            Box(Modifier.width(180.dp).height(150.dp).testTag("stage"), contentAlignment = Alignment.Center) {
                Box(Modifier.padding(20.dp)) {
                    EngageAction(
                        Engagement.Recommend, active, if (active) "Loved" else "Recommend", null,
                        if (active) "Remove recommendation" else "Recommend", { active = !active },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Recommend").performClick()
        frames("recommend_tile", 30, 24)
    }
}
