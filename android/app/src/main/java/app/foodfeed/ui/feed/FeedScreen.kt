package app.foodfeed.ui.feed

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foodfeed.data.DeviceLocation
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.rememberDishActions
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun FeedScreen(
    vm: FeedViewModel,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onChangeCity: () -> Unit,
    onSearch: () -> Unit,
    onAddDish: () -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch {
                app.location.value = DeviceLocation.current(context)
                if (app.location.value == null) showMessage("Couldn't get your location. Is location turned on?")
                vm.locationChanged()
            }
        }
    }

    FeedContent(
        state = state,
        engagementOf = { card -> overrides[card.id] ?: EngagementState(card.stats, card.me) },
        imageUrlOf = { card -> app.mediaUrl(card.cover?.imageUrl) },
        onTab = vm::selectTab,
        onCategory = vm::selectCategory,
        onRefresh = vm::refresh,
        onRetry = { vm.reload() },
        onLoadMore = vm::loadMore,
        onOpenDish = onOpenDish,
        onOpenRestaurant = onOpenRestaurant,
        onToggle = actions::toggle,
        onShare = actions::share,
        onChangeCity = onChangeCity,
        onSearch = onSearch,
        onAddDish = onAddDish,
        onAskLocation = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
        onSettled = { card, next ->
            app.recordImpression(card.id)
            // Prefetch the next few photos so the next swipe is instant.
            val loader = SingletonImageLoader.get(context)
            next.forEach { c ->
                app.mediaUrl(c.cover?.imageUrl)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).build()) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedContent(
    state: FeedUiState,
    engagementOf: (DishCard) -> EngagementState,
    imageUrlOf: (DishCard) -> String?,
    onTab: (FeedTab) -> Unit,
    onCategory: (Int?) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onToggle: (DishCard, Engagement) -> Unit,
    onShare: (DishCard) -> Unit,
    onChangeCity: () -> Unit,
    onSearch: () -> Unit,
    onAddDish: () -> Unit,
    onAskLocation: () -> Unit,
    onSettled: (DishCard, List<DishCard>) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize()) {
        FeedHeader(state.city?.name ?: "Choose a city", onChangeCity, onSearch)
        CategoryStories(state.categories, state.selectedCategory, onCategory)
        FeedTabs(state.tab, onTab)

        Box(Modifier.weight(1f).padding(horizontal = 10.dp).padding(bottom = 8.dp)) {
            when {
                state.loading && state.items.isEmpty() -> LoadingBox()
                state.error != null && state.items.isEmpty() -> CenteredMessage(
                    title = "Couldn't load dishes",
                    body = state.error,
                    icon = Icons.Filled.Refresh,
                    actionLabel = "Try again",
                    onAction = onRetry,
                )
                state.needsLocation -> CenteredMessage(
                    title = "See what's good near you",
                    body = "Allow approximate location to sort dishes by distance. We only use it while the app is open and never store it.",
                    icon = AppIcons.Near,
                    actionLabel = "Allow location",
                    onAction = onAskLocation,
                )
                state.items.isEmpty() -> EmptyFeed(state, onAddDish)
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
                    DishPager(state, engagementOf, imageUrlOf, onLoadMore, onOpenDish, onOpenRestaurant, onToggle, onShare, onSettled)
                }
            }
        }
    }
}

@Composable
private fun EmptyFeed(state: FeedUiState, onAddDish: () -> Unit) {
    val city = state.city?.name ?: "your city"
    when (state.tab) {
        FeedTab.Gems -> CenteredMessage(
            title = "No hidden gems yet",
            body = "A dish becomes a hidden gem when at least 5 people back it at a place few people post about. Keep recommending!",
            icon = AppIcons.Gem,
        )
        FeedTab.Trending -> CenteredMessage(
            title = "Nothing trending this week",
            body = "Dishes people recommend, save or share in the last 7 days show up here.",
            icon = AppIcons.Trending,
        )
        else -> CenteredMessage(
            title = if (state.selectedCategory != null) "Nothing in this category yet" else "No dishes in $city yet",
            body = "Be the first to post a dish you loved.",
            icon = AppIcons.Restaurant,
            actionLabel = "Post a dish",
            onAction = onAddDish,
        )
    }
}

@Composable
private fun DishPager(
    state: FeedUiState,
    engagementOf: (DishCard) -> EngagementState,
    imageUrlOf: (DishCard) -> String?,
    onLoadMore: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onToggle: (DishCard, Engagement) -> Unit,
    onShare: (DishCard) -> Unit,
    onSettled: (DishCard, List<DishCard>) -> Unit,
) {
    val items = state.items
    val pager = rememberPagerState { items.size }

    LaunchedEffect(state.generation) { if (pager.currentPage != 0) pager.scrollToPage(0) }
    LaunchedEffect(pager, items) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collectLatest { page ->
            if (page >= items.size - 4) onLoadMore()
            val card = items.getOrNull(page) ?: return@collectLatest
            delay(1_000) // counts as an impression after a full second on screen
            onSettled(card, items.drop(page + 1).take(3))
        }
    }

    VerticalPager(
        state = pager,
        key = { items[it].id },
        beyondViewportPageCount = 1,
        pageSpacing = 10.dp,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val card = items[page]
        DishPage(
            card = card,
            engagement = engagementOf(card),
            imageUrl = imageUrlOf(card),
            onOpen = { onOpenDish(card.id) },
            onRestaurant = { onOpenRestaurant(card.restaurant.id) },
            onToggle = { kind -> onToggle(card, kind) },
            onShare = { onShare(card) },
        )
    }
}
