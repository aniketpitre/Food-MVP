package app.foodfeed

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
import app.foodfeed.ui.restaurant.RestaurantContent
import app.foodfeed.ui.saved.SavedContent
import app.foodfeed.ui.search.SearchContent
import app.foodfeed.ui.search.SearchUiState
import app.foodfeed.data.SearchResult
import app.foodfeed.ui.theme.FoodFeedTheme
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
            ),
            engagement = EngagementState(d.stats, d.me),
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
            profile = UserProfile(
                handle = "aniket", displayName = "Aniket", recommendations = 42, isMe = true,
                posts = listOf(
                    ProfilePost("1", "d1", "/media/ananda_sabudana_khichdi", null, "live", "Sabudana Khichdi", "Cafe Ananda"),
                    ProfilePost("2", "d2", "/media/katta_vada_pav", null, "live", "Vada Pav", "Chai Katta"),
                    ProfilePost("3", "d3", "/media/sangam_jalebi", null, "live", "Jalebi", "Sweet Sangam", "pending"),
                    ProfilePost("4", "d4", "/media/udupi_filter_coffee", null, "live", "Filter Coffee", "Udupi Swad"),
                    ProfilePost("5", "d5", "/media/pbp_pav_bhaji", null, "live", "Pav Bhaji", "Pav Bhaji Point"),
                ),
            ),
            thumbOf = { it }, onBack = null, onOpenDish = {}, onSettings = {}, onBlockToggle = {}, onReport = {},
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
}
