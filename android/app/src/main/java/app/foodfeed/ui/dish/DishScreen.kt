package app.foodfeed.ui.dish

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.PriceTag
import app.foodfeed.ui.theme.Saffron
import app.foodfeed.ui.theme.accent
import app.foodfeed.ui.theme.dishHero
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.BlockBody
import app.foodfeed.data.DishCard
import app.foodfeed.data.DishDetail
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.data.Post
import androidx.compose.runtime.saveable.rememberSaveable
import app.foodfeed.ui.comments.CommentsSheet
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.AvatarStack
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.EngageAction
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.components.Reveal
import app.foodfeed.ui.components.Tag
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.components.rememberDishActions
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.SkeletonBlock
import app.foodfeed.ui.theme.pressScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DishUiState(val loading: Boolean = true, val dish: DishDetail? = null, val error: String? = null)

class DishViewModel(private val app: AppContainer, private val dishId: String) : ViewModel() {
    private val _state = MutableStateFlow(DishUiState())
    val state: StateFlow<DishUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            var first = true
            app.contentVersion.collect { if (!first) load() else first = false }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.dish == null, error = null)
            try {
                val loc = app.location.value
                val dish = apiCall { app.api.dish(dishId, loc?.lat, loc?.lng) }
                _state.value = DishUiState(loading = false, dish = dish)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}

@Composable
fun DishScreen(
    vm: DishViewModel,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val session by app.session.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)
    var reportTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var commentsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val commentCounts by app.commentCounts.collectAsState()
    val scope = rememberCoroutineScope()

    when {
        state.loading -> DishSkeleton()
        state.dish == null -> CenteredMessage(
            "Couldn't open this dish", state.error, Icons.Filled.Refresh, "Try again", vm::load, "Go back", onBack,
        )
        else -> {
            val dish = state.dish!!
            val card = dish.toCard()
            DishContent(
                dish = dish,
                engagement = overrides[dish.id] ?: EngagementState(dish.stats, dish.me),
                imageUrl = { app.mediaUrl(it) },
                onBack = onBack,
                onToggle = { actions.toggle(card, it) },
                onShare = { actions.share(card) },
                onMaps = { actions.openMaps(dish.restaurant.lat, dish.restaurant.lng, dish.restaurant.name) },
                onOpenRestaurant = { onOpenRestaurant(dish.restaurant.id) },
                onOpenDish = onOpenDish,
                onOpenUser = onOpenUser,
                onReport = { type, id -> if (session == null) onNeedSignIn() else reportTarget = type to id },
                commentCountOf = { post -> commentCounts[post.id] ?: post.commentCount },
                onComments = { commentsFor = it },
                onBlock = { handle ->
                    if (session == null) onNeedSignIn()
                    else scope.launch {
                        runCatching { apiCall { app.api.block(BlockBody(handle)) } }
                            .onSuccess { showMessage("You won't see @$handle's photos any more."); vm.load() }
                            .onFailure { showMessage(it.message ?: "Couldn't block.") }
                    }
                },
            )
        }
    }

    commentsFor?.let { post ->
        CommentsSheet(post, onDismiss = { commentsFor = null }, onNeedSignIn = onNeedSignIn, onOpenUser = onOpenUser, showMessage = showMessage)
    }
    reportTarget?.let { (type, id) ->
        ReportSheet(targetType = type, targetId = id, onDismiss = { reportTarget = null }, showMessage = showMessage)
    }
}

@Composable
private fun DishSkeleton() {
    Column(Modifier.fillMaxSize()) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(4f / 5f), RoundedCornerShape(0.dp))
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBlock(Modifier.width(220.dp).height(26.dp), RoundedCornerShape(6.dp))
            SkeletonBlock(Modifier.width(160.dp).height(14.dp), RoundedCornerShape(4.dp))
        }
    }
}

/**
 * Dish page. The photo sits behind the content and scrolls at under half speed (parallax)
 * while dimming; the content sheet slides over it with rounded shoulders. Once the photo is
 * mostly gone, a slim title bar fades in so you never lose the dish name.
 */
