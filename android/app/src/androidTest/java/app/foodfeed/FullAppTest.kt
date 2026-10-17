package app.foodfeed

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.foodfeed.data.AppContainer
import app.foodfeed.data.AuthResult
import app.foodfeed.data.Coords
import app.foodfeed.data.RegisterBody
import app.foodfeed.data.SelectedCity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Walks through every part of the app on an emulator against a real FoodFeed server (CI
 * starts one with demo data): the Explore deck (scrolling, love, save, send, tabs), a dish
 * page (real photo credit, comments, share, Take me there on a demo place, the place page),
 * craving search (typos and nothing found), messages (text, double-tap heart, reply, dish
 * card, a reply from a friend while the chat is open), phone notifications and the links
 * they open, Activity, profile and every setting, signing out, and a guest trying to love a
 * dish. A second account (the "friend") talks to the server directly. Screenshots of each
 * step go to files/device-shots for CI to collect; how smooth the deck scrolled goes to the log.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FullAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext
    private val container: AppContainer get() = (context.applicationContext as FoodFeedApp).container
    private val shots by lazy { File(context.filesDir, "device-shots").apply { mkdirs() } }
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var me: AuthResult
    private val suffix = (System.currentTimeMillis() % 1_000_000).toString()

    @Before
    fun setUp() = runBlocking {
        while (!container.ready.value) kotlinx.coroutines.delay(100)
        val nashik = container.api.cities("nashik").first { it.slug == "nashik" }
        container.setCity(SelectedCity(nashik.id, nashik.name))
        container.location.value = Coords(19.9975, 73.7898)
        val handle = "full_$suffix"
        me = container.api.register(RegisterBody("$handle@example.com", "correct horse", handle, "Full Tester", true))
        container.signedIn(me)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    // ------------------------------------------------------------------ helpers

    private fun shot(name: String) {
        runCatching { compose.waitForIdle() }
        instrumentation.waitForIdleSync()
        Thread.sleep(700)
        val bmp: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Runs a step; on failure leaves a screenshot and the UI tree in the log. */
    private fun step(name: String, block: () -> Unit) {
        Log.d("JOURNEY", "step: $name")
        try {
            block()
        } catch (e: Throwable) {
            Log.e("JOURNEY", "step \"$name\" failed", e)
            runCatching { shot("99_failure_${name.replace(' ', '_')}") }
            runCatching { compose.onRoot(useUnmergedTree = true).printToLog("JOURNEY") }
            throw AssertionError("Step \"$name\" failed: ${e.message}", e)
        }
    }

    /** Waits for [matcher] anywhere, including inside merged rows (a tag inside a tappable row). */
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String, substring: Boolean = false, timeoutMs: Long = 15_000) =
        waitFor(hasText(text, substring = substring), timeoutMs)

    private fun gone(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    /** The first node matching [matcher] that is fully on screen (pager pages next door are composed too). */
    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val rootH = compose.onRoot().fetchSemanticsNode().size.height
        val nodes = compose.onAllNodes(matcher).fetchSemanticsNodes()
        val i = nodes.indexOfFirst { it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= rootH && it.boundsInRoot.height > 0f }
        assertTrue("a visible node matching ${matcher.description}", i >= 0)
        return compose.onAllNodes(matcher)[i]
    }

    private fun tab(name: String) {
        compose.onNodeWithContentDescription(name).performClick()
        compose.waitForIdle()
    }

    /** Back until [matcher] shows (a keyboard or sheet may take a press of its own). */
    private fun backTo(matcher: SemanticsMatcher, presses: Int = 4) {
        repeat(presses) {
            if (compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            device.pressBack()
            Thread.sleep(600)
            compose.waitForIdle()
        }
        waitFor(matcher, 5_000)
    }

    private fun open(link: String) {
        scenario.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(it, MainActivity::class.java)) }
        compose.waitForIdle()
    }

    private val server get() = container.serverUrl.value.trimEnd('/')

    /** The friend's side of things: plain HTTP calls to the server with their own token. */
    private fun call(method: String, path: String, token: String, body: JSONObject? = null): JSONObject {
        val json = "application/json".toMediaType()
        val req = Request.Builder().url("$server$path").header("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody(json) ?: if (method == "GET") null else "{}".toRequestBody(json))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            assertTrue("$method $path -> ${res.code} $text", res.isSuccessful)
            return if (text.startsWith("{")) JSONObject(text) else JSONObject()
        }
    }

    private fun friend(tag: String): AuthResult = runBlocking {
        val h = "fr${tag}_$suffix".take(20)
        container.api.register(RegisterBody("$h@example.com", "correct horse", h, "Friend $tag", true))
    }

    private fun gfx(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${context.packageName} $cmd").use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        }

    // ------------------------------------------------------------------ tests

    @Test
    fun exploreDeck_scrollLoveSaveSendAndTabs() {
        step("open Explore") {
            waitFor(hasContentDescription("Explore"), 30_000)
            tab("Explore")
            waitFor(hasTestTag("feed_pager"), 20_000)
            waitFor(hasContentDescription("Save") or hasContentDescription("Remove from saved"), 20_000)
            Thread.sleep(2000) // the first photos arrive
            shot("10_feed_top")
        }
        step("scroll the deck") {
            gfx("reset")
            repeat(10) {
                compose.onNodeWithTag("feed_pager").performTouchInput { swipeUp(durationMillis = 220) }
                compose.waitForIdle()
            }
            val stats = gfx("").lines().filter {
                it.contains("Total frames") || it.contains("Janky frames") || it.contains("percentile") || it.contains("Slow")
            }
            stats.forEach { Log.d("JOURNEY", "deck scroll: ${it.trim()}") }
            shot("11_feed_after_10_swipes")
            repeat(10) {
                compose.onNodeWithTag("feed_pager").performTouchInput { swipeDown(durationMillis = 220) }
                compose.waitForIdle()
            }
        }
        step("love and save a dish") {
            visible(hasContentDescription("Recommend")).performClick()
            waitFor(hasContentDescription("Remove recommendation"))
            visible(hasContentDescription("Save")).performClick()
            waitFor(hasContentDescription("Remove from saved"))
            shot("12_feed_loved_saved")
            // Double-tap never un-loves: it stays loved.
            compose.onNodeWithTag("feed_pager").performTouchInput { doubleClick(center) }
            compose.waitForIdle()
            visible(hasContentDescription("Remove recommendation"))
        }
        step("send sheet") {
            visible(hasContentDescription("Send to friends")).performClick()
            waitText("Send to")
            waitText("Share elsewhere")
            shot("13_send_sheet")
            backTo(hasTestTag("feed_pager"))
        }
        step("tabs") {
            for (t in listOf("Trending", "Gems", "Top")) {
                compose.onAllNodes(hasText(t)).onFirst().performClick()
                compose.waitForIdle()
                Thread.sleep(1500)
                if (t == "Gems") shot("14_feed_gems")
            }
            waitFor(hasTestTag("feed_pager"), 15_000)
        }
    }

    @Test
    fun dishPage_creditCommentsSendDemoPlace() {
        lateinit var dishId: String
        step("open a demo dish by link") {
            val city = container.city.value!!.id
            val card = runBlocking { container.api.feed(city, null, "top", 20, 0, "full", null, null) }
                .items.first { it.restaurant.isDemo && it.cover != null }
            dishId = card.id
            waitFor(hasContentDescription("Explore"), 30_000)
            open("foodfeed://dish/$dishId")
            waitText("Take me there", timeoutMs = 20_000)
            // A real photo with its photographer and licence.
            waitText("Wikimedia Commons", substring = true)
            waitFor(hasTestTag("demo_tag"))
            Thread.sleep(1500)
            shot("20_dish_page")
        }
        step("scroll the dish page") {
            compose.onRoot().performTouchInput { swipeUp() }
            compose.waitForIdle()
            shot("21_dish_scrolled")
            compose.onRoot().performTouchInput { swipeDown() }
        }
        step("comment") {
            compose.onAllNodes(hasContentDescription("Comments")).onFirst().performScrollTo().performClick()
            waitFor(hasTestTag("comment_input"))
            compose.onNodeWithTag("comment_input").performTextInput("Looks delicious! Going tonight 😋")
            compose.onNodeWithContentDescription("Post comment").performClick()
            waitText("Looks delicious! Going tonight 😋")
            shot("22_comments")
            backTo(hasText("Take me there"))
        }
        step("send this post") {
            compose.onAllNodes(hasContentDescription("Send to friends")).onFirst().performScrollTo().performClick()
            waitText("Send to")
            backTo(hasText("Take me there"))
        }
        step("take me there on a demo place") {
            compose.onNodeWithText("Take me there").performScrollTo().performClick()
            waitText("This is a demo place")
            shot("23_demo_place_notice")
            compose.onNodeWithText("OK").performClick()
            gone(hasText("This is a demo place"))
        }
        step("the place page") {
            compose.onAllNodes(hasContentDescription("Open place")).onFirst().performScrollTo().performClick()
            waitFor(hasContentDescription("Send this place to friends"), 15_000)
            waitFor(hasTestTag("demo_tag"))
            Thread.sleep(1200)
            shot("24_place_page")
            compose.onNodeWithContentDescription("Send this place to friends").performClick()
            waitText("Send to")
            backTo(hasContentDescription("Send this place to friends"))
        }
    }

    @Test
    fun cravingSearch_typosChipsAndNothingFound() {
        step("a misspelt craving") {
            waitFor(hasTestTag("craving_input"), 30_000)
            compose.onNodeWithTag("craving_input").performTextInput("wada pav")
            compose.onNodeWithTag("craving_input").performImeAction()
            waitText("Best ", substring = true, timeoutMs = 20_000)
            waitText("Vada Pav", substring = true)
            Thread.sleep(1500)
            shot("30_craving_wada_pav")
            backTo(hasTestTag("craving_input"))
        }
        step("nothing found") {
            compose.onNodeWithTag("craving_input").performTextClearance()
            compose.onNodeWithTag("craving_input").performTextInput("qqzzxx")
            compose.onNodeWithTag("craving_input").performImeAction()
            waitText(" yet", substring = true, timeoutMs = 20_000)
            waitText("Post a dish")
            shot("31_craving_nothing_found")
            backTo(hasTestTag("craving_input"))
        }
        step("a craving chip") {
            // The chips read "<emoji>  Misal"; dish cards say "Misal Pav" without the gap.
            compose.onAllNodes(hasText("  Misal", substring = true)).onFirst().performScrollTo().performClick()
            waitText("Best ", substring = true, timeoutMs = 20_000)
            backTo(hasTestTag("craving_input"))
        }
    }

    @Test
    fun messages_richChatNotificationsAndLinks() {
        val pal = friend("m")
        val palName = pal.user.displayName!!
        step("find the friend and open a chat") {
            waitFor(hasTestTag("messages_button"), 30_000)
            compose.onNodeWithTag("messages_button").performClick()
            waitFor(hasTestTag("inbox_search"))
            compose.onNodeWithTag("inbox_search").performTextInput(pal.user.handle)
            waitText(palName, timeoutMs = 15_000)
            compose.onNodeWithText(palName).performClick()
            waitFor(hasTestTag("chat_input"), 15_000)
        }
        step("text, double-tap heart, reply") {
            compose.onNodeWithTag("chat_input").performTextInput("Hello from the emulator 👋")
            compose.onNodeWithContentDescription("Send").performClick()
            waitFor(hasTestTag("message_text"))
            compose.onAllNodes(hasTestTag("message_text")).onLast().performTouchInput { doubleClick() }
            waitFor(hasTestTag("reactions"))
            compose.onAllNodes(hasTestTag("message_text")).onLast().performTouchInput { longClick() }
            waitText("Reply")
            compose.onNodeWithText("Reply").performClick()
            waitText("Replying to", substring = true)
            compose.onNodeWithTag("chat_input").performTextInput("Also: misal or pav bhaji?")
            compose.onNodeWithContentDescription("Send").performClick()
            waitText("You replied to yourself")
        }
        step("send a dish card") {
            compose.onNodeWithTag("chat_attach").performClick()
            waitText("Dish or place")
            compose.onNodeWithText("Dish or place").performClick()
            waitFor(hasTestTag("attach_dish"), 20_000)
            compose.onAllNodes(hasTestTag("attach_dish")).onFirst().performClick()
            waitFor(hasTestTag("message_dish"), 15_000)
            Thread.sleep(1200)
            shot("40_chat_text_heart_reply_dish")
        }
        lateinit var chatId: String
        step("the friend answers while the chat is open") {
            chatId = call("POST", "/conversations", pal.token, JSONObject().put("handle", me.user.handle)).getString("id")
            call("POST", "/conversations/$chatId/messages", pal.token, JSONObject().put("body", "Misal at 8? 🌶️"))
            waitText("Misal at 8? 🌶️", timeoutMs = 15_000)
            shot("41_chat_live_reply")
        }
        step("notifications when the app is elsewhere") {
            backTo(hasTestTag("messages_button"), presses = 6)
            Thread.sleep(1000)
            call("POST", "/conversations/$chatId/messages", pal.token, JSONObject().put("body", "Are you coming?"))
            call("PUT", "/users/${me.user.handle}/follow", pal.token)
            val nm = context.getSystemService(NotificationManager::class.java)
            val end = System.currentTimeMillis() + 60_000
            var tags = emptyList<String>()
            while (System.currentTimeMillis() < end) {
                runBlocking { container.checkUpdates() }
                tags = nm.activeNotifications.mapNotNull { it.tag }
                if (tags.contains("chat:$chatId") && tags.any { it.startsWith("activity:") }) break
                Thread.sleep(2000)
            }
            Log.d("JOURNEY", "notifications: $tags")
            assertTrue("a message notification ($tags)", tags.contains("chat:$chatId"))
            assertTrue("a follow notification ($tags)", tags.any { it.startsWith("activity:") })
            device.openNotification()
            Thread.sleep(2000)
            val shade = instrumentation.uiAutomation.takeScreenshot()
            File(shots, "42_notifications.png").outputStream().use { shade?.compress(Bitmap.CompressFormat.PNG, 100, it) }
            device.pressBack()
            Thread.sleep(800)
        }
        step("the notification's link opens the chat and clears it") {
            open("foodfeed://chat/$chatId")
            waitText("Are you coming?", timeoutMs = 15_000)
            val nm = context.getSystemService(NotificationManager::class.java)
            compose.waitUntil(10_000) { nm.activeNotifications.none { it.tag == "chat:$chatId" } }
            shot("43_chat_from_notification")
        }
        step("a profile link") {
            open("foodfeed://user/${pal.user.handle}")
            waitText(palName, timeoutMs = 15_000)
            waitText("Message")
            open("foodfeed://activity")
            waitText("started following you", substring = true, timeoutMs = 15_000)
            shot("44_activity_from_link")
        }
    }

    @Test
    fun profileSettingsSignOutAndGuest() {
        step("profile") {
            waitFor(hasContentDescription("Profile"), 30_000)
            tab("Profile")
            waitFor(hasContentDescription("Settings"), 15_000)
            shot("50_profile")
            compose.onAllNodes(hasContentDescription("Settings")).onFirst().performClick()
            waitText("Theme")
        }
        step("theme: light, dark, back to the phone's") {
            compose.onNodeWithText("Theme").performClick()
            waitText("Light")
            compose.onNodeWithText("Theme").performClick()
            waitText("Dark")
            shot("51_settings_dark")
            compose.onNodeWithText("Theme").performClick()
            waitText("Same as phone")
        }
        step("lite mode: on, off, automatic") {
            compose.onNodeWithText("Lite mode").performClick()
            waitText("On: fewer effects", substring = true)
            shot("52_settings_lite_on")
            compose.onNodeWithText("Lite mode").performClick()
            waitText("Off: full effects")
            compose.onNodeWithText("Lite mode").performClick()
            waitText("Automatic", substring = true)
        }
        step("notifications and the rest are there") {
            waitText("Notifications")
            waitText("Who can message you")
            compose.onNodeWithText("Version", substring = true).performScrollTo()
            waitText("Privacy policy")
        }
        step("sign out") {
            compose.onNodeWithText("Sign out").performScrollTo().performClick()
            waitText("Sign in", timeoutMs = 10_000)
            shot("53_signed_out")
        }
        step("a guest who loves a dish is asked to sign in") {
            backTo(hasContentDescription("Explore"))
            tab("Explore")
            waitFor(hasTestTag("feed_pager"), 20_000)
            waitFor(hasContentDescription("Recommend"), 20_000)
            visible(hasContentDescription("Recommend")).performClick()
            waitText("Create account", timeoutMs = 10_000)
            shot("54_guest_sign_in")
        }
    }
}
