package app.worthabite.ui.feed

import app.worthabite.ui.explore.PlacesScreen
import app.worthabite.ui.explore.PlacesViewModel
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.worthabite.data.engagement
import app.worthabite.data.DeviceLocation
import androidx.compose.runtime.saveable.rememberSaveable
import app.worthabite.ui.comments.CommentsSheet
import app.worthabite.ui.appViewModel
import app.worthabite.ui.map.FoodMap
import app.worthabite.ui.map.MapViewModel
import app.worthabite.data.LocationResult
import app.worthabite.data.DishCard
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.rememberDishActions
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.SkeletonBlock
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import app.worthabite.ui.messages.SendToSheet
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
    onSearch: (() -> Unit)? = null,
    onAddDish: () -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
    onOpenUser: (String) -> Unit = {},
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)
    val commentCounts by app.commentCounts.collectAsState()
    var commentsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var sendingCard by remember { mutableStateOf<DishCard?>(null) }
    // From the Following feed: the photo you were looking at, not the dish's cover.
    var sendingPost by remember { mutableStateOf<String?>(null) }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            scope.launch {
                val found = DeviceLocation.locate(context)
                app.location.value = (found as? LocationResult.Found)?.coords
                DeviceLocation.explain(found)?.let(showMessage)
                if (found == LocationResult.Off) DeviceLocation.openSettings(context)
                vm.locationChanged()
            }
        } else {
            showMessage("No problem. Allow location any time to sort dishes by distance.")
        }
    }

    FeedContent(
        state = state,
        engagementOf = { card -> overrides[card.id] ?: card.engagement() },
        imageUrlOf = { card -> app.mediaUrl(card.cover?.imageUrl) },
        onTab = vm::selectTab,
        onCategory = vm::selectCategory,
        onRefresh = vm::refresh,
        onRetry = { vm.reload() },
        onLoadMore = vm::loadMore,
        onOpenDish = { id -> app.recordOpen(id, state.tab.api); onOpenDish(id) },
        onOpenRestaurant = onOpenRestaurant,
        onToggle = actions::toggle,
        onShare = { sendingPost = null; sendingCard = it },
        onChangeCity = onChangeCity,
        onSearch = onSearch,
        onAddDish = onAddDish,
        onAskLocation = { locationPermission.launch(DeviceLocation.PERMISSIONS) },
        commentCountOf = { card -> card.cover?.let { commentCounts[it.postId] ?: it.commentCount } ?: 0 },
        followingContent = {
            val followingVm = appViewModel { FollowingViewModel(it) }
            FollowingFeed(
                followingVm,
                onOpenDish = { id -> app.recordOpen(id, "following"); onOpenDish(id) },
                onOpenRestaurant = onOpenRestaurant,
                onOpenUser = onOpenUser,
                onToggle = actions::toggle,
                engagementOf = { card -> overrides[card.id] ?: card.engagement() },
                onShare = { card, postId -> sendingPost = postId; sendingCard = card },
                onComments = { postId -> commentsFor = postId },
                commentCounts = commentCounts,
            )
        },
        placesContent = {
            val placesVm = appViewModel { PlacesViewModel(it) }
            PlacesScreen(
                placesVm, onOpenRestaurant,
                onAskLocation = { locationPermission.launch(DeviceLocation.PERMISSIONS) },
                onAddDish = onAddDish,
            )
        },
        nearbyContent = {
            val mapVm = appViewModel { MapViewModel(it) }
            val location by app.location.collectAsState()
            FoodMap(mapVm, location, onOpenDish, onOpenRestaurant, onNeedSignIn = onNeedSignIn)
        },
        onComments = { card ->
            val post = card.cover?.postId
            if (post == null) showMessage("This dish has no photo to comment on yet.") else commentsFor = post
        },
        onSettled = { card, next ->
            app.recordImpression(card.id, state.tab.api)
            // Prefetch the next few photos so the next swipe is instant.
            // Screen-sized (not original-sized) so the memory cache keeps more photos.
            val loader = SingletonImageLoader.get(context)
            val screen = context.resources.displayMetrics
            next.forEach { c ->
                app.mediaUrl(c.cover?.imageUrl)?.let {
                    loader.enqueue(ImageRequest.Builder(context).data(it).size(screen.widthPixels, screen.heightPixels).build())
                }
            }
        },
    )

    commentsFor?.let { post ->
        CommentsSheet(post, onDismiss = { commentsFor = null }, onNeedSignIn = onNeedSignIn, onOpenUser = onOpenUser, showMessage = showMessage)
    }
    // The paper-plane on a card: send this post to friends in WorthAbite, or share it elsewhere.
    sendingCard?.let { c ->
        SendToSheet(
            dishId = if (c.cover == null && sendingPost == null) c.id else null,
            postId = sendingPost ?: c.cover?.postId,
            onDismiss = { sendingCard = null; sendingPost = null },
            onShareElsewhere = { actions.share(c) },
            onNeedSignIn = onNeedSignIn,
            showMessage = showMessage,
        )
    }
}