@Composable
fun DishContent(
    dish: DishDetail,
    engagement: EngagementState,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
    onToggle: (Engagement) -> Unit,
    onShare: () -> Unit,
    onMaps: () -> Unit,
    onOpenRestaurant: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onReport: (type: String, id: String) -> Unit,
    onBlock: (String) -> Unit,
    commentCountOf: (Post) -> Int = { it.commentCount },
    onComments: (postId: String) -> Unit = {},
) {
    val photos = dish.posts
    val pager = rememberPagerState { photos.size.coerceAtLeast(1) }
    val currentPost = photos.getOrNull(pager.currentPage)
    val scroll = rememberScrollState()
    var menu by remember { mutableStateOf(false) }
    var photoHeight by remember { mutableStateOf(1) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 5f)
                    .onSizeChanged { photoHeight = it.height.coerceAtLeast(1) }
                    .dishHero(dish.id)
                    .graphicsLayer {
                        translationY = scroll.value * 0.55f
                        alpha = 1f - (scroll.value / photoHeight.toFloat()).coerceIn(0f, 1f) * 0.5f
                    },
            ) {
                if (photos.isEmpty()) {
                    DishImage(null, dish.cover?.placeholderColor, dish.name, Modifier.fillMaxSize())
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { i ->
                        DishImage(imageUrl(photos[i].imageUrl), photos[i].placeholderColor, dish.name, Modifier.fillMaxSize())
                    }
                }
                if (photos.size > 1) {
                    PageDots(photos.size, pager.currentPage, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp))
                }
            }

            // The content sheet overlaps the photo by 32dp with rounded shoulders and a grabber.
            Column(
                Modifier
                    .offset(y = (-32).dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(top = 12.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp)
                        .background(MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.height(16.dp))
                Reveal(0) {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (currentPost?.isSponsored == true) Tag("Sponsored")
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                dish.name, style = MaterialTheme.typography.displaySmall,
                                modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            )
                            Format.price(dish.priceInr)?.let {
                                PriceTag(it, Modifier.padding(start = 12.dp, top = 8.dp), tilt = 4f)
                            }
                        }
                        val placeInteraction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .pressScale(placeInteraction, 0.98f)
                                .clickable(placeInteraction, indication = null, role = Role.Button, onClick = onOpenRestaurant),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VegMark(dish.isVeg)
                            if (dish.isVeg != null) Spacer(Modifier.width(8.dp))
                            Text(
                                listOfNotNull(dish.restaurant.name, dish.restaurant.locality, Format.distance(dish.distanceM))
                                    .joinToString("  ·  "),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open place",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Reveal(70) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (dish.recommenders.isNotEmpty() && engagement.stats.recommenders > 0) {
                            AvatarStack(dish.recommenders, ringColor = MaterialTheme.colorScheme.background, size = 22.dp)
                            Spacer(Modifier.width((10 + 15 * dish.recommenders.size.coerceAtMost(3)).dp))
                        }
                        Text(
                            buildAnnotatedString {
                                val n = engagement.stats.recommenders
                                if (n > 0) {
                                    append("Recommended by ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent())) {
                                        append(if (n == 1) "1 person" else "${Format.compact(n)} people")
                                    }
                                } else {
                                    append(Format.recommended(0))
                                }
                                if (engagement.stats.tried > 0) append("  ·  tried by ${engagement.stats.tried}")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Reveal(140) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            EngageAction(
                                Engagement.Recommend, engagement.me.recommended,
                                if (engagement.me.recommended) "Loved" else "Recommend", null,
                                if (engagement.me.recommended) "Remove recommendation" else "Recommend",
                                { onToggle(Engagement.Recommend) },
                            )
                            currentPost?.let { post ->
                                EngageAction(
                                    null, false, "Comment", commentCountOf(post), "Comments", { onComments(post.id) },
                                    icon = AppIcons.Comment,
                                )
                            }
                            EngageAction(
                                Engagement.Save, engagement.me.saved, if (engagement.me.saved) "Saved" else "Save", null,
                                if (engagement.me.saved) "Remove from saved" else "Save", { onToggle(Engagement.Save) },
                            )
                            EngageAction(
                                Engagement.Tried, engagement.me.tried, "Tried", null,
                                if (engagement.me.tried) "Unmark tried" else "I tried this", { onToggle(Engagement.Tried) },
                            )
                            EngageAction(null, false, "Share", null, "Share", onShare)
                        }
                    }
                }

                Reveal(210) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PrimaryButton("Take me there", onMaps, Modifier.fillMaxWidth(), leading = AppIcons.Directions)
                        currentPost?.let { post ->
                            Row(verticalAlignment = Alignment.Top) {
                                val handle = post.authorHandle
                                if (handle != null) {
                                    Avatar(handle, post.authorName, 32.dp, Modifier.clickable { onOpenUser(handle) })
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainer,
                                            RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                                        )
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        when {
                                            handle != null -> "@$handle"
                                            post.photoCredit != null -> post.photoCredit
                                            else -> "FoodFeed"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    post.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                                    if (post.isSponsored && post.sponsorNote != null) {
                                        Text(post.sponsorNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        currentPost?.let { post ->
                            val n = commentCountOf(post)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onComments(post.id) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(AppIcons.Comment, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    when (n) {
                                        0 -> "Add a comment…"
                                        1 -> "View 1 comment"
                                        else -> "View all $n comments"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "Veg and allergen details come from people, not the restaurant. Check before you order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (dish.moreAtPlace.isNotEmpty()) {
                    Reveal(280) {
                        Column {
                            KickerText("More at", Modifier.padding(start = 20.dp, top = 8.dp))
                            Text(
                                dish.restaurant.name,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            )
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(dish.moreAtPlace, key = { it.id }) { d ->
                                    MiniDishTile(d, imageUrl(d.cover?.thumbUrl)) { onOpenDish(d.id) }
                                }
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    }
                }
            }
        }

        // Slim title bar that fades in once the photo has scrolled away.
        val barAlpha = ((scroll.value - photoHeight * 0.7f) / (photoHeight * 0.2f)).coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = barAlpha }
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .height(56.dp),
        ) {
            Text(
                dish.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GlassIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", barAlpha, onBack)
            Box {
                GlassIcon(Icons.Filled.MoreVert, "More options", barAlpha) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (currentPost != null) {
                        DropdownMenuItem(
                            text = { Text("Report this photo") },
                            leadingIcon = { Icon(AppIcons.Flag, null) },
                            onClick = { menu = false; onReport("post", currentPost.id) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Report this dish") },
                        leadingIcon = { Icon(AppIcons.Flag, null) },
                        onClick = { menu = false; onReport("dish", dish.id) },
                    )
                    currentPost?.authorHandle?.let { handle ->
                        DropdownMenuItem(
                            text = { Text("Block @$handle") },
                            leadingIcon = { Icon(AppIcons.Block, null) },
                            onClick = { menu = false; onBlock(handle) },
                        )
                    }
                }
            }
        }
    }
}

/** Photo position dots: the current one stretches into a short bar on a spring. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == current) 16.dp else 6.dp, Motion.glide(), label = "dot")
            Box(
                Modifier.size(width = w, height = 6.dp)
                    .background(if (i == current) Saffron else Color.White.copy(alpha = 0.6f), CircleShape),
            )
        }
    }
}

/** Back / menu buttons: dark glass over the photo, turning into plain icons over the title bar. */
@Composable
private fun GlassIcon(icon: ImageVector, description: String, solid: Float, onClick: () -> Unit) {
    val glass = Color.Black.copy(alpha = 0.32f * (1f - solid))
    val tint = androidx.compose.ui.graphics.lerp(Color.White, MaterialTheme.colorScheme.onSurface, solid)
    IconButton(onClick = onClick, colors = IconButtonDefaults.iconButtonColors(containerColor = glass, contentColor = tint)) {
        Icon(icon, description)
    }
}

@Composable
fun MiniDishTile(card: DishCard, thumbUrl: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .width(140.dp)
            .pressScale(interaction, 0.96f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        DishImage(
            thumbUrl, card.cover?.placeholderColor, card.name,
            Modifier.fillMaxWidth().aspectRatio(0.85f).dishHero(card.id, 20.dp).clip(RoundedCornerShape(20.dp)),
        )
        Text(
            card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (card.stats.recommenders > 0) "${card.stats.recommenders} recommend" else "New",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
