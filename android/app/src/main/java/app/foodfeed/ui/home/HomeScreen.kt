package app.foodfeed.ui.home

import app.foodfeed.data.StoryReel
import app.foodfeed.data.UserRef
import app.foodfeed.ui.stories.StoriesRow
import app.foodfeed.ui.components.AppIcons
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.HomeData
import app.foodfeed.ui.feed.becauseLine
import app.foodfeed.data.TasteMate
import app.foodfeed.data.SelectedCity
import app.foodfeed.data.Taster
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.ChampionLine
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.RatingPill
import app.foodfeed.ui.components.Sticker
import app.foodfeed.ui.components.TasterCard
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.feed.greetingFor
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.SkeletonBlock
import app.foodfeed.ui.theme.Turmeric
import app.foodfeed.ui.theme.HeroOrigin
import app.foodfeed.ui.theme.dishHero
import app.foodfeed.ui.theme.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val city: SelectedCity? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val data: HomeData = HomeData(),
    val error: String? = null,
    /** People whose taste matches yours, to follow (signed in only). */
    val mates: List<TasteMate> = emptyList(),
    /** Whether you've told us your taste; null for a guest or while unknown. */
    val tasteSet: Boolean? = null,
)

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.city, app.contentVersion, app.location) { c, _, _ -> c }.collect { city ->
                _state.update { it.copy(city = city) }
                if (city != null) load()
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    /** Follows someone suggested for their taste; they leave the suggestions. */
    fun followMate(handle: String) {
        _state.update { s -> s.copy(mates = s.mates.filterNot { it.handle == handle }) }
        viewModelScope.launch { runCatching { apiCall { app.api.follow(handle) } } }
    }

    fun load() {
        val city = app.city.value ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = it.data == HomeData() && !it.refreshing, error = null) }
            try {
                val loc = app.location.value
                val data = apiCall { app.api.home(city.id, loc?.lat, loc?.lng) }
                _state.update { it.copy(loading = false, refreshing = false, data = data) }
                if (app.session.value != null) {
                    val mates = runCatching { apiCall { app.api.tasteMates(8) } }.getOrDefault(emptyList())
                    val taste = runCatching { apiCall { app.api.taste() } }.getOrNull()
                    _state.update { it.copy(mates = mates, tasteSet = taste?.set) }
                } else {
                    _state.update { it.copy(mates = emptyList(), tasteSet = null) }
                }
                runCatching { apiCall { app.api.stories(city.id) } }.onSuccess { app.storyTray.value = it.items }
                app.refreshUnreadMessages()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
            }
        }
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onCraving: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onChangeCity: () -> Unit,
    onExplore: () -> Unit,
    onAddDish: () -> Unit,
    onOpenStory: (Int) -> Unit = {},
    onAddStory: () -> Unit = {},
    onInbox: () -> Unit = {},
    onTaste: () -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val stories by app.storyTray.collectAsState()
    val unreadMessages by app.unreadMessages.collectAsState()
    val session by app.session.collectAsState()
    // A new line each time Home opens, picked for the time of day.
    val headline = remember { headlineFor(java.time.LocalTime.now().hour, kotlin.random.Random.nextInt(100)) }
    HomeContent(
        headline = headline,
        stories = stories,
        me = session?.user?.let { UserRef(it.handle, it.displayName, it.avatarUrl) },
        unreadMessages = unreadMessages,
        onOpenStory = onOpenStory,
        onAddStory = onAddStory,
        onInbox = onInbox,
        state = state,
        imageUrl = { app.mediaUrl(it) },
        onCraving = onCraving,
        onOpenDish = onOpenDish,
        onOpenUser = onOpenUser,
        onChangeCity = onChangeCity,
        onExplore = onExplore,
        onAddDish = onAddDish,
        onRefresh = vm::refresh,
        onRetry = vm::load,
        onTaste = onTaste,
        onFollowMate = vm::followMate,
        onSearch = onSearch,
    )
}

