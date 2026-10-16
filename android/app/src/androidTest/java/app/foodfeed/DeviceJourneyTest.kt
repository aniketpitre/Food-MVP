package app.foodfeed

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.foodfeed.data.AppContainer
import app.foodfeed.data.Coords
import app.foodfeed.data.RegisterBody
import app.foodfeed.data.SelectedCity
import app.foodfeed.ui.map.FoodMapProbe
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real app on an emulator against a real FoodFeed server (CI starts one with
 * Docker, demo data on): signs a tester up, opens Explore → Nearby, waits for the map and
 * its food logos, taps a logo, checks the 3D place card, and that Take me there asks
 * Google Maps for directions. Also visits stories and messages. Screenshots of each step
 * land in the app's files/device-shots folder for CI to collect.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class DeviceJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val app get() = instrumentation.targetContext.applicationContext as FoodFeedApp
    private val container: AppContainer get() = app.container
    private val shots by lazy {
        File(instrumentation.targetContext.filesDir, "device-shots").apply { deleteRecursively(); mkdirs() }
    }
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() = runBlocking {
        while (!container.ready.value) kotlinx.coroutines.delay(100)
        val nashik = container.api.cities("nashik").first { it.slug == "nashik" }
        container.setCity(SelectedCity(nashik.id, nashik.name))
        container.location.value = Coords(19.9975, 73.7898)
        val handle = "device_${System.currentTimeMillis() % 1_000_000}"
        container.signedIn(container.api.register(RegisterBody("$handle@example.com", "correct horse", handle, "Device Tester", true)))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
        scenario.close()
    }

    private fun shot(name: String) {
        runCatching { compose.waitForIdle() }
        instrumentation.waitForIdleSync()
        Thread.sleep(800) // let the window draw what the semantics tree already shows
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(200)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun mapLogosPopupAndDirections_storiesAndMessages() {
        try {
            journey()
        } catch (e: Throwable) {
            // Leave evidence: what was on screen and the UI tree, for the CI log.
            runCatching { shot("99_failure") }
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("JOURNEY") }
            throw e
        }
    }

    private fun journey() {
        // Home with the stories row and the craving search.
        compose.waitUntilAtLeastOneExists(hasText("What are you\ncraving?"), 30_000)
        compose.waitUntilAtLeastOneExists(hasTestTag("story_bubble"), 20_000)
        Thread.sleep(2500) // photos and sections settle
        shot("01_home")

        // A demo story, full screen. The test clock would otherwise fast-forward the 5-second
        // story timers and the viewer would play every story and close at once, so the clock
        // is paused while we look.
        compose.waitUntilAtLeastOneExists(hasTestTag("story_bubble"), 15_000)
        compose.onAllNodes(hasTestTag("story_bubble")).onFirst().performClick()
        compose.mainClock.autoAdvance = false
        repeat(12) {
            compose.mainClock.advanceTimeBy(100)
            Thread.sleep(150) // real time for the photo to load
        }
        assertTrue("story viewer is open", compose.onAllNodes(hasTestTag("story_page")).fetchSemanticsNodes().isNotEmpty())
        shot("02_story")
        device.pressBack()
        compose.mainClock.advanceTimeBy(1000)
        compose.mainClock.autoAdvance = true

        // Messages: empty inbox for a new tester.
        compose.waitUntilAtLeastOneExists(hasTestTag("messages_button"), 10_000)
        compose.onNodeWithTag("messages_button").performClick()
        compose.waitUntilAtLeastOneExists(hasText("No messages yet"), 10_000)
        shot("03_inbox")
        device.pressBack()

        // Explore → Nearby: the map with food logos.
        compose.waitUntilAtLeastOneExists(hasContentDescription("Explore"), 10_000)
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Nearby"), 15_000)
        compose.onAllNodesWithText("Nearby").onFirst().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("food_map"), 15_000)
        // compose.waitUntil keeps the (test-controlled) clock ticking so the app recomposes
        // while we wait; a plain sleep would freeze the screen.
        val ok = runCatching { compose.waitUntil(60_000) { FoodMapProbe.styleReady && FoodMapProbe.places.isNotEmpty() } }
        if (ok.isFailure) {
            instrumentation.runOnMainSync {
                val m = FoodMapProbe.map
                android.util.Log.d(
                    "JOURNEY",
                    "map state: map=${m != null} styleReady=${FoodMapProbe.styleReady} styleLoaded=${m?.style?.isFullyLoaded} " +
                        "probePlaces=${FoodMapProbe.places.size} view=${FoodMapProbe.view != null}",
                )
            }
            throw ok.exceptionOrNull()!!
        }
        Thread.sleep(4000) // tiles and camera settle
        shot("04_map_logos")

        // What the map engine itself reports, for the CI log.
        instrumentation.runOnMainSync {
            val m = FoodMapProbe.map
            val v = FoodMapProbe.view
            if (m != null && v != null) {
                val all = m.queryRenderedFeatures(android.graphics.RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()), "ff-pins", "ff-quiet")
                android.util.Log.d("JOURNEY", "map: styleLoaded=${m.style?.isFullyLoaded} pinsRendered=${all.size} places=${FoodMapProbe.places.size} zoom=${m.cameraPosition.zoom}")
            }
        }

        // Tap a food logo (the pin's badge sits just above its anchor point).
        val place = FoodMapProbe.places.first { it.dishCount > 0 }
        var pin: Pair<Float, Float>? = null
        instrumentation.runOnMainSync { pin = FoodMapProbe.pinOnScreen(place.id) }
        assertNotNull("pin for ${place.name} is on screen", pin)
        val badge = (28 * instrumentation.targetContext.resources.displayMetrics.density).toInt()
        device.click(pin!!.first.toInt(), pin!!.second.toInt() - badge)
        val pinTapWorked = runCatching { compose.waitUntilAtLeastOneExists(hasTestTag("map_popup"), 6_000) }.isSuccess
        if (!pinTapWorked) {
            android.util.Log.d("JOURNEY", "pin tap did not open the card; opening it from the card strip instead")
            compose.onNodeWithTag("map_card_${place.id}").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("map_popup"), 10_000)
        }
        Thread.sleep(1200) // let the 3D swing finish
        shot("05_map_popup")
        assertTrue(compose.onAllNodesWithText(place.name).fetchSemanticsNodes().isNotEmpty())

        // Take me there → Google Maps directions to this place (stubbed so nothing opens).
        intending(anyIntent()).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        compose.onNodeWithText("Take me there").performClick()
        Thread.sleep(3000) // it asks our server for the Google listing first (2.5 s at most)
        intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(uriContaining("google.com/maps/dir/"))))
        intended(hasData(uriContaining("destination=${place.lat},${place.lng}")))

        // Close the card; the strip of places comes back and opens the card too.
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("map_card_${FoodMapProbe.places.first { it.dishCount > 0 }.id}"), 10_000)
        shot("06_map_strip")
        assertTrue("tapping a food logo on the map opens its card", pinTapWorked)
    }

    private fun uriContaining(part: String) = object : TypeSafeMatcher<android.net.Uri>() {
        override fun describeTo(description: Description) {
            description.appendText("a uri containing ").appendValue(part)
        }

        override fun matchesSafely(item: android.net.Uri) = item.toString().contains(part)
    }
}