private enum class FeedBody { Loading, Error, NeedsLocation, Empty, Cards, Map, Places, Following }

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
    onSearch: (() -> Unit)? = null,
    onAddDish: () -> Unit,
    onAskLocation: () -> Unit,
    onSettled: (DishCard, List<DishCard>) -> Unit = { _, _ -> },
    commentCountOf: (DishCard) -> Int = { it.cover?.commentCount ?: 0 },
    onComments: (DishCard) -> Unit = {},
    nearbyContent: (@Composable () -> Unit)? = null,
    placesContent: (@Composable () -> Unit)? = null,
    followingContent: (@Composable () -> Unit)? = null,
) {
    var showFilter by remember { mutableStateOf(false) }
    val selectedCategory = state.categories.firstOrNull { it.id == state.selectedCategory }

    Column(Modifier.fillMaxSize()) {
        FeedHeader(
            cityName = state.city?.name ?: "Choose a city",
            filterActive = selectedCategory != null,
            onCityClick = onChangeCity,
            onSearch = onSearch,
            onFilter = { showFilter = true },
        )
        FeedTabs(state.tab, state.tabs, onTab)
        if (state.tab != FeedTab.Places && state.tab != FeedTab.Following) ActiveFilterChip(selectedCategory) { onCategory(null) }

        val body = when {
            state.tab == FeedTab.Nearby && nearbyContent != null -> FeedBody.Map
            state.tab == FeedTab.Places && placesContent != null -> FeedBody.Places
            state.tab == FeedTab.Following && followingContent != null -> FeedBody.Following
            state.loading && state.items.isEmpty() -> FeedBody.Loading
            state.error != null && state.items.isEmpty() -> FeedBody.Error
            state.needsLocation -> FeedBody.NeedsLocation
            state.items.isEmpty() -> FeedBody.Empty
            else -> FeedBody.Cards
        }
        // States cross-fade; content arrives rising and zooming in so it feels placed, not swapped.
        AnimatedContent(
            targetState = body,
            transitionSpec = {
                (fadeIn(Motion.enter(Motion.LONG, 60)) + scaleIn(Motion.enter(Motion.LONG, 60), initialScale = 0.92f) +
                    slideInVertically(Motion.enter(Motion.LONG, 60)) { it / 10 })
                    .togetherWith(fadeOut(Motion.exit(120)) + scaleOut(Motion.exit(120), targetScale = 0.97f))
            },
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).padding(bottom = 10.dp),
            label = "feed-body",
        ) { b ->
            when (b) {
                FeedBody.Map -> nearbyContent?.invoke()
                FeedBody.Places -> placesContent?.invoke()
                FeedBody.Following -> followingContent?.invoke()
                FeedBody.Loading -> FeedSkeleton()
                FeedBody.Error -> CenteredMessage("Couldn't load dishes", state.error, Icons.Filled.Refresh, "Try again", onRetry)
                FeedBody.NeedsLocation -> CenteredMessage(
                    title = "What's good near you",
                    body = "Allow approximate location to sort dishes by distance. It's only used while the app is open.",
                    icon = AppIcons.Near,
                    actionLabel = "Allow location",
                    onAction = onAskLocation,
                )
                FeedBody.Empty -> EmptyFeed(state, onAddDish)
                FeedBody.Cards -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
                    DishPager(
                        state, engagementOf, imageUrlOf, onLoadMore, onOpenDish, onOpenRestaurant, onToggle, onShare, onSettled,
                        commentCountOf, onComments,
                    )
                }
            }
        }
    }

    if (showFilter) {
        CategorySheet(
            categories = state.categories,
            selected = state.selectedCategory,
            onSelect = { onCategory(it); showFilter = false },
            onDismiss = { showFilter = false },
        )
    }
}

@Composable
private fun FeedSkeleton() {
    Box(Modifier.fillMaxSize()) {
        SkeletonBlock(Modifier.fillMaxSize(), RoundedCornerShape(32.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
            SkeletonBlock(Modifier.width(64.dp).height(22.dp), RoundedCornerShape(8.dp))
            Spacer(Modifier.height(12.dp))
            SkeletonBlock(Modifier.width(230.dp).height(34.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.height(10.dp))
            SkeletonBlock(Modifier.width(160.dp).height(16.dp), RoundedCornerShape(6.dp))
        }
    }
}

@Composable
private fun EmptyFeed(state: FeedUiState, onAddDish: () -> Unit) {
    val city = state.city?.name ?: "your city"
    when (state.tab) {
        FeedTab.ForYou -> CenteredMessage(
            title = "Nothing picked for you yet",
            body = "Love a few dishes and follow people whose taste you trust: their picks show up here. Tell us your taste in Settings to get started.",
        )
        else -> CenteredMessage(
            title = if (state.selectedCategory != null) "Nothing here yet" else "No dishes in $city yet",
            body = "Be the first to post a dish you loved.",
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
    commentCountOf: (DishCard) -> Int,
    onComments: (DishCard) -> Unit,
) {
    val items = state.items
    val pager = rememberPagerState { items.size }

    LaunchedEffect(state.generation) { if (pager.currentPage != 0) pager.animateScrollToPage(0) }
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
        pageSpacing = 12.dp,
        modifier = Modifier.fillMaxSize().testTag("feed_pager"),
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
            // Signed distance of this page from the centre, read during draw only.
            pageOffset = { (pager.currentPage - page) + pager.currentPageOffsetFraction },
            // Start the landing choreography as soon as the swipe commits to this card.
            active = remember(page) { derivedStateOf { pager.targetPage == page } }.value,
            commentCount = commentCountOf(card),
            onComments = { onComments(card) },
        )
    }
}