/** Home's big question, by time of day; [pick] chooses among the lines for that hour. */
fun headlineFor(hour: Int, pick: Int): String {
    val lines = when (hour) {
        in 5..10 -> listOf("What's for\nbreakfast?", "Poha or\nmisal today?", "Chai and\nsomething?", "Breakfast\ncalling?")
        in 11..15 -> listOf("What's for\nlunch?", "Thali or\nbiryani?", "Hungry\nalready?", "What are you\ncraving?")
        in 16..18 -> listOf("Chai-time\ncravings?", "Evening\nsnack?", "Vada pav\no'clock?", "What are you\ncraving?")
        in 19..22 -> listOf("What's for\ndinner?", "Dinner\ntonight?", "Craving something\ngood?", "What are you\ncraving?")
        else -> listOf("Midnight\nmunchies?", "Late-night\ncravings?", "Still\nhungry?", "Night owl\nsnack?")
    }
    return lines[Math.floorMod(pick, lines.size)]
}

/** Things people crave, cycled in the search box until you start typing. */
private val Ideas = listOf("pav bhaji", "misal pav", "cold coffee", "biryani", "masala dosa", "vada pav", "jalebi", "thali")

/**
 * Home asks the one question the app exists for: "What are you craving?". Type a dish (any
 * spelling) or tap a craving, and the next screen ranks where to get it by what people who
 * tried it decided. Below: today's People's Picks, what's good near you, what people you
 * follow loved, the city's tastemakers, and what's trending or new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    imageUrl: (String?) -> String?,
    onCraving: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onChangeCity: () -> Unit,
    onExplore: () -> Unit,
    onAddDish: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    greeting: String = greetingFor(java.time.LocalTime.now().hour),
    headline: String = headlineFor(java.time.LocalTime.now().hour, 0),
    cyclePlaceholder: Boolean = true,
    stories: List<StoryReel> = emptyList(),
    me: UserRef? = null,
    unreadMessages: Int = 0,
    onOpenStory: (Int) -> Unit = {},
    onAddStory: () -> Unit = {},
    onInbox: () -> Unit = {},
    onTaste: () -> Unit = {},
    onFollowMate: (String) -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val d = state.data
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("home_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "stories") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    app.foodfeed.ui.components.Wordmark(modifier = Modifier.weight(1f))
                    MessagesButton(unreadMessages, onInbox)
                }
                StoriesRow(stories, me, imageUrl, onOpenStory, onAddStory, Modifier.padding(top = 8.dp))
            }
            item(key = "hero") {
                CravingHero(state.city?.name ?: "your city", greeting, headline, onChangeCity, onCraving, cyclePlaceholder, onSearch)
            }
            if (d.cravings.isNotEmpty()) {
                item(key = "chips") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    ) {
                        itemsIndexed(d.cravings, key = { _, c -> c.label }) { i, c ->
                            CravingChip(listOfNotNull(c.emoji, c.label).joinToString("  "), i) { onCraving(c.label) }
                        }
                    }
                }
            }
            when {
                state.loading -> item(key = "skeleton") { HomeSkeleton() }
                state.error != null && d == HomeData() -> item(key = "error") {
                    Box(Modifier.height(360.dp)) { CenteredMessage("Couldn't load", state.error, null, "Try again", onRetry) }
                }
                d.peoplesPicks.isEmpty() && d.fresh.isEmpty() -> item(key = "empty") {
                    Box(Modifier.height(380.dp)) {
                        CenteredMessage(
                            "Nothing here yet",
                            "Be the first to post a dish you love in ${state.city?.name ?: "your city"}.",
                            null, "Post a dish", onAddDish,
                        )
                    }
                }
                else -> {
                    if (d.forYou.isNotEmpty()) {
                        item(key = "foryou-title", contentType = "header") {
                            SectionHeader("For you", "Loved by people whose taste matches yours")
                        }
                        item(key = "foryou", contentType = "carousel") {
                            CardCarousel(d.forYou, imageUrl, onOpenDish, onOpenUser, big = true, section = "home:foryou")
                        }
                    }
                    if (state.tasteSet == false) {
                        item(key = "taste-ask", contentType = "prompt") { TastePrompt(onTaste) }
                    }
                    if (state.mates.isNotEmpty()) {
                        item(key = "mates-title", contentType = "header") {
                            SectionHeader("People with your taste", "They love what you love. Follow them for more picks")
                        }
                        item(key = "mates", contentType = "mates") { MateRow(state.mates, onOpenUser, onFollowMate) }
                    }
                    if (d.peoplesPicks.isNotEmpty()) {
                        item(key = "picks-title", contentType = "header") {
                            SectionHeader("People's Pick", "Loved most by people who tried them", onMore = onExplore)
                        }
                        item(key = "picks", contentType = "carousel") { CardCarousel(d.peoplesPicks, imageUrl, onOpenDish, onOpenUser, big = true, ranked = true, section = "home:picks") }
                    }
                    if (d.nearby.isNotEmpty()) {
                        item(key = "near-title", contentType = "header") { SectionHeader("Good near you", "Within 5 km") }
                        item(key = "near", contentType = "carousel") { CardCarousel(d.nearby, imageUrl, onOpenDish, onOpenUser, section = "home:near") }
                    }
                    if (d.following.isNotEmpty()) {
                        item(key = "follow-title", contentType = "header") { SectionHeader("From people you follow", null) }
                        item(key = "follow", contentType = "carousel") {
                            CardCarousel(d.following.map { it.card }, imageUrl, onOpenDish, onOpenUser, lovedBy = d.following.map { it.lovedBy }, section = "home:following")
                        }
                    }
                    if (d.tasters.isNotEmpty()) {
                        item(key = "tasters-title", contentType = "header") { SectionHeader("Tastemakers", "Their picks were backed by the most people") }
                        item(key = "tasters", contentType = "tasters") { TasterRow(d.tasters, onOpenUser) }
                    }
                    if (d.trending.isNotEmpty()) {
                        item(key = "trend-title", contentType = "header") { SectionHeader("Trending this week", null) }
                        item(key = "trend", contentType = "carousel") { CardCarousel(d.trending, imageUrl, onOpenDish, onOpenUser, section = "home:trend") }
                    }
                    if (d.fresh.isNotEmpty()) {
                        item(key = "fresh-title", contentType = "header") { SectionHeader("Just posted", "Help decide: tried any of these?") }
                        item(key = "fresh", contentType = "carousel") { CardCarousel(d.fresh, imageUrl, onOpenDish, onOpenUser, section = "home:fresh") }
                    }
                }
            }
        }
    }
}

/** The big question, a city switch and a search box whose example cravings roll by. */
@Composable
private fun CravingHero(
    city: String,
    greeting: String,
    headline: String,
    onChangeCity: () -> Unit,
    onCraving: (String) -> Unit,
    cycle: Boolean,
    onSearch: () -> Unit,
) {
    var idea by remember { mutableIntStateOf(0) }
    if (cycle) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(2200)
                idea = (idea + 1) % Ideas.size
            }
        }
    }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, Motion.enter(600)) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 12.dp)
            .graphicsLayer {
                alpha = rise.value
                translationY = (1f - rise.value) * 40f
            },
    ) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .pressScale(interaction, 0.97f)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onChangeCity),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KickerText("$greeting $city")
            Icon(Icons.Filled.KeyboardArrowDown, "Change city", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(headline, style = MaterialTheme.typography.displayMedium, lineHeight = 52.sp, modifier = Modifier.testTag("home_headline"))
        Spacer(Modifier.height(16.dp))
        // One search for everything, like Instagram's: dishes, places and people. Tapping the
        // box opens it; the round button goes straight to the best places for the idea shown.
        val field = remember { MutableInteractionSource() }
        Row(
            Modifier
                .fillMaxWidth()
                .pressScale(field, 0.98f)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable(field, indication = null, role = Role.Button, onClick = onSearch)
                .semantics { contentDescription = "Search dishes, places and people" }
                .testTag("craving_input")
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                run {
                    AnimatedContent(
                        targetState = idea,
                        transitionSpec = {
                            (slideInVertically(Motion.enter(Motion.LONG)) { it } + fadeIn(Motion.enter(Motion.LONG)))
                                .togetherWith(slideOutVertically(Motion.exit(Motion.MEDIUM)) { -it } + fadeOut(Motion.exit(Motion.MEDIUM)))
                        },
                        label = "idea",
                    ) { i ->
                        Text(
                            "Search “${Ideas[i]}”, a place, a friend…",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val go = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(44.dp)
                    .pressScale(go, 0.88f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(go, indication = null, role = Role.Button) { onCraving(Ideas[idea]) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Search, "Find the best", tint = Ink, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun CravingChip(label: String, index: Int, onClick: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L * index.coerceAtMost(10)); appear.animateTo(1f, Motion.bouncy()) }
    val interaction = remember { MutableInteractionSource() }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * p
                scaleY = 0.6f + 0.4f * p
            }
            .pressScale(interaction, 0.9f)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
fun SectionHeader(title: String, subtitle: String?, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onMore != null) {
            Text(
                "See all",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onMore).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CardCarousel(
    cards: List<DishCard>,
    imageUrl: (String?) -> String?,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    big: Boolean = false,
    ranked: Boolean = false,
    lovedBy: List<String>? = null,
    section: String = "home",
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(cards, key = { _, c -> c.id }, contentType = { _, _ -> "card" }) { i, c ->
            PickCard(
                c, imageUrl(c.cover?.thumbUrl ?: c.cover?.imageUrl), { HeroOrigin.tap(c.id, section); onOpenDish(c.id) }, onOpenUser,
                width = if (big) 236 else 176, rank = if (ranked) i + 1 else null, lovedBy = lovedBy?.getOrNull(i),
                heroSource = section,
            )
        }
    }
}

// Made once, not for every card on every recomposition.
private val CardShape = RoundedCornerShape(24.dp)
private val CardShade = Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))

/** A dish as a tall card: photo with its verdict on it, name, place, and whose top pick it is. */
@Composable
fun PickCard(
    card: DishCard,
    thumb: String?,
    onClick: () -> Unit,
    onOpenUser: (String) -> Unit,
    width: Int = 200,
    rank: Int? = null,
    lovedBy: String? = null,
    heroSource: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(width.dp)
            .pressScale(interaction, 0.96f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Box {
            DishImage(
                thumb, card.cover?.placeholderColor, card.name,
                Modifier.fillMaxWidth().aspectRatio(0.82f).dishHero(card.id, 24.dp, heroSource).clip(CardShape),
            )
            Box(
                Modifier.matchParentSize().clip(CardShape).background(CardShade),
            )
            if (rank != null) {
                Sticker(
                    "#$rank", if (rank == 1) Turmeric else Color.White, tilt = -8f,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
            RatingPill(card.rating, Modifier.align(Alignment.BottomStart).padding(10.dp), onPhoto = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            VegMark(card.isVeg)
            if (card.isVeg != null) Spacer(Modifier.width(6.dp))
            Text(card.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            listOfNotNull(card.restaurant.name, Format.price(card.priceInr), Format.distance(card.distanceM)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            card.because != null -> Text(
                becauseLine(card.because),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).testTag("because"),
            )
            lovedBy != null -> Text(
                "Loved by @$lovedBy",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
            card.champion != null -> ChampionLine(card.champion, onOpenUser, Modifier.padding(top = 6.dp))
        }
    }
}

/** People whose taste matches yours: a face, how close, what they love, and Follow. */
@Composable
private fun MateRow(mates: List<TasteMate>, onOpenUser: (String) -> Unit, onFollow: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(mates, key = { it.handle }) { m ->
            Column(
                Modifier
                    .width(140.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .clickable { onOpenUser(m.handle) }
                    .testTag("mate_${m.handle}")
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(m.handle, m.displayName, 56.dp, photo = m.avatarUrl)
                Spacer(Modifier.height(8.dp))
                Text("@${m.handle}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${m.match}% taste match",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                m.favourite?.let {
                    Text("Loves $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Follow", { onFollow(m.handle) }, Modifier.fillMaxWidth().height(36.dp))
            }
        }
    }
}

/** Asks for your taste once you're signed in and haven't said yet. */
@Composable
private fun TastePrompt(onTaste: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onTaste)
            .testTag("taste_prompt")
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🌶️", fontSize = 28.sp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Tell us your taste", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Veg or not, how spicy, what you love: we'll find people who eat like you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun TasterRow(tasters: List<Taster>, onOpenUser: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tasters, key = { it.handle }) { t -> TasterCard(t, { onOpenUser(t.handle) }) }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        SkeletonBlock(Modifier.width(180.dp).height(26.dp), RoundedCornerShape(8.dp))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(2) { SkeletonBlock(Modifier.width(236.dp).aspectRatio(0.82f), RoundedCornerShape(24.dp)) }
        }
    }
}

/** The paper plane in Home's corner, with a count of chats waiting for you. */
@Composable
private fun MessagesButton(unread: Int, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick, modifier = Modifier.testTag("messages_button")) {
            Icon(AppIcons.Send, if (unread > 0) "Messages, $unread new" else "Messages", modifier = Modifier.size(26.dp))
        }
        if (unread > 0) {
            Text(
                if (unread > 9) "9+" else unread.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}
