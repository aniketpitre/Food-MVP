package app.foodfeed.ui.profile

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.BlockBody
import app.foodfeed.data.DishCard
import app.foodfeed.data.ProfilePost
import app.foodfeed.data.UserProfile
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.ExpertBadge
import app.foodfeed.ui.components.FollowButton
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.components.RatingPill
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.components.Tag
import app.foodfeed.ui.theme.Chili
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.RollingNumber
import app.foodfeed.ui.theme.Ember
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

enum class ProfileTab(val label: String) { Posts("Posts"), Loved("Loved"), Saved("Saved") }

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val error: String? = null,
    val tab: ProfileTab = ProfileTab.Posts,
    val loved: List<DishCard>? = null,
    val saved: List<DishCard>? = null,
    /** They have a live story: their photo gets a ring you can tap. */
    val hasStory: Boolean = false,
    val storySeen: Boolean = false,
)

class ProfileViewModel(private val app: AppContainer, private val handle: String?) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.session, app.contentVersion) { s, _ -> s }.collect { load() }
        }
    }

    private fun target() = handle ?: app.session.value?.user?.handle

    fun load() {
        val target = target()
        if (target == null) {
            _state.value = ProfileUiState(loading = false)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = it.profile == null, error = null) }
            try {
                val p = apiCall { app.api.user(target) }
                _state.update { it.copy(loading = false, profile = p, loved = null, saved = null) }
                loadTab(_state.value.tab)
                runCatching { apiCall { app.api.userStories(target) } }.onSuccess { r ->
                    _state.update { it.copy(hasStory = r.stories.isNotEmpty(), storySeen = r.allSeen) }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun selectTab(tab: ProfileTab) {
        _state.update { it.copy(tab = tab) }
        loadTab(tab)
    }

    private fun loadTab(tab: ProfileTab) {
        val target = _state.value.profile?.handle ?: return
        viewModelScope.launch {
            runCatching {
                when (tab) {
                    ProfileTab.Loved -> _state.update { it.copy(loved = apiCall { app.api.userLoved(target) }.items) }
                    ProfileTab.Saved -> if (_state.value.profile?.isMe == true) {
                        val loc = app.location.value
                        _state.update { it.copy(saved = apiCall { app.api.saved(loc?.lat, loc?.lng) }.items) }
                    }
                    ProfileTab.Posts -> Unit
                }
            }
        }
    }

    /** Optimistic follow toggle; the server's follower count replaces ours when it answers. */
    fun toggleFollow(onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
        val p = _state.value.profile ?: return
        if (app.session.value == null) return onNeedSignIn()
        val on = !p.isFollowing
        _state.update { it.copy(profile = p.copy(isFollowing = on, followers = (p.followers + if (on) 1 else -1).coerceAtLeast(0))) }
        viewModelScope.launch {
            try {
                val r = apiCall { if (on) app.api.follow(p.handle) else app.api.unfollow(p.handle) }
                _state.update { s -> s.copy(profile = s.profile?.copy(isFollowing = r.isFollowing, followers = r.followers)) }
            } catch (e: ApiException) {
                _state.update { it.copy(profile = p) }
                showMessage(e.message)
            }
        }
    }

    fun toggleBlock(showMessage: (String) -> Unit) {
        val p = _state.value.profile ?: return
        viewModelScope.launch {
            try {
                if (p.blockedByMe) apiCall { app.api.unblock(p.handle) } else apiCall { app.api.block(BlockBody(p.handle)) }
                showMessage(if (p.blockedByMe) "Unblocked @${p.handle}." else "Blocked @${p.handle}. You won't see their photos or comments.")
                load()
            } catch (e: ApiException) {
                showMessage(e.message)
            }
        }
    }
}

@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    isOwnTab: Boolean,
    onBack: (() -> Unit)?,
    onOpenDish: (String) -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
    showMessage: (String) -> Unit,
    onEditProfile: () -> Unit = {},
    onFollows: (handle: String, followers: Boolean) -> Unit = { _, _ -> },
    onMessage: (String) -> Unit = {},
    onOpenStory: (String) -> Unit = {},
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    var reporting by remember { mutableStateOf(false) }

    if (isOwnTab && session == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
            CenteredMessage(
                "Your food profile",
                "Sign in to post dishes, follow people and get credit for the places you discover.",
                null, "Sign in", onSignIn,
            )
        }
        return
    }
    when {
        state.loading -> LoadingBox()
        state.profile == null -> CenteredMessage("Couldn't load profile", state.error, Icons.Filled.Refresh, "Try again", vm::load)
        else -> ProfileContent(
            state = state,
            imageUrl = { app.mediaUrl(it) },
            onBack = onBack,
            onOpenDish = onOpenDish,
            onSettings = onSettings,
            onEditProfile = onEditProfile,
            onFollow = { vm.toggleFollow(onSignIn, showMessage) },
            onFollows = onFollows,
            onTab = vm::selectTab,
            onBlockToggle = { if (session == null) onSignIn() else vm.toggleBlock(showMessage) },
            onReport = { if (session == null) onSignIn() else reporting = true },
            onMessage = { if (session == null) onSignIn() else onMessage(it) },
            onOpenStory = onOpenStory,
            onShare = { handle ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "See what @$handle recommends on WorthAbite 🍛\n${app.serverUrl.value}")
                }
                context.startActivity(Intent.createChooser(send, "Share profile").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
    }
    if (reporting) {
        ReportSheet(targetType = "user", targetId = null, targetHandle = state.profile?.handle, onDismiss = { reporting = false }, showMessage = showMessage)
    }
}

