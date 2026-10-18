package app.worthabite.ui.activity

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ActivityItem
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.apiCall
import app.worthabite.ui.Format
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.Avatar
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.FollowButton
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActivityUiState(val loading: Boolean = true, val refreshing: Boolean = false, val items: List<ActivityItem> = emptyList(), val error: String? = null)

class ActivityViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = _state.asStateFlow()

    init { load() }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        load()
    }

    fun load() {
        if (app.session.value == null) {
            _state.value = ActivityUiState(loading = false)
            return
        }
        viewModelScope.launch {
            try {
                val page = apiCall { app.api.notifications() }
                _state.update { it.copy(loading = false, refreshing = false, items = page.items, error = null) }
                // Seen: clear the badge, but keep this screen's "New" section until next time.
                runCatching { apiCall { app.api.markRead() } }
                app.unreadActivity.value = 0
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
            }
        }
    }

    /** Follow back, or unfollow, straight from a "started following you" row. */
    fun follow(handle: String, showMessage: (String) -> Unit) {
        val on = _state.value.items.firstOrNull { it.actor.handle == handle }?.followingActor?.not() ?: true
        fun mark(v: Boolean) = _state.update { s -> s.copy(items = s.items.map { if (it.actor.handle == handle) it.copy(followingActor = v) else it }) }
        mark(on)
        viewModelScope.launch {
            runCatching { app.setFollowing(handle, on) }.onFailure {
                mark(!on)
                showMessage(it.message ?: "Couldn't update.")
            }
        }
    }
}

@Composable
fun ActivityScreen(
    vm: ActivityViewModel,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(session) { vm.load() }
    if (session == null) {
        CenteredMessage(
            "Your activity",
            "Sign in to see who follows you, comments on your dishes and agrees with your picks.",
            AppIcons.BellOutline, "Sign in", onSignIn,
        )
        return
    }
    ActivityContent(state, { app.mediaUrl(it) }, onOpenDish, onOpenUser, { vm.follow(it, showMessage) }, vm::refresh)
}

/**
 * The Activity tab, Instagram style: who followed you, commented, replied, mentioned you,
 * liked your comment, loved your dish, or agreed with one of your picks. Unread items sit
 * under "New" and slide in one after another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityContent(
    state: ActivityUiState,
    imageUrl: (String?) -> String?,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onFollow: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp)) {
            KickerText("What's happening")
            Text("Activity", style = MaterialTheme.typography.displaySmall)
        }
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingBox()
                state.error != null && state.items.isEmpty() -> CenteredMessage("Couldn't load", state.error, Icons.Filled.Refresh, "Try again", onRefresh)
                state.items.isEmpty() -> CenteredMessage(
                    "Nothing yet",
                    "When people follow you, comment on your dishes or agree with your picks, you'll see it here.",
                    AppIcons.BellOutline,
                )
                else -> {
                    val fresh = state.items.filter { !it.read }
                    val earlier = state.items.filter { it.read }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (fresh.isNotEmpty()) item(key = "new") { SectionLabel("New") }
                        itemsIndexed(fresh, key = { _, n -> n.id }) { i, n -> ActivityRow(n, i, imageUrl, onOpenDish, onOpenUser, onFollow) }
                        if (earlier.isNotEmpty()) item(key = "earlier") { SectionLabel("Earlier") }
                        itemsIndexed(earlier, key = { _, n -> n.id }) { i, n -> ActivityRow(n, i + fresh.size, imageUrl, onOpenDish, onOpenUser, onFollow) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
}

private fun sentence(n: ActivityItem): Pair<String, String?> = when (n.kind) {
    "follow" -> "started following you." to null
    "comment" -> "commented:" to n.commentBody
    "reply" -> "replied to your comment:" to n.commentBody
    "mention" -> "mentioned you:" to n.commentBody
    "comment_like" -> "liked your comment:" to n.commentBody
    "recommend" -> "loved the ${n.dish?.name ?: "dish"} you posted." to null
    "agree" -> "agreed with your pick: ${n.dish?.name ?: "a dish"}." to null
    "story" -> "posted a new story." to null
    "post" -> "posted ${n.dish?.name ?: "a new dish"}. Worth a bite?" to null
    "story_like" -> "liked your story." to null
    else -> "did something." to null
}

@Composable
private fun ActivityRow(
    n: ActivityItem,
    index: Int,
    imageUrl: (String?) -> String?,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onFollow: (String) -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(30L * index.coerceAtMost(12)); appear.animateTo(1f, Motion.enter(Motion.LONG)) }
    val interaction = remember { MutableInteractionSource() }
    val (what, quote) = sentence(n)
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationX = (1f - appear.value) * 40f
            }
            .pressScale(interaction, 0.98f)
            .clickable(interaction, indication = null, role = Role.Button) {
                val dish = n.dish
                if (dish != null && n.kind != "follow" && n.kind != "story" && n.kind != "story_like") onOpenDish(dish.id)
                else onOpenUser(n.actor.handle)
            }
            .then(if (!n.read) Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(n.actor.handle, n.actor.displayName, 46.dp, Modifier.clip(CircleShape).clickable { onOpenUser(n.actor.handle) }, photo = n.actor.avatarUrl)
            if (!n.read) {
                Box(Modifier.align(Alignment.TopEnd).size(10.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(n.actor.handle) }
                append(" $what")
                if (quote != null) append(" “$quote”")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("  ${Format.timeAgo(n.createdAt)}") }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        when {
            n.kind == "follow" -> FollowButton(n.followingActor, { onFollow(n.actor.handle) }, small = true, followsYou = true)
            n.dish != null -> DishImage(imageUrl(n.dish.thumbUrl), null, n.dish.name, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        }
    }
}
