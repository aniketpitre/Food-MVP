package app.foodfeed.ui.stories

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.ImageCompressor
import app.foodfeed.data.LikeBody
import app.foodfeed.data.ReplyBody
import app.foodfeed.data.Story
import app.foodfeed.data.StoryReel
import app.foodfeed.data.StoryViewer
import app.foodfeed.data.UserRef
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.theme.Chili
import app.foodfeed.ui.theme.LocalLiteMode
import app.foodfeed.ui.theme.Saffron
import app.foodfeed.ui.theme.Turmeric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

private const val STORY_MILLIS = 5_000

// ---------------------------------------------------------------- Tray on Home

/**
 * The stories row at the top of Home: your story first (with a + to add one), then everyone
 * else, unwatched first, each in a spice ring that turns grey once you've seen it all.
 */
@Composable
fun StoriesRow(
    reels: List<StoryReel>,
    me: UserRef?,
    imageUrl: (String?) -> String?,
    onOpen: (Int) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mine = reels.indexOfFirst { it.isMe }
    LazyRow(
        modifier.fillMaxWidth().testTag("stories_row"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "me") {
            val reel = reels.getOrNull(mine)
            StoryBubble(
                user = me ?: UserRef("you"),
                label = "Your story",
                ring = if (reel != null) (if (reel.allSeen) RingState.Seen else RingState.New) else RingState.None,
                photo = imageUrl(me?.avatarUrl),
                plus = true,
                onClick = { if (reel != null) onOpen(mine) else onAdd() },
                onPlus = onAdd,
            )
        }
        itemsIndexed(reels, key = { _, r -> r.user.handle }) { i, r ->
            if (!r.isMe) {
                StoryBubble(
                    user = r.user,
                    label = r.user.handle,
                    ring = if (r.allSeen) RingState.Seen else RingState.New,
                    photo = imageUrl(r.user.avatarUrl),
                    onClick = { onOpen(i) },
                )
            }
        }
    }
}

enum class RingState { None, New, Seen }

@Composable
private fun StoryBubble(
    user: UserRef,
    label: String,
    ring: RingState,
    photo: String?,
    onClick: () -> Unit,
    plus: Boolean = false,
    onPlus: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(
            Modifier
                .size(70.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .then(
                    when (ring) {
                        RingState.New -> Modifier.border(3.dp, Brush.sweepGradient(listOf(Saffron, Turmeric, Chili, Saffron)), CircleShape)
                        RingState.Seen -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        RingState.None -> Modifier
                    },
                )
                .padding(5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(user.handle, user.displayName, 60.dp, photo = photo)
        }
        if (plus) {
            Box(
                Modifier
                    .offset(x = 24.dp, y = (-20).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlus),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, "Add to your story", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (plus) 0.dp else 4.dp).offset(y = if (plus) (-16).dp else 0.dp),
        )
    }
}

// ---------------------------------------------------------------- Viewer

data class StoryViewerUiState(val loading: Boolean = true, val reels: List<StoryReel> = emptyList(), val error: String? = null)

/** Pages through the Home tray from [start], or through one person's stories when [handle] is set. */
class StoryViewerViewModel(private val app: AppContainer, private val handle: String?, val start: Int) : ViewModel() {
    private val _state = MutableStateFlow(StoryViewerUiState())
    val state: StateFlow<StoryViewerUiState> = _state.asStateFlow()

    init {
        if (handle == null) {
            _state.value = StoryViewerUiState(loading = false, reels = app.storyTray.value)
        } else {
            viewModelScope.launch {
                try {
                    val r = apiCall { app.api.userStories(handle) }
                    _state.value = StoryViewerUiState(loading = false, reels = listOf(r).filter { it.stories.isNotEmpty() })
                } catch (e: ApiException) {
                    _state.value = StoryViewerUiState(loading = false, error = e.message)
                }
            }
        }
    }

    val signedIn get() = app.session.value != null

    fun seen(reel: StoryReel, story: Story) {
        if (reel.isMe || story.seen || !signedIn) return
        viewModelScope.launch { runCatching { apiCall { app.api.viewStory(story.id) } } }
        // Mark it locally so the tray ring greys out without a reload.
        app.storyTray.update { tray ->
            tray.map { r ->
                if (r.user.handle != reel.user.handle) r
                else r.copy(stories = r.stories.map { if (it.id == story.id) it.copy(seen = true) else it })
                    .let { it.copy(allSeen = it.stories.all { s -> s.seen }) }
            }
        }
    }

    fun like(story: Story, liked: Boolean) {
        viewModelScope.launch { runCatching { apiCall { app.api.likeStory(story.id, LikeBody(liked)) } } }
    }

    suspend fun reply(story: Story, text: String): String? = try {
        apiCall { app.api.replyStory(story.id, ReplyBody(text)) }
        null
    } catch (e: ApiException) {
        e.message
    }

    suspend fun viewers(story: Story): List<StoryViewer> = runCatching { apiCall { app.api.storyViewers(story.id) }.items }.getOrDefault(emptyList())

    fun delete(story: Story, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { apiCall { app.api.deleteStory(story.id) } }
            app.storyTray.update { tray ->
                tray.map { r -> r.copy(stories = r.stories.filterNot { it.id == story.id }) }.filter { it.stories.isNotEmpty() }
            }
            onDone()
        }
    }
}