/**
 * An Instagram-style profile: photo in a spice ring, posts / followers / following counts,
 * name and bio, the dish types people trust them for, Edit profile or Follow, and a tab
 * strip for their posts, the dishes they loved and (your own) saved dishes.
 */
@Composable
fun ProfileContent(
    state: ProfileUiState,
    imageUrl: (String?) -> String?,
    onBack: (() -> Unit)?,
    onOpenDish: (String) -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onFollow: () -> Unit,
    onFollows: (handle: String, followers: Boolean) -> Unit,
    onTab: (ProfileTab) -> Unit,
    onBlockToggle: () -> Unit,
    onReport: () -> Unit,
    onShare: (String) -> Unit,
    onMessage: (String) -> Unit = {},
    onOpenStory: (String) -> Unit = {},
) {
    val profile = state.profile ?: return
    var menu by remember { mutableStateOf(false) }
    val tabs = if (profile.isMe) ProfileTab.entries else listOf(ProfileTab.Posts, ProfileTab.Loved)
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(
                        profile.handle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp),
                    )
                    if (profile.isMe) {
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                    } else {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Share profile") }, onClick = { menu = false; onShare(profile.handle) })
                                DropdownMenuItem(text = { Text(if (profile.blockedByMe) "Unblock" else "Block") }, onClick = { menu = false; onBlockToggle() })
                                DropdownMenuItem(text = { Text("Report") }, onClick = { menu = false; onReport() })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SpiceRingAvatar(profile, state.hasStory, state.storySeen) { onOpenStory(profile.handle) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Count(profile.postCount, "posts", null)
                        Count(profile.followers, "followers") { onFollows(profile.handle, true) }
                        Count(profile.following, "following") { onFollows(profile.handle, false) }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.displayName ?: profile.handle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        if (profile.followsYou && !profile.isMe) {
                            Spacer(Modifier.width(8.dp))
                            Tag("Follows you")
                        }
                    }
                    profile.bio?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                    }
                    Text(
                        "${Format.compact(profile.recommendations)} dishes loved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    profile.tasteMatch?.let { TasteMatchPill(it, Modifier.padding(top = 10.dp)) }
                }
                if (profile.tasteBadges.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        profile.tasteBadges.take(3).forEach { b ->
                            Row(
                                Modifier
                                    .background(if (b.isExpert) Turmeric.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(listOfNotNull(b.emoji, b.category).joinToString(" "), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                if (b.isExpert) {
                                    Spacer(Modifier.width(6.dp))
                                    ExpertBadge()
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profile.isMe) {
                        QuietButton("Edit profile", onEditProfile, Modifier.weight(1f), leading = AppIcons.Edit)
                        QuietButton("Share", { onShare(profile.handle) }, Modifier.weight(1f), leading = Icons.Outlined.Share)
                    } else {
                        FollowButton(profile.isFollowing, onFollow, Modifier.weight(1f), followsYou = profile.followsYou)
                        // Followers and people you follow can share dishes with each other.
                        if (profile.isFollowing || profile.followsYou) {
                            QuietButton("Message", { onMessage(profile.handle) }, Modifier.weight(1f).testTag("message_button"))
                        }
                        QuietButton("", { onShare(profile.handle) }, leading = Icons.Outlined.Share)
                    }
                }
                ProfileTabs(tabs, state.tab, onTab)
            }
        }
        when (state.tab) {
            ProfileTab.Posts -> {
                if (profile.posts.isEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        EmptyTab(if (profile.isMe) "Dishes you post show up here." else "No dishes yet.")
                    }
                }
                itemsIndexed(profile.posts, key = { _, p -> "p" + p.id }) { i, p -> PostTile(p, imageUrl(p.thumbUrl), i) { onOpenDish(p.dishId) } }
            }
            ProfileTab.Loved, ProfileTab.Saved -> {
                val list = if (state.tab == ProfileTab.Loved) state.loved else state.saved
                when {
                    list == null -> item(span = { GridItemSpan(3) }) { Box(Modifier.height(160.dp)) { LoadingBox() } }
                    list.isEmpty() -> item(span = { GridItemSpan(3) }) {
                        EmptyTab(
                            when {
                                state.tab == ProfileTab.Saved -> "Tap the bookmark on any dish to keep it here."
                                profile.isMe -> "Dishes you love show up here."
                                else -> "No loved dishes yet."
                            },
                        )
                    }
                    else -> itemsIndexed(list, key = { _, c -> state.tab.name + c.id }) { i, c ->
                        DishTile(c, imageUrl(c.cover?.thumbUrl), i) { onOpenDish(c.id) }
                    }
                }
            }
        }
    }
}

