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
import androidx.compose.ui.test.performScrollToNode
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
 * craving search (typos and nothing found), sharing a dish with a friend (double-tap
 * heart, a post shared back while the chat is open), phone notifications and the links
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
            waitText("Nobody to share with yet", substring = true)
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
            waitText("Most recommended here", timeoutMs = 15_000)
            waitFor(hasTestTag("demo_tag"))
            Thread.sleep(1200)
            shot("24_place_page")
            // Places can't be sent for now: only dishes.
            assertTrue(compose.onAllNodes(hasContentDescription("Send this place to friends")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun cravingSearch_typosChipsAndNothingFound() {
        val someone = friend("q")
        step("Home's search: a misspelt craving") {
            waitFor(hasTestTag("craving_input"), 30_000)
            compose.onNodeWithTag("craving_input").performClick()
            waitFor(hasTestTag("search_input"), 10_000)
            compose.onNodeWithTag("search_input").performTextInput("wada pav")
            waitFor(hasTestTag("search_craving"), 20_000)
            waitText("Vada Pav", substring = true)
            shot("30_search_wada_pav")
            compose.onNodeWithTag("search_craving").performClick()
            waitText("Best ", substring = true, timeoutMs = 20_000)
            Thread.sleep(1500)
            shot("31_craving_wada_pav")
            backTo(hasTestTag("search_input"))
        }
        step("people, places and dishes tabs") {
            compose.onNodeWithTag("search_input").performTextClearance()
            compose.onNodeWithTag("search_input").performTextInput(someone.user.handle)
            waitFor(hasTestTag("search_tab_people"), 15_000)
            compose.onNodeWithTag("search_tab_people").performClick()
            waitFor(hasTestTag("search_person_${someone.user.handle}"), 15_000)
            shot("32_search_people")
            compose.onNodeWithTag("search_input").performTextClearance()
            compose.onNodeWithTag("search_input").performTextInput("godavari")
            waitFor(hasTestTag("search_tab_places"), 15_000)
            compose.onNodeWithTag("search_tab_places").performClick()
            waitText("Godavari Misal House", timeoutMs = 15_000)
            compose.onNodeWithTag("search_tab_top").performClick()
        }
        step("nothing found") {
            compose.onNodeWithTag("search_input").performTextClearance()
            compose.onNodeWithTag("search_input").performTextInput("qqzzxx")
            waitText("Nothing found", timeoutMs = 20_000)
            shot("33_search_nothing_found")
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
    fun messages_shareDishesWithFriendsNotificationsAndLinks() {
        val pal = friend("m")
        val palName = pal.user.displayName!!
        val follower = friend("f")
        val outsider = friend("o")
        val dishCount = { compose.onAllNodes(hasTestTag("message_dish"), useUnmergedTree = true).fetchSemanticsNodes().size }
        step("people I follow and people who follow me are my friends") {
            call("PUT", "/users/${pal.user.handle}/follow", me.token)
            call("PUT", "/users/${me.user.handle}/follow", follower.token)
        }
        step("share a dish from Explore") {
            waitFor(hasContentDescription("Explore"), 30_000)
            tab("Explore")
            waitFor(hasTestTag("feed_pager"), 20_000)
            waitFor(hasContentDescription("Send to friends"), 20_000)
            visible(hasContentDescription("Send to friends")).performClick()
            waitText("Send to")
            waitFor(hasTestTag("friend_${pal.user.handle}"), 15_000)
            waitFor(hasTestTag("friend_${follower.user.handle}"), 5_000)
            assertTrue(
                "strangers aren't listed",
                compose.onAllNodes(hasTestTag("friend_${outsider.user.handle}"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
            )
            compose.onNodeWithTag("friend_${pal.user.handle}", useUnmergedTree = true).performClick()
            shot("40_share_sheet")
            compose.onAllNodes(hasText("Share")).onLast().performClick()
            waitText("Shared.", timeoutMs = 15_000)
        }
        lateinit var chatId: String
        step("the inbox, the conversation, a reaction, and sharing a dish from inside it") {
            tab("Home")
            waitFor(hasTestTag("messages_button"), 30_000)
            compose.onNodeWithTag("messages_button").performClick()
            waitText("Messages")
            waitFor(hasTestTag("chat_${pal.user.handle}"), 15_000)
            shot("41_inbox")
            compose.onNodeWithTag("chat_${pal.user.handle}", useUnmergedTree = true).performClick()
            // Explore shares the post (its photo and caption) rather than the bare dish.
            waitFor(hasTestTag("message_post"), 15_000)
            waitFor(hasTestTag("share_hint"))
            // No typing: there is no message box, only sharing.
            assertTrue(compose.onAllNodes(hasTestTag("chat_input"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            compose.onAllNodes(hasTestTag("message_post")).onLast().performTouchInput { doubleClick() }
            waitFor(hasTestTag("reactions"))
            compose.onNodeWithTag("share_dish").performClick()
            waitFor(hasTestTag("pick_dish"), 20_000)
            shot("42_pick_a_dish")
            compose.onAllNodes(hasTestTag("pick_dish")).onFirst().performClick()
            waitFor(hasTestTag("message_dish"), 15_000)
            Thread.sleep(1200)
            shot("43_chat_reaction_and_shared_dish")
        }
        step("the friend shares a dish back while the conversation is open") {
            chatId = call("POST", "/conversations", pal.token, JSONObject().put("handle", me.user.handle)).getString("id")
            val city = container.city.value!!.id
            val before = dishCount()
            val dishId = runBlocking { container.api.feed(city, null, "top", 5, 0, "full", null, null) }.items[1].id
            call("POST", "/messages", pal.token, JSONObject().put("handles", org.json.JSONArray().put(me.user.handle)).put("dishId", dishId))
            compose.waitUntil(15_000) { dishCount() > before }
            shot("44_chat_live_share")
        }
        step("start a conversation with a follower from the inbox search") {
            backTo(hasTestTag("new_chat"), presses = 3)
            compose.onNodeWithTag("new_chat").performClick()
            compose.onNodeWithTag("inbox_search").performTextInput(follower.user.handle)
            waitFor(hasTestTag("person_${follower.user.handle}"), 15_000)
            shot("45_inbox_search")
            compose.onNodeWithTag("person_${follower.user.handle}").performClick()
            waitFor(hasTestTag("share_dish"), 15_000)
            compose.onNodeWithTag("share_dish").performClick()
            waitFor(hasTestTag("pick_dish"), 20_000)
            compose.onAllNodes(hasTestTag("pick_dish")).onFirst().performClick()
            waitFor(hasTestTag("message_dish"), 15_000)
        }
        step("Message from a profile") {
            open("foodfeed://user/${pal.user.handle}")
            waitText(palName, timeoutMs = 15_000)
            waitFor(hasTestTag("message_button"), 10_000)
            shot("46_profile_message_button")
            compose.onNodeWithTag("message_button").performClick()
            waitFor(hasTestTag("share_dish"), 15_000)
            waitFor(hasTestTag("message_post"), 15_000)
            // A stranger's profile has no Message button.
            open("foodfeed://user/${outsider.user.handle}")
            waitText(outsider.user.displayName!!, timeoutMs = 15_000)
            Thread.sleep(800)
            assertTrue(compose.onAllNodes(hasTestTag("message_button")).fetchSemanticsNodes().isEmpty())
        }
        step("notifications when the app is elsewhere") {
            backTo(hasTestTag("messages_button"), presses = 8)
            Thread.sleep(1000)
            val city = container.city.value!!.id
            val other = runBlocking { container.api.feed(city, null, "top", 5, 0, "full", null, null) }.items.last { it.cover != null }.cover!!.postId
            call("POST", "/messages", pal.token, JSONObject().put("handles", org.json.JSONArray().put(me.user.handle)).put("postId", other))
            call("PUT", "/users/${me.user.handle}/follow", outsider.token)
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
            assertTrue("a share notification ($tags)", tags.contains("chat:$chatId"))
            assertTrue("a follow notification ($tags)", tags.any { it.startsWith("activity:") })
            device.openNotification()
            Thread.sleep(2000)
            val shade = instrumentation.uiAutomation.takeScreenshot()
            File(shots, "47_notifications.png").outputStream().use { shade?.compress(Bitmap.CompressFormat.PNG, 100, it) }
            device.pressBack()
            Thread.sleep(800)
        }
        step("the notification's link opens the conversation and clears it") {
            open("foodfeed://chat/$chatId")
            waitFor(hasTestTag("message_dish"), 15_000)
            val nm = context.getSystemService(NotificationManager::class.java)
            compose.waitUntil(10_000) { nm.activeNotifications.none { it.tag == "chat:$chatId" } }
            shot("48_chat_from_notification")
        }
        step("Activity by link") {
            open("foodfeed://activity")
            waitText("started following you", substring = true, timeoutMs = 15_000)
            shot("49_activity_from_link")
        }
    }

    @Test
    fun tasteForYouTasteMatchMapFiltersAndRoute() {
        val twin = friend("t")
        val city = container.city.value!!.id
        val dishes = runBlocking { container.api.feed(city, null, "top", 10, 0, "full", null, null) }.items
        step("we love the same dishes, and I follow them") {
            dishes.take(4).forEach { d ->
                call("PUT", "/dishes/${d.id}/engagements/recommend", me.token)
                call("PUT", "/dishes/${d.id}/engagements/recommend", twin.token)
            }
            call("PUT", "/dishes/${dishes[5].id}/engagements/recommend", twin.token)
            call("PUT", "/users/${twin.user.handle}/follow", me.token)
        }
        step("tell the app my taste") {
            waitFor(hasTestTag("home_list"), 30_000)
            compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("taste_prompt"))
            compose.onNodeWithTag("taste_prompt").performClick()
            waitFor(hasTestTag("taste_cat_misal"), 15_000)
            compose.onNodeWithTag("taste_cat_misal").performClick()
            compose.onNodeWithTag("taste_cat_snacks").performClick()
            compose.onNodeWithTag("taste_diet_egg").performClick()
            compose.onNodeWithTag("taste_spice_4").performClick()
            waitText("Spice: Spicy")
            shot("50_taste_profile")
            compose.onNodeWithTag("taste_diet_nonveg").performClick()
            compose.onNodeWithTag("taste_save").performClick()
            waitFor(hasTestTag("home_list"), 15_000)
        }
        step("For you and people with your taste on Home") {
            // The reason line sits inside a tappable card, so look for the section, then the line.
            compose.waitUntil(20_000) {
                runCatching { compose.onNodeWithTag("home_list").performScrollToNode(hasText("For you")) }.isSuccess
            }
            waitFor(hasTestTag("because"), 10_000)
            Thread.sleep(1200)
            shot("51_home_for_you")
            runCatching { compose.onNodeWithTag("home_list").performScrollToNode(hasText("People with your taste")) }
            Thread.sleep(800)
            shot("52_home_taste_mates")
        }
        step("Taste Match on their profile") {
            open("foodfeed://user/${twin.user.handle}")
            waitFor(hasTestTag("taste_match"), 15_000)
            shot("53_profile_taste_match")
            compose.onNodeWithTag("taste_match").performClick()
            waitText("You both voted on 4 of the same dishes", substring = true)
            shot("54_taste_match_details")
            compose.onNodeWithText("OK").performClick()
        }
        step("For you in Explore") {
            backTo(hasTestTag("home_list"), presses = 4)
            tab("Explore")
            waitFor(hasText("For you"), 15_000)
            compose.onAllNodes(hasText("For you")).onFirst().performClick()
            waitFor(hasTestTag("feed_pager"), 20_000)
            Thread.sleep(2000)
            shot("55_explore_for_you")
        }
        step("map: my friends' picks") {
            compose.onAllNodes(hasText("Nearby")).onFirst().performClick()
            waitFor(hasTestTag("food_map"), 15_000)
            compose.waitUntil(60_000) { app.foodfeed.ui.map.FoodMapProbe.styleReady }
            compose.onNodeWithTag("map_filter_friends").performClick()
            // Only the places the person I follow loved, each with who loved it.
            val probe = app.foodfeed.ui.map.FoodMapProbe
            compose.waitUntil(20_000) { probe.places.isNotEmpty() && probe.places.all { it.lovedBy?.handle == twin.user.handle } }
            assertTrue(probe.places.any { it.id == dishes[5].restaurant.id })
            val first = probe.places.first { it.dishCount > 0 }
            waitFor(hasTestTag("map_card_${first.id}"), 10_000)
            Thread.sleep(1500)
            shot("56_map_friends_picks")
            compose.onNodeWithTag("map_card_${first.id}").performClick()
            waitFor(hasTestTag("loved_by"), 10_000)
            Thread.sleep(1200)
            shot("57_map_loved_by_friend")
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("map_filter_friends").performClick()
        }
        step("map: food on the way") {
            compose.onNodeWithTag("map_route").performClick()
            waitFor(hasTestTag("route_to"))
            compose.onNodeWithTag("route_to").performTextInput("Pune")
            waitFor(hasTestTag("route_city_pune"), 15_000)
            compose.onNodeWithTag("route_city_pune").performClick()
            waitFor(hasTestTag("route_banner"), 40_000)
            Thread.sleep(2500)
            shot("58_map_food_on_the_way")
            compose.onNodeWithContentDescription("End the drive").performClick()
            gone(hasTestTag("route_banner"))
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
            waitText("Who can share dishes with you")
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
