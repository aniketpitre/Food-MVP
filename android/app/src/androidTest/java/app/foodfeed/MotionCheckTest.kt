package app.foodfeed

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.foodfeed.data.AppContainer
import app.foodfeed.data.Coords
import app.foodfeed.data.RegisterBody
import app.foodfeed.data.SelectedCity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Frame-by-frame look at motion, for checking by eye: the Explore card's buttons arriving
 * after a swipe (with Lite mode on its automatic setting, then off), and Home's photos while
 * the page is flung fast. The clock is paused and stepped, and each step is a screenshot.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MotionCheckTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container: AppContainer get() = (context.applicationContext as FoodFeedApp).container
    private val shots by lazy { File(context.filesDir, "device-shots").apply { mkdirs() } }
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() = runBlocking {
        while (!container.ready.value) kotlinx.coroutines.delay(100)
        val nashik = container.api.cities("nashik").first { it.slug == "nashik" }
        container.setCity(SelectedCity(nashik.id, nashik.name))
        container.location.value = Coords(19.9975, 73.7898)
        val handle = "motion_${System.currentTimeMillis() % 1_000_000}"
        container.signedIn(container.api.register(RegisterBody("$handle@example.com", "correct horse", handle, "Motion", true)))
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        runBlocking { container.setLiteMode("auto") }
        scenario.close()
    }

    private fun frame(name: String) {
        compose.waitForIdle()
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun deckFrames(prefix: String) {
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("feed_pager"), 20_000)
        compose.waitUntilAtLeastOneExists(hasContentDescription("Save"), 20_000)
        Thread.sleep(3000) // photos of this card and the next arrive
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("feed_pager").performTouchInput { swipeUp(durationMillis = 200) }
        for (step in 0..8) {
            frame("${prefix}_%02d".format(step))
            compose.mainClock.advanceTimeBy(150)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun exploreButtonsArrive_liteAutoThenOff() {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        Log.d(
            "JOURNEY",
            "motion: lowRam=${am.isLowRamDevice} totalMem=${mem.totalMem / (1024 * 1024)}MB memoryClass=${am.memoryClass} " +
                "isLowEndDevice=${container.isLowEndDevice} lite=${container.liteModeSetting.value} liteInEffect=${container.liteMode}",
        )
        compose.waitUntilAtLeastOneExists(hasContentDescription("Explore"), 30_000)
        deckFrames("70_deck_lite_auto")
        runBlocking { container.setLiteMode("off") }
        compose.onNodeWithContentDescription("Home").performClick()
        compose.waitForIdle()
        deckFrames("71_deck_lite_off")
    }

    private fun home() = runBlocking {
        val c = container.city.value!!.id
        container.api.home(c, container.location.value?.lat, container.location.value?.lng)
    }

    @Test
    fun homePhotosDuringFastFling() {
        compose.waitUntilAtLeastOneExists(hasTestTag("craving_input"), 30_000)
        Thread.sleep(4000) // sections and their photos load
        val home = home()
        val sections = listOf(home.peoplesPicks, home.nearby, home.following.map { it.card }, home.trending, home.fresh)
        val ids = sections.flatMap { s -> s.map { it.id }.distinct() }
        val repeated = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.size
        Log.d("JOURNEY", "motion: home shows ${ids.size} dish cards, ${repeated} dishes appear in more than one section")
        compose.mainClock.autoAdvance = false
        // A fast flick in the middle of the page (the bottom bar would take a swipe that starts lower).
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.72f, endY = height * 0.12f, durationMillis = 70) }
        for (step in 0..9) {
            frame("72_home_fling_%02d".format(step))
            compose.mainClock.advanceTimeBy(48)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun homeSurvivesATapBurst() {
        compose.waitUntilAtLeastOneExists(hasTestTag("craving_input"), 30_000)
        Thread.sleep(4000)
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val w = device.displayWidth
        val h = device.displayHeight
        // Stories, chips, both People's Pick cards and the messages button, hit as fast as a
        // thumb can, without waiting for anything to open.
        val spots = listOf(0.25f to 0.68f, 0.75f to 0.68f, 0.12f to 0.47f, 0.42f to 0.47f, 0.45f to 0.11f, 0.25f to 0.62f, 0.93f to 0.05f)
        repeat(24) { i ->
            val (x, y) = spots[i % spots.size]
            device.click((w * x).toInt(), (h * y).toInt())
            Thread.sleep(45)
        }
        Thread.sleep(2500)
        frame("73_after_tap_burst")
        // How deep did the taps stack screens? Back until Home shows again.
        var backs = 0
        while (compose.onAllNodes(hasTestTag("craving_input"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() && backs < 15) {
            device.pressBack()
            backs++
            Thread.sleep(700)
            compose.waitForIdle()
        }
        Log.d("JOURNEY", "motion: after 24 fast taps it took $backs presses of Back to reach Home")
        org.junit.Assert.assertTrue("Home is reachable again ($backs backs)", backs < 15)
        // Home still works: it scrolls, and a card opens its dish.
        compose.onRoot().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.4f) }
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.7f) }
        compose.waitForIdle()
        // Open a People's Pick by its name (where the card sits depends on the sections above it).
        val pick = home().peoplesPicks.first().name
        compose.onNodeWithTag("home_list").performScrollToNode(androidx.compose.ui.test.hasText(pick))
        compose.onAllNodes(androidx.compose.ui.test.hasText(pick))[0].performClick()
        compose.waitUntilAtLeastOneExists(androidx.compose.ui.test.hasText("Take me there"), 15_000)
        frame("74_home_still_works")
    }
}