/** The profile photo inside a spice-coloured ring that spins once into place. */
@Composable
private fun SpiceRingAvatar(profile: UserProfile, hasStory: Boolean = false, storySeen: Boolean = false, onOpenStory: () -> Unit = {}) {
    val spin = remember { Animatable(0f) }
    LaunchedEffect(Unit) { spin.animateTo(1f, tween(1000, easing = Motion.EmphasizedDecelerate)) }
    // A live story thickens the ring (grey once watched) and makes the photo open it.
    val ring = if (hasStory && storySeen) listOf(Color.Gray, Color.Gray) else listOf(Ember, Turmeric, Chili, Ember)
    Box(
        Modifier
            .size(92.dp)
            .then(if (hasStory) Modifier.clip(CircleShape).clickable(onClick = onOpenStory) else Modifier)
            .graphicsLayer { rotationZ = -200f * (1f - spin.value) }
            .drawBehind {
                drawCircle(
                    Brush.sweepGradient(ring),
                    style = Stroke(width = (if (hasStory) 4.5.dp else 3.5.dp).toPx()),
                    radius = size.minDimension / 2 - 2.dp.toPx(),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Avatar(
            profile.handle, profile.displayName, 80.dp,
            Modifier.graphicsLayer { rotationZ = 200f * (1f - spin.value) },
            photo = profile.avatarUrl,
        )
    }
}

@Composable
private fun Count(value: Int, label: String, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RollingNumber(value, MaterialTheme.typography.titleLarge, MaterialTheme.colorScheme.onSurface, format = Format::compact)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Icon tabs with a ember bar that slides under the selected one. */
@Composable
private fun ProfileTabs(tabs: List<ProfileTab>, selected: ProfileTab, onTab: (ProfileTab) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp)) {
        val slot = maxWidth / tabs.size
        val x by animateDpAsState(slot * tabs.indexOf(selected).coerceAtLeast(0), Motion.glide(), label = "tab-bar")
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { t ->
                val on = t == selected
                val tint by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    Motion.enter(Motion.SHORT), label = "tab-tint",
                )
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(tabIcon(t), t.label, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = x + slot / 2 - 18.dp)
                .size(width = 36.dp, height = 3.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

private fun tabIcon(t: ProfileTab): ImageVector = when (t) {
    ProfileTab.Posts -> AppIcons.Grid
    ProfileTab.Loved -> Icons.Filled.Favorite
    ProfileTab.Saved -> AppIcons.Bookmark
}

@Composable
private fun EmptyTab(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
    )
}

@Composable
private fun appear(index: Int): Animatable<Float, *> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(40L * index.coerceAtMost(12)); a.animateTo(1f, Motion.bouncy()) }
    return a
}

/** A post in the grid: square photo that pops in a beat after the one before it. */
@Composable
private fun PostTile(p: ProfilePost, thumb: String?, index: Int, onClick: () -> Unit) {
    val a = appear(index)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .aspectRatio(0.8f)
            .graphicsLayer {
                alpha = a.value.coerceIn(0f, 1f)
                scaleX = 0.8f + 0.2f * a.value
                scaleY = 0.8f + 0.2f * a.value
            }
            .pressScale(interaction, 0.95f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interaction, indication = null, role = Role.Button) {
                HeroOrigin.tap(p.dishId, "post:${p.id}")
                onClick()
            },
    ) {
        // Several photos in the grid can be of the same dish; each is its own hero source.
        DishImage(thumb, p.placeholderColor, p.dishName, Modifier.fillMaxSize().dishHero(p.dishId, 14.dp, "post:${p.id}"))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
        val label = when {
            p.restaurantStatus == "pending" -> "In review"
            p.status == "hidden" -> "Hidden"
            else -> null
        }
        if (label != null) Tag(label, onPhoto = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        Text(
            p.dishName, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

/** A dish in the Loved / Saved grids, with its People's Pick verdict. */
@Composable
private fun DishTile(c: DishCard, thumb: String?, index: Int, onClick: () -> Unit) {
    val a = appear(index)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .aspectRatio(0.8f)
            .graphicsLayer {
                alpha = a.value.coerceIn(0f, 1f)
                scaleX = 0.8f + 0.2f * a.value
                scaleY = 0.8f + 0.2f * a.value
            }
            .pressScale(interaction, 0.95f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        DishImage(thumb, c.cover?.placeholderColor, c.name, Modifier.fillMaxSize().dishHero(c.id, 14.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(c.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.restaurant.name, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (c.rating.percent != null) RatingPill(c.rating, Modifier.align(Alignment.TopStart).padding(6.dp), onPhoto = true)
    }
}

/**
 * How alike your taste and theirs are, with what it's based on. Tap for the details:
 * how many dishes you both voted on and agreed about, and the dish types you share.
 */
@Composable
private fun TasteMatchPill(m: app.foodfeed.data.TasteMatch, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { open = true }
            .testTag("taste_match")
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🍽️", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Text(
            "${m.percent}% taste match",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        m.shared.firstOrNull()?.let {
            Text(
                " · both love ${it.name}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("${m.percent}% taste match") },
            text = {
                Text(
                    buildString {
                        if (m.coVotes > 0) {
                            append("You both voted on ${m.coVotes} of the same ${if (m.coVotes == 1) "dish" else "dishes"} and agreed on ${m.agreed}.")
                        } else {
                            append("You haven't voted on the same dishes yet.")
                        }
                        if (m.shared.isNotEmpty()) append(" You both love ${m.shared.joinToString(", ") { it.name }}.")
                        append("\n\nIt also counts the dish types you each love and what you told us about your taste (veg or not, spice). The more you both vote, the surer it gets.")
                    },
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("OK") } },
        )
    }
}
