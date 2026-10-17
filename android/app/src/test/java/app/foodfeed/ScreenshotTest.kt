package app.foodfeed

import app.foodfeed.data.StoryReel
import app.foodfeed.data.Story
import app.foodfeed.data.MapPlace
import app.foodfeed.data.MapTopDish
import app.foodfeed.data.ChatSummary
import app.foodfeed.data.LastMessage
import app.foodfeed.data.Message
import app.foodfeed.data.MessagePost
import app.foodfeed.data.Reaction
import app.foodfeed.data.MessageDish
import app.foodfeed.data.Conversation
import app.foodfeed.ui.map.MapPopup
import app.foodfeed.ui.messages.InboxList
import app.foodfeed.ui.messages.InboxUiState
import app.foodfeed.ui.messages.ChatContent
import app.foodfeed.ui.messages.ChatUiState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.foodfeed.data.Category
import app.foodfeed.data.Cover
import app.foodfeed.data.DishCard
import app.foodfeed.data.DishDetail
import app.foodfeed.data.DishStats
import app.foodfeed.data.EngagementState
import app.foodfeed.data.MyActions
import app.foodfeed.data.Post
import app.foodfeed.data.ProfilePost
import app.foodfeed.data.RestaurantDetail
import app.foodfeed.data.RestaurantRef
import app.foodfeed.data.SelectedCity
import app.foodfeed.data.City
import app.foodfeed.data.PlaceOption
import app.foodfeed.data.UserProfile
import app.foodfeed.data.UserRef
import app.foodfeed.data.DishSuggestion
import app.foodfeed.ui.add.AddDishContent
import app.foodfeed.ui.add.AddDishUiState
import app.foodfeed.ui.auth.AuthContent
import app.foodfeed.ui.auth.AuthUiState
import app.foodfeed.ui.components.LocalImageOverride
import app.foodfeed.ui.dish.DishContent
import app.foodfeed.ui.feed.FeedContent
import app.foodfeed.ui.feed.FeedTab
import app.foodfeed.ui.feed.FeedUiState
import app.foodfeed.ui.onboarding.OnboardingContent
import app.foodfeed.ui.onboarding.OnboardingStep
import app.foodfeed.ui.onboarding.OnboardingUiState
import app.foodfeed.ui.profile.ProfileContent
import app.foodfeed.ui.profile.ProfileUiState
import app.foodfeed.data.TasteBadge
import app.foodfeed.data.Rating
import app.foodfeed.data.Champion
import app.foodfeed.data.HomeData
import app.foodfeed.data.Because
import app.foodfeed.data.TasteMate
import app.foodfeed.data.CravingChip
import app.foodfeed.data.Taster
import app.foodfeed.data.CravingResult
import app.foodfeed.data.ActivityItem
import app.foodfeed.data.NotificationDish
import app.foodfeed.ui.home.HomeContent
import app.foodfeed.ui.home.HomeUiState
import app.foodfeed.ui.home.CravingContent
import app.foodfeed.ui.home.CravingUiState
import app.foodfeed.ui.activity.ActivityContent
import app.foodfeed.ui.activity.ActivityUiState
import app.foodfeed.ui.restaurant.RestaurantContent
import app.foodfeed.ui.saved.SavedContent
import app.foodfeed.ui.search.SearchContent
import app.foodfeed.ui.search.SearchUiState
import app.foodfeed.data.SearchResult
import app.foodfeed.ui.theme.FoodFeedTheme
import app.foodfeed.data.Comment
import app.foodfeed.ui.comments.CommentsContent
import app.foodfeed.ui.comments.CommentsUiState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders each screen with sample data into PNGs under app/screenshots (run with
 * `./gradlew recordRoborazziDebug`). CI uploads them so the UI can be reviewed without a phone.
 * The images are the demo dish illustrations from backend/seed/images.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val images = File("../../backend/seed/images")
    private val cache = mutableMapOf<String, ImageBitmap?>()

    /** URLs in the samples are "/media/<seed-key>"; they map to the seed JPEGs. */
    private fun load(url: String): ImageBitmap? = cache.getOrPut(url) {
        val key = url.substringAfterLast('/')
        val f = File(images, "$key.jpg")
        if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
    }

    /**
     * Renders [content] and saves a PNG. Screens with endless motion (the loading shimmer)
     * use a frozen clock advanced by a fixed time, so the shot is deterministic.
     */
    private fun shoot(name: String, dark: Boolean = false, frozenClockMs: Long? = null, content: @Composable () -> Unit) {
        if (frozenClockMs != null) compose.mainClock.autoAdvance = false
        compose.setContent {
            FoodFeedTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalImageOverride provides ::load) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        if (frozenClockMs != null) compose.mainClock.advanceTimeBy(frozenClockMs) else compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private fun card(
        key: String, name: String, place: String, locality: String, recs: Int, veg: Boolean = true, price: Int? = 90,
        lane: String = "top", saved: Boolean = false, recommended: Boolean = false, color: String = "#c2410c",
        distance: Int? = 1200,
    ) = DishCard(
        id = key, name = name, priceInr = price, isVeg = veg,
        restaurant = RestaurantRef(key + "_r", place, locality, "active", 20.0, 73.79),
        cover = Cover(key, "/media/$key", "/media/$key", color),
        stats = DishStats(recommenders = recs, saves = recs / 2, tried = recs / 3, endorsers = recs + 2),
        me = MyActions(recommended = recommended, saved = saved),
        recentRecommenders = if (recs > 0) listOf(UserRef("diya_k", "Diya K"), UserRef("arjun", "Arjun"), UserRef("myra_s", "Myra S")) else emptyList(),
        distanceM = distance, lane = lane,
    )

    private val misal = card("godavari_kolhapuri_misal", "Kolhapuri Misal", "Godavari Misal House", "Panchavati", 38, recommended = true)
    private val coffee = card("ananda_cold_coffee", "Cold Coffee", "Cafe Ananda", "College Road", 31, price = 120, color = "#57534e", saved = true)
    private val bhurji = card("bhurji_egg_bhurji_pav", "Egg Bhurji Pav", "Bhurji Corner", "Nashik Road", 17, veg = false, price = 60, lane = "gem")
    private val biryani = card("tandoor_chicken_biryani", "Chicken Dum Biryani", "Tandoor Tales", "Gangapur Road", 22, veg = false, price = 280)
    private val thali = card("annapurna_thali", "Maharashtrian Thali", "Annapurna Thali", "Sharanpur Road", 0, price = 220, lane = "fresh", color = "#1e3a8a")

    private val categories = listOf(
        Category(1, "misal", "Misal", "🌶️"), Category(2, "snacks", "Vada Pav & Snacks", "🥟"),
        Category(3, "south-indian", "Dosa & South Indian", "🥞"), Category(4, "biryani", "Biryani", "🍛"),
        Category(5, "thali", "Thali", "🍱"), Category(6, "chai-coffee", "Chai & Coffee", "☕"),
    )

    @Composable
    private fun feed(state: FeedUiState) = FeedContent(
        state = state,
        engagementOf = { EngagementState(it.stats, it.me) },
        imageUrlOf = { it.cover?.imageUrl },
        onTab = {}, onCategory = {}, onRefresh = {}, onRetry = {}, onLoadMore = {}, onOpenDish = {}, onOpenRestaurant = {},
        onToggle = { _, _ -> }, onShare = {}, onChangeCity = {}, onSearch = {}, onAddDish = {}, onAskLocation = {},
    )

    @Test
    fun feedTop() = shoot("01_feed_top") {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, items = listOf(misal, coffee), loading = false))
    }

    @Test
    fun feedHiddenGemDark() = shoot("02_feed_hidden_gem_dark", dark = true) {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, selectedCategory = null, tab = FeedTab.Gems, items = listOf(bhurji), loading = false))
    }

    @Test
    fun feedNewDish() = shoot("03_feed_new_dish") {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, selectedCategory = 5, items = listOf(thali), loading = false))
    }

    @Test
    fun feedEmptyGems() = shoot("04_feed_empty_gems") {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, tab = FeedTab.Gems, loading = false))
    }

    @Test
    fun dishDetail() = shoot("05_dish_detail") {
        val d = misal
        DishContent(
            dish = DishDetail(
                id = d.id, name = d.name, priceInr = d.priceInr, isVeg = d.isVeg, restaurant = d.restaurant, cover = d.cover,
                stats = d.stats, me = d.me, distanceM = d.distanceM,
                posts = listOf(
                    Post("p1", "Fiery tarri — ask for extra farsan.", "/media/godavari_kolhapuri_misal", "/media/godavari_kolhapuri_misal", "#c2410c", authorHandle = "diya_k", authorName = "Diya K"),
                    Post("p2", null, "/media/godavari_misal_pav", "/media/godavari_misal_pav"),
                ),
                recommenders = listOf(UserRef("diya_k", "Diya K"), UserRef("arjun", null), UserRef("myra_s", "Myra S"), UserRef("kabir"), UserRef("isha_r", "Isha R")),
                moreAtPlace = listOf(
                    card("godavari_misal_pav", "Misal Pav", "Godavari Misal House", "Panchavati", 21),
                    card("godavari_kanda_bhaji", "Kanda Bhaji", "Godavari Misal House", "Panchavati", 8),
                ),
                champion = Champion("diya_k", "Diya K", isExpert = true, backers = 31),
            ),
            engagement = EngagementState(d.stats, d.me, Rating(36, 2, 95, 0.8)),
            imageUrl = { it }, onBack = {}, onToggle = {}, onShare = {}, onMaps = {}, onOpenRestaurant = {},
            onOpenDish = {}, onOpenUser = {}, onReport = { _, _ -> }, onBlock = {},
        )
    }

    @Test
    fun feedTopDark() = shoot("13_feed_top_dark", dark = true) {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, items = listOf(coffee, misal), loading = false))
    }

    @Test
    fun feedFiltered() = shoot("14_feed_filtered") {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, selectedCategory = 4, items = listOf(biryani), loading = false))
    }

    @Test
    fun feedLoading() = shoot("15_feed_loading", frozenClockMs = 700) {
        feed(FeedUiState(city = SelectedCity(1, "Nashik"), categories = categories, loading = true))
    }

    @Test
    fun restaurant() = shoot("06_restaurant") {
        val dishes = listOf(
            card("katta_vada_pav", "Vada Pav", "Chai Katta", "Canada Corner", 44, price = 20),
            card("katta_cutting_chai", "Cutting Chai", "Chai Katta", "Canada Corner", 25, price = 15, saved = true),
            card("katta_bun_maska", "Bun Maska", "Chai Katta", "Canada Corner", 0, price = 30),
        )
        RestaurantContent(
            place = RestaurantDetail("r1", "Chai Katta", "Canada Corner", priceBand = 1, distanceM = 850, dishes = dishes),
            engagementOf = { EngagementState(it.stats, it.me) }, thumbOf = { it.cover?.thumbUrl },
            onBack = {}, onOpenDish = {}, onSave = {}, onMaps = {},
        )
    }

    @Test
    fun search() = shoot("07_search") {
        SearchContent(
            state = SearchUiState(
                query = "wada pav",
                result = SearchResult(
                    dishes = listOf(
                        card("katta_vada_pav", "Vada Pav", "Chai Katta", "Canada Corner", 44, price = 20),
                        card("pbp_pav_bhaji", "Pav Bhaji", "Pav Bhaji Point", "Canada Corner", 29, price = 110),
                    ),
                    restaurants = listOf(PlaceOption("r1", "Chai Katta", "Canada Corner")),
                ),
            ),
            onQuery = {}, engagementOf = { EngagementState(it.stats, it.me) }, thumbOf = { it.cover?.thumbUrl },
            onOpenDish = {}, onOpenRestaurant = {}, onSave = {}, autoFocus = false,
        )
    }

    @Test
    fun saved() = shoot("08_saved") {
        SavedContent(
            loading = false, error = null,
            items = listOf(coffee, biryani, card("sangam_jalebi", "Jalebi", "Sweet Sangam", "Panchavati", 19, price = 40), bhurji),
            thumbOf = { it.cover?.thumbUrl }, onOpenDish = {}, onUnsave = {}, onRetry = {},
        )
    }

    @Test
    fun addDish() = shoot("09_add_dish") {
        AddDishContent(
            state = AddDishUiState(
                dishName = "sabudana",
                suggestions = listOf(DishSuggestion("Sabudana Khichdi", 12), DishSuggestion("Sabudana Vada", 2)),
                categories = categories,
                categoryId = 1,
                places = listOf(PlaceOption("r1", "Cafe Ananda", "College Road", 240), PlaceOption("r2", "Slice of Nashik", "College Road", 410)),
                placesNearby = true,
            ),
            photoModel = null, onGallery = {}, onCamera = {}, onClearPhoto = {}, onDishName = {}, onSuggestion = {},
            onCategory = {}, onPlaceQuery = {}, onSelectPlace = {}, onNewPlace = {}, onCancelNewPlace = {},
            onNewPlaceName = {}, onNewPlaceLocality = {}, onUseLocation = {}, onPrice = {}, onVeg = {}, onCaption = {},
            onSponsored = {}, onSubmit = {},
        )
    }

    @Test
    fun profile() = shoot("10_profile") {
        ProfileContent(
            state = ProfileUiState(
                loading = false,
                profile = UserProfile(
                    handle = "aniket", displayName = "Aniket Pitre", bio = "Misal before work, chai after. Nashik.",
                    recommendations = 42, followers = 128, following = 87, isMe = true,
                    tasteBadges = listOf(
                        TasteBadge("Misal", "🌶️", picks = 6, backers = 31, isExpert = true),
                        TasteBadge("Chai & Coffee", "☕", picks = 3, backers = 9),
                    ),
                    posts = listOf(
                        ProfilePost("1", "d1", "/media/ananda_sabudana_khichdi", null, "live", "Sabudana Khichdi", "Cafe Ananda"),
                        ProfilePost("2", "d2", "/media/katta_vada_pav", null, "live", "Vada Pav", "Chai Katta"),
                        ProfilePost("3", "d3", "/media/sangam_jalebi", null, "live", "Jalebi", "Sweet Sangam", "pending"),
                        ProfilePost("4", "d4", "/media/udupi_filter_coffee", null, "live", "Filter Coffee", "Udupi Swad"),
                        ProfilePost("5", "d5", "/media/pbp_pav_bhaji", null, "live", "Pav Bhaji", "Pav Bhaji Point"),
                    ),
                ),
            ),
            imageUrl = { it }, onBack = null, onOpenDish = {}, onSettings = {}, onEditProfile = {}, onFollow = {},
            onFollows = { _, _ -> }, onTab = {}, onBlockToggle = {}, onReport = {}, onShare = {},
        )
    }

    private fun rated(c: DishCard, loves: Int, passes: Int, champ: String? = null, expert: Boolean = false) = c.copy(
        rating = Rating(loves, passes, Math.round(100f * loves / (loves + passes)), 0.8),
        champion = champ?.let { Champion(it, null, isExpert = expert, backers = 14) },
    )

    @Test
    fun home() = shoot("17_home") {
        HomeContent(
            state = HomeUiState(
                city = SelectedCity(1, "Nashik"),
                loading = false,
                data = HomeData(
                    cravings = listOf(
                        CravingChip("Misal", "🌶️", 4), CravingChip("Vada Pav & Snacks", "🥟", 6), CravingChip("Chai & Coffee", "☕", 3),
                    ),
                    peoplesPicks = listOf(
                        rated(misal, 36, 2, "diya_k", expert = true),
                        rated(card("pbp_pav_bhaji", "Pav Bhaji", "Pav Bhaji Point", "Canada Corner", 29, price = 110), 28, 6, "arjun"),
                    ),
                    tasters = listOf(
                        Taster("diya_k", "Diya K", picks = 6, backers = 31, isExpert = true),
                        Taster("arjun", "Arjun", picks = 4, backers = 12),
                        Taster("myra_s", "Myra S", picks = 2, backers = 5),
                    ),
                ),
            ),
            imageUrl = { it }, onCraving = {}, onOpenDish = {}, onOpenUser = {}, onChangeCity = {}, onExplore = {}, onAddDish = {},
            onRefresh = {}, onRetry = {}, greeting = "Lunch o'clock in", cyclePlaceholder = false,
            stories = listOf(
                StoryReel(UserRef("diya_k", "Diya K"), stories = listOf(Story("s1", "/media/godavari_kolhapuri_misal", "/media/godavari_kolhapuri_misal"))),
                StoryReel(UserRef("arjun", "Arjun"), stories = listOf(Story("s2", "/media/pbp_pav_bhaji", "/media/pbp_pav_bhaji"))),
                StoryReel(UserRef("myra_s", "Myra S"), allSeen = true, stories = listOf(Story("s3", "/media/katta_vada_pav", "/media/katta_vada_pav", seen = true))),
                StoryReel(UserRef("kabir", "Kabir"), allSeen = true, stories = listOf(Story("s4", "/media/ananda_cold_coffee", "/media/ananda_cold_coffee", seen = true))),
            ),
            me = UserRef("you", "You"),
            unreadMessages = 2,
        )
    }

    @Test
    fun homeForYou() = shoot("17b_home_for_you") {
        HomeContent(
            state = HomeUiState(
                city = SelectedCity(1, "Nashik"),
                loading = false,
                data = HomeData(
                    forYou = listOf(
                        coffee.copy(because = Because("diya_k", "Diya K", match = 92, people = 3)),
                        thali.copy(because = Because("arjun", "Arjun", match = 81, followed = true)),
                        biryani.copy(because = Because(category = "Biryani")),
                    ),
                    peoplesPicks = listOf(rated(misal, 36, 2, "diya_k", expert = true)),
                ),
                mates = listOf(
                    TasteMate("diya_k", "Diya K", match = 92, coVotes = 11, favourite = "Misal"),
                    TasteMate("kabir", "Kabir", match = 78, coVotes = 6, favourite = "Chai & Coffee"),
                ),
                tasteSet = false,
            ),
            imageUrl = { it }, onCraving = {}, onOpenDish = {}, onOpenUser = {}, onChangeCity = {}, onExplore = {}, onAddDish = {},
            onRefresh = {}, onRetry = {}, greeting = "Lunch o'clock in", cyclePlaceholder = false,
            me = UserRef("you", "You"),
        )
    }

    @Test
    fun mapPopup() = shoot("20_map_place_card") {
        // The card as it sits over the map (the map itself is native and not drawn here).
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFF3EADB))) {
            Column(Modifier.align(Alignment.BottomCenter)) {
                MapPopup(
                    MapPlace(
                        id = "p1", name = "Godavari Misal House", locality = "Panchavati", cuisine = "maharashtrian",
                        openingHours = "Mo-Su 07:00-22:30", lat = 20.0, lng = 73.79, dishCount = 4, lovers = 41, emoji = "🌶️",
                        topDish = MapTopDish("d1", "Kolhapuri Misal", loves = 36, percent = 95, priceInr = 90, isVeg = true, emoji = "🌶️"),
                    ),
                    distanceM = 1200,
                    photoUrl = "/media/godavari_kolhapuri_misal",
                    onDismiss = {}, onTakeMeThere = {}, onOpenDish = {}, onOpenPlace = {},
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Test
    fun inbox() = shoot("21_messages") {
        val now = java.time.Instant.now()
        fun ago(m: Long) = now.minusSeconds(m * 60).toString()
        InboxList(
            state = InboxUiState(
                loading = false,
                items = listOf(
                    ChatSummary("c1", with = UserRef("diya_k", "Diya K"), lastMessage = LastMessage("post", null, false, ago(3)), unread = true),
                    ChatSummary("c2", with = UserRef("arjun", "Arjun"), lastMessage = LastMessage("dish", null, true, ago(45))),
                    ChatSummary("c3", with = UserRef("myra_s", "Myra S"), lastMessage = LastMessage("dish", null, false, ago(60 * 5))),
                ),
            ),
            imageUrl = { it }, onOpen = {}, onLongPress = {},
        )
    }

    @Test
    fun chat() = shoot("22_chat") {
        val now = java.time.Instant.now()
        fun ago(m: Long) = now.minusSeconds(m * 60).toString()
        val msgs = listOf(
            Message("m1", "dish", null, ago(30), mine = false, dish = MessageDish("d1", "Kolhapuri Misal", "Godavari Misal House", 90, "/media/godavari_kolhapuri_misal")),
            Message(
                "m2", "post", null, ago(28), mine = true, reactions = listOf(Reaction("❤️")),
                post = MessagePost("p1", "d2", "/media/katta_vada_pav", "Crisp, spicy, perfect", "Vada Pav", "arjun"),
            ),
            Message("m3", "dish", null, ago(3), mine = false, reactions = listOf(Reaction("😋", mine = true)), dish = MessageDish("d3", "Pav Bhaji", "Pav Bhaji Point", 110, "/media/pbp_pav_bhaji")),
        )
        ChatContent(
            state = ChatUiState(loading = false, chat = Conversation("c1", with = UserRef("diya_k", "Diya K"), theirLastReadAt = now.toString()), messages = msgs),
            imageUrl = { it }, onBack = {}, onOpenUser = {}, onOpenDish = {}, onUnsend = {},
            onDelete = {}, onBlock = {}, onReport = {},
        )
    }

    @Test
    fun craving() = shoot("18_craving_pav_bhaji") {
        val pbp = card("pbp_pav_bhaji", "Pav Bhaji", "Pav Bhaji Point", "Canada Corner", 29, price = 110)
        CravingContent(
            state = CravingUiState(
                loading = false, city = "Nashik",
                result = CravingResult(
                    query = "pav bhaji", label = "Pav Bhaji",
                    results = listOf(
                        rated(pbp, 5, 0, "diya_k", expert = true),
                        rated(card("katta_vada_pav", "Pav Bhaji", "Chai Katta", "Canada Corner", 28, price = 90), 28, 6, "arjun"),
                        rated(card("ananda_masala_dosa", "Butter Pav Bhaji", "Cafe Ananda", "College Road", 9, price = 130), 9, 4),
                    ),
                    tasters = listOf(Taster("diya_k", "Diya K", picks = 6, backers = 31, isExpert = true), Taster("arjun", "Arjun", picks = 4, backers = 12)),
                ),
            ),
            imageUrl = { it }, onBack = {}, onOpenDish = {}, onOpenUser = {}, onAddDish = {}, onRetry = {},
        )
    }

    @Test
    fun activity() = shoot("19_activity") {
        val now = java.time.Instant.now()
        fun ago(m: Long) = now.minusSeconds(m * 60).toString()
        ActivityContent(
            state = ActivityUiState(
                loading = false,
                items = listOf(
                    ActivityItem(1, "agree", ago(4), false, UserRef("diya_k", "Diya K"), NotificationDish("d1", "Kolhapuri Misal", "/media/godavari_kolhapuri_misal")),
                    ActivityItem(2, "follow", ago(30), false, UserRef("arjun", "Arjun")),
                    ActivityItem(3, "comment", ago(90), true, UserRef("myra_s", "Myra S"), NotificationDish("d2", "Vada Pav", "/media/katta_vada_pav"), commentBody = "Is it spicy?"),
                    ActivityItem(4, "recommend", ago(60 * 26), true, UserRef("kabir"), NotificationDish("d3", "Pav Bhaji", "/media/pbp_pav_bhaji")),
                ),
            ),
            imageUrl = { it }, onOpenDish = {}, onOpenUser = {}, onFollow = {}, onRefresh = {},
        )
    }

    @Test
    fun signIn() = shoot("11_create_account") {
        AuthContent(
            AuthUiState(register = true, email = "you@example.com", password = "secret123", handle = "you", acceptTerms = true),
            {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    @Test
    fun onboarding() = shoot("12_onboarding_city") {
        OnboardingContent(
            state = OnboardingUiState(
                step = OnboardingStep.City,
                cities = listOf(
                    City(1, "nashik", "Nashik", "Maharashtra", true),
                    City(2, "pune", "Pune", "Maharashtra", false),
                    City(3, "mumbai", "Mumbai", "Maharashtra", false),
                ),
            ),
            onServerInput = {}, onConnect = {}, onDetect = {}, onChoose = {}, onChangeServer = {},
        )
    }

    @Test
    fun comments() = shoot("16_comments") {
        val now = java.time.Instant.now()
        fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()
        val reply = Comment("c2", "c1", "@diya_k agreed, best tarri in Panchavati", ago(50), UserRef("arjun", "Arjun"), likes = 3)
        CommentsContent(
            state = CommentsUiState(
                loading = false,
                count = 4,
                comments = listOf(
                    Comment(
                        "c1", null, "Fiery tarri. Ask for extra farsan and a second pav!", ago(180),
                        UserRef("diya_k", "Diya K"), likes = 12, likedByMe = true, replies = listOf(reply),
                    ),
                    Comment("c3", null, "Is it too spicy for kids?", ago(60 * 26), UserRef("myra_s", "Myra S"), likes = 1),
                    Comment("c4", null, "Went on Sunday, 20 min queue but worth it", ago(60 * 24 * 9), UserRef("kabir"), canDelete = true),
                ),
                draft = "@diya_k thanks",
                replyTo = Comment("c1", null, "", "", UserRef("diya_k")),
                fresh = setOf("c2"),
            ),
            me = "aniket",
            onDraft = {}, onSend = {}, onReply = {}, onCancelReply = {}, onLike = {}, onDelete = {}, onReport = {},
            onRetry = {}, onOpenUser = {},
        )
    }
}