/**
 * Full-screen stories. People are pages you swipe between with a 3D cube turn; within a
 * person, tap right or left to step, hold to pause, swipe down to close. Each story runs
 * five seconds with a progress bar per story at the top. Viewers can like or reply (which
 * arrives as a message); your own stories show who watched and can be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryViewerScreen(
    vm: StoryViewerViewModel,
    onClose: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val lite = LocalLiteMode.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    if (state.loading) return Box(Modifier.fillMaxSize().background(Color.Black)) { LoadingBox() }
    val reels = state.reels
    if (reels.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = vm.start.coerceIn(0, reels.lastIndex)) { reels.size }
    var viewersOf by remember { mutableStateOf<Story?>(null) }
    val drag = remember { Animatable(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (drag.value > 260f) onClose() else drag.animateTo(0f, spring()) } },
                ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
            }
            .graphicsLayer {
                translationY = drag.value
                val s = 1f - (drag.value / 3000f).coerceIn(0f, 0.15f)
                scaleX = s
                scaleY = s
            },
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { reels[it].user.handle }) { page ->
            val reel = reels[page]
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    if (!lite) {
                        // Cube turn: each person's page hinges on the edge it shares with the next.
                        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        transformOrigin = TransformOrigin(if (offset > 0) 1f else 0f, 0.5f)
                        rotationY = -90f * offset.coerceIn(-1f, 1f)
                        cameraDistance = 12f * density
                    }
                },
            ) {
                StoryPage(
                    reel = reel,
                    active = pager.currentPage == page && !pager.isScrollInProgress && viewersOf == null,
                    imageUrl = { app.mediaUrl(it) },
                    signedIn = vm.signedIn,
                    onSeen = { vm.seen(reel, it) },
                    onPrevReel = {
                        if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) }
                    },
                    onNextReel = {
                        if (page < reels.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) } else onClose()
                    },
                    onClose = onClose,
                    onLike = { s, liked -> if (!vm.signedIn) onNeedSignIn() else vm.like(s, liked) },
                    onReply = { s, text ->
                        if (!vm.signedIn) {
                            onNeedSignIn()
                        } else {
                            scope.launch { showMessage(vm.reply(s, text) ?: "Sent to @${reel.user.handle}.") }
                        }
                    },
                    onOpenDish = onOpenDish,
                    onOpenPlace = onOpenPlace,
                    onOpenUser = onOpenUser,
                    onViewers = { viewersOf = it },
                    onDelete = { s -> vm.delete(s) { if (reel.stories.size <= 1) onClose() } },
                )
            }
        }
    }

    viewersOf?.let { s ->
        val viewers = remember { mutableStateListOf<StoryViewer>() }
        var loading by remember { mutableStateOf(true) }
        LaunchedEffect(s.id) {
            viewers.addAll(vm.viewers(s))
            loading = false
        }
        ModalBottomSheet(onDismissRequest = { viewersOf = null }) {
            Text(
                if (viewers.size == 1) "1 view" else "${viewers.size} views",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (loading) LoadingBox(Modifier.height(120.dp))
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(viewers, key = { it.handle }) { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewersOf = null; onOpenUser(v.handle) }.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(v.handle, v.displayName, 40.dp, photo = app.mediaUrl(v.avatarUrl))
                        Text(v.displayName ?: v.handle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        if (v.liked) Icon(Icons.Filled.Favorite, "Liked", tint = Chili)
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryPage(
    reel: StoryReel,
    active: Boolean,
    imageUrl: (String?) -> String?,
    signedIn: Boolean,
    onSeen: (Story) -> Unit,
    onPrevReel: () -> Unit,
    onNextReel: () -> Unit,
    onClose: () -> Unit,
    onLike: (Story, Boolean) -> Unit,
    onReply: (Story, String) -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onViewers: (Story) -> Unit,
    onDelete: (Story) -> Unit,
) {
    val stories = reel.stories
    if (stories.isEmpty()) return
    // Start at the first story you haven't seen.
    var index by remember(reel.user.handle) { mutableIntStateOf(stories.indexOfFirst { !it.seen }.takeIf { it >= 0 } ?: 0) }
    if (index > stories.lastIndex) index = stories.lastIndex
    val story = stories[index]
    val progress = remember(reel.user.handle) { Animatable(0f) }
    var held by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var reply by remember(story.id) { mutableStateOf("") }
    val liked = remember { mutableStateMapOf<String, Boolean>() }

    fun next() {
        if (index < stories.lastIndex) index++ else onNextReel()
    }
    fun prev() {
        if (index > 0) index-- else onPrevReel()
    }

    LaunchedEffect(story.id, active) { if (active) onSeen(story) }
    LaunchedEffect(story.id) { progress.snapTo(0f) }
    LaunchedEffect(story.id, active, held, typing) {
        if (active && !held && !typing) {
            val left = (1f - progress.value) * STORY_MILLIS
            progress.animateTo(1f, tween(left.toInt().coerceAtLeast(1), easing = LinearEasing))
            next()
        }
    }

    Box(Modifier.fillMaxSize().testTag("story_page")) {
        DishImage(
            imageUrl(story.imageUrl), story.placeholderColor, story.caption ?: "Story by ${reel.user.handle}",
            Modifier.fillMaxSize().pointerInput(story.id) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    val start = System.currentTimeMillis()
                    val up = waitForUpOrCancellation()
                    held = false
                    // A quick tap steps; a long press only paused.
                    if (up != null && System.currentTimeMillis() - start < 250) {
                        if (down.position.x < size.width / 3f) prev() else next()
                    }
                }
            },
        )
        // Shade top and bottom so the white text always reads.
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                stories.forEachIndexed { i, _ ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().drawBehind {
                            val r = CornerRadius(size.height / 2)
                            drawRoundRect(Color.White.copy(alpha = 0.35f), cornerRadius = r)
                            val f = when {
                                i < index -> 1f
                                i == index -> progress.value
                                else -> 0f
                            }
                            if (f > 0f) drawRoundRect(Color.White, Offset.Zero, Size(size.width * f, size.height), r)
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onOpenUser(reel.user.handle) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(reel.user.handle, reel.user.displayName, 34.dp, photo = imageUrl(reel.user.avatarUrl))
                    Text(
                        reel.user.handle,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Text(
                        "  " + Format.timeAgo(story.createdAt),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (reel.isMe) {
                    IconButton(onClick = { onDelete(story) }) { Icon(Icons.Filled.Delete, "Delete story", tint = Color.White) }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The tag works like a sticker: tap it to open the dish or place.
            val dish = story.dish
            val place = story.place
            if (dish != null || place != null) {
                Row(
                    Modifier
                        .graphicsLayer { rotationZ = -2.5f }
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .clickable { if (dish != null) onOpenDish(dish.id) else if (place != null) onOpenPlace(place.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Pin, null, tint = Chili, modifier = Modifier.size(18.dp))
                    Text(
                        if (dish != null) listOfNotNull(dish.name, dish.restaurantName).joinToString(" · ") else place!!.name,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            story.caption?.let {
                Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
            }
            if (reel.isMe) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { onViewers(story) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Seen by ${story.viewCount ?: 0}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = reply,
                        onValueChange = { reply = it.take(500) },
                        placeholder = { Text("Reply to ${reel.user.handle}…", color = Color.White.copy(alpha = 0.8f)) },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.6f),
                            cursorColor = Color.White,
                        ),
                        modifier = Modifier.weight(1f).onFocusChanged { typing = it.isFocused },
                    )
                    if (reply.isNotBlank()) {
                        IconButton(onClick = { onReply(story, reply.trim()); reply = "" }) {
                            Icon(AppIcons.Send, "Send", tint = Color.White)
                        }
                    } else {
                        val on = liked[story.id] ?: story.liked
                        val pop = remember(story.id) { Animatable(1f) }
                        val scope = rememberCoroutineScope()
                        IconButton(onClick = {
                            if (!signedIn) {
                                onLike(story, true)
                            } else {
                                liked[story.id] = !on
                                onLike(story, !on)
                                scope.launch { pop.snapTo(0.6f); pop.animateTo(1f, spring(dampingRatio = 0.4f)) }
                            }
                        }) {
                            Icon(
                                if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                if (on) "Unlike" else "Like",
                                tint = if (on) Chili else Color.White,
                                modifier = Modifier.size(30.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Composer

data class StoryComposerUiState(
    val photo: Uri? = null,
    val caption: String = "",
    val tag: DishCard? = null,
    val query: String = "",
    val results: List<DishCard> = emptyList(),
    val posting: Boolean = false,
)

class StoryComposerViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(StoryComposerUiState())
    val state: StateFlow<StoryComposerUiState> = _state.asStateFlow()

    fun setPhoto(uri: Uri?) = _state.update { it.copy(photo = uri) }
    fun setCaption(v: String) = _state.update { it.copy(caption = v.take(200)) }
    fun tag(d: DishCard?) = _state.update { it.copy(tag = d, query = "", results = emptyList()) }

    fun search(q: String) {
        _state.update { it.copy(query = q.take(40)) }
        val city = app.city.value ?: return
        if (q.trim().length < 2) return _state.update { it.copy(results = emptyList()) }
        viewModelScope.launch {
            runCatching { apiCall { app.api.search(q.trim(), city.id, null, null) } }
                .onSuccess { r -> if (_state.value.query == q.take(40)) _state.update { it.copy(results = r.dishes.take(6)) } }
        }
    }

    fun post(context: android.content.Context, onDone: (String?) -> Unit) {
        val s = _state.value
        val photo = s.photo ?: return
        _state.update { it.copy(posting = true) }
        viewModelScope.launch {
            try {
                val jpeg = ImageCompressor.compress(context, photo)
                val fields = buildMap {
                    if (s.caption.isNotBlank()) put("caption", s.caption.trim())
                    s.tag?.let { put("dishId", it.id) }
                }
                app.createStory(fields, jpeg)
                onDone(null)
            } catch (e: ApiException) {
                _state.update { it.copy(posting = false) }
                onDone(e.message)
            } catch (e: Exception) {
                _state.update { it.copy(posting = false) }
                onDone("Couldn't read that photo.")
            }
        }
    }
}

/** Add to your story: take or pick a photo, write a line, tag the dish, share for 24 hours. */
@Composable
fun StoryComposerScreen(vm: StoryComposerViewModel, onClose: () -> Unit, onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setPhoto(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) vm.setPhoto(cameraUri) }
    val openCamera = {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "story-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraUri = uri
        camera.launch(uri)
    }
    LaunchedEffect(session) { if (session == null) onNeedSignIn() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val photo = state.photo
        if (photo == null) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Add to your story", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Text("A photo of what you're eating, for 24 hours.", color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center)
                PrimaryButton("Take a photo", openCamera, Modifier.fillMaxWidth(), leading = AppIcons.Camera)
                QuietButton("Choose from gallery", { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth())
            }
        } else {
            coil3.compose.AsyncImage(
                model = photo,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .navigationBarsPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    cursorColor = Color.White,
                )
                OutlinedTextField(
                    value = state.caption,
                    onValueChange = vm::setCaption,
                    placeholder = { Text("Say something about it…", color = Color.White.copy(alpha = 0.7f)) },
                    colors = fieldColors,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                val tag = state.tag
                if (tag != null) {
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { vm.tag(null) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Pin, null, tint = Chili, modifier = Modifier.size(18.dp))
                        Text("${tag.name} · ${tag.restaurant.name}", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
                        Icon(Icons.Filled.Close, "Remove tag", tint = Color.Black, modifier = Modifier.padding(start = 6.dp).size(16.dp))
                    }
                } else {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::search,
                        placeholder = { Text("Tag the dish (optional)", color = Color.White.copy(alpha = 0.7f)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Color.White) },
                        singleLine = true,
                        colors = fieldColors,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.results.forEach { d ->
                        Text(
                            "${d.name} · ${d.restaurant.name}",
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.tag(d) }.padding(vertical = 8.dp, horizontal = 6.dp),
                        )
                    }
                }
                PrimaryButton(
                    if (state.posting) "Sharing…" else "Share to your story",
                    {
                        vm.post(context) { err ->
                            if (err == null) {
                                showMessage("Added to your story.")
                                onClose()
                            } else {
                                showMessage(err)
                            }
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = !state.posting,
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Filled.Close, "Close", tint = Color.White)
        }
    }
}
