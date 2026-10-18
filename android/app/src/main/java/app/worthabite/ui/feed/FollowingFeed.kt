package app.worthabite.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.DishCard
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.data.FollowingCursor
import app.worthabite.data.FollowingPost
import app.worthabite.data.apiCall
import app.worthabite.ui.Format
import app.worthabite.ui.components.ActionLook
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.Avatar
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.EngageAction
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalMediaUrl
import app.worthabite.ui.components.shownHandle
import app.worthabite.ui.theme.TextFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FollowingUiState(
    val items: List<FollowingPost> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
)

/** The Following tab: photos from the people you follow, newest first, a page at a time. */
class FollowingViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(FollowingUiState())
    val state: StateFlow<FollowingUiState> = _state.asStateFlow()
    private var next: FollowingCursor? = null
    private var job: Job? = null

    init {
        // Following or unfollowing someone anywhere changes this list: reload when it does.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(app.contentVersion, app.followVersion) { a, b -> a to b }.collect { reload() }
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        reload(keepItems = true)
    }

    fun reload(keepItems: Boolean = false) {
        job?.cancel()
        next = null
        _state.update { it.copy(loading = !keepItems, error = null, endReached = false, items = if (keepItems) it.items else emptyList()) }
        job = viewModelScope.launch { fetch(replace = true) }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || job?.isActive == true || next == null) return
        job = viewModelScope.launch { fetch(replace = false) }
    }

    private suspend fun fetch(replace: Boolean) {
        val loc = app.location.value
        try {
            val cursor = if (replace) null else next
            val page = apiCall { app.api.followingFeed(cursor?.before, cursor?.beforeId, 15, loc?.lat, loc?.lng) }
            next = page.next
            _state.update { cur ->
                cur.copy(
                    items = if (replace) page.items else (cur.items + page.items).distinctBy { it.id },
                    loading = false,
                    refreshing = false,
                    endReached = page.next == null,
                )
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingFeed(
    vm: FollowingViewModel,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onToggle: (DishCard, Engagement) -> Unit,
    engagementOf: (DishCard) -> EngagementState,
    /** Send this photo (by its post id) of this dish. */
    onShare: (DishCard, String) -> Unit,
    onComments: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    when {
        state.loading && state.items.isEmpty() -> LoadingBox()
        state.error != null && state.items.isEmpty() ->
            CenteredMessage("Couldn't load", state.error, Icons.Filled.Refresh, "Try again", { vm.reload() })
        state.items.isEmpty() -> CenteredMessage(
            title = "Nothing from people you follow yet",
            body = "Follow people whose taste you trust: their dish photos show up here, newest first, older ones included.",
        )
        else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = vm::refresh) {
            val list = rememberLazyListState()
            val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
            LaunchedEffect(nearEnd, state.items.size) { if (nearEnd >= state.items.size - 3) vm.loadMore() }
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().testTag("following_feed"),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.items, key = { it.id }) { p ->
                    FollowingPostCard(
                        p, engagementOf(p.dish),
                        onOpenDish = { onOpenDish(p.dish.id) },
                        onOpenRestaurant = { onOpenRestaurant(p.dish.restaurant.id) },
                        onOpenUser = { onOpenUser(p.author.handle) },
                        onToggle = { onToggle(p.dish, it) },
                        onShare = { onShare(p.dish, p.id) },
                        onComments = { onComments(p.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.endReached) {
                    item(key = "caught-up") {
                        Text(
                            "You're all caught up",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("following_caught_up"),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** One photo, Instagram style: who posted it, the photo (double-tap to love), the dish and place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FollowingPostCard(
    p: FollowingPost,
    engagement: EngagementState,
    onOpenDish: () -> Unit,
    onOpenRestaurant: () -> Unit,
    onOpenUser: () -> Unit,
    onToggle: (Engagement) -> Unit,
    onShare: () -> Unit,
    onComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media = LocalMediaUrl.current
    Column(modifier.fillMaxWidth().testTag("following_post")) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenUser).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(p.author.handle, p.author.displayName, size = 34.dp, photo = p.author.avatarUrl)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(shownHandle(p.author.handle), fontFamily = TextFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    listOfNotNull(p.dish.restaurant.name, Format.timeAgo(p.createdAt).ifBlank { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (p.verifiedVisit) VerifiedVisitChip()
        }
        val ratio = if ((p.width ?: 0) > 0 && (p.height ?: 0) > 0) (p.width!!.toFloat() / p.height!!).coerceIn(0.8f, 1.6f) else 0.8f
        DishImage(
            url = media(p.imageUrl),
            thumbUrl = media(p.thumbUrl),
            placeholderColor = p.placeholderColor,
            contentDescription = p.dish.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(22.dp))
                .pointerInput(p.id) {
                    detectTapGestures(
                        onTap = { onOpenDish() },
                        onDoubleTap = { if (!engagement.me.recommended) onToggle(Engagement.Recommend) },
                    )
                },
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            EngageAction(
                Engagement.Recommend, engagement.me.recommended, "Love", engagement.stats.recommenders,
                if (engagement.me.recommended) "Remove recommendation" else "Recommend",
                { onToggle(Engagement.Recommend) }, look = ActionLook.Bare,
            )
            EngageAction(null, false, "Comment", null, "Comments", onComments, look = ActionLook.Bare, icon = AppIcons.Comment)
            EngageAction(null, false, "Send", null, "Send to friends", onShare, look = ActionLook.Bare, icon = AppIcons.Send)
            Spacer(Modifier.weight(1f))
            EngageAction(
                Engagement.Save, engagement.me.saved, if (engagement.me.saved) "Saved" else "Save", null,
                if (engagement.me.saved) "Remove from saved" else "Save", { onToggle(Engagement.Save) }, look = ActionLook.Bare,
            )
        }
        Text(
            p.dish.name,
            fontFamily = TextFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            modifier = Modifier.clickable(onClick = onOpenDish),
        )
        Text(
            p.dish.restaurant.name + (p.dish.restaurant.locality?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onOpenRestaurant),
        )
        p.caption?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "Verified visit": the photographer's phone was at the place when they posted it. */
@Composable
fun VerifiedVisitChip(modifier: Modifier = Modifier, onPhoto: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (onPhoto) androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f) else MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("verified_visit"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Pin, null,
            tint = if (onPhoto) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            "Verified visit",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (onPhoto) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

