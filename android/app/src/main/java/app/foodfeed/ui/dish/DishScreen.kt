package app.foodfeed.ui.dish

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.Pill
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.components.SectionTitle
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.components.rememberDishActions
import app.foodfeed.ui.theme.Saffron
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
    val scope = rememberCoroutineScope()

    when {
        state.loading -> LoadingBox()
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

    reportTarget?.let { (type, id) ->
        ReportSheet(targetType = type, targetId = id, onDismiss = { reportTarget = null }, showMessage = showMessage)
    }
}

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
) {
    val photos = dish.posts
    val pager = rememberPagerState { photos.size.coerceAtLeast(1) }
    val currentPost = photos.getOrNull(pager.currentPage)
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f)) {
            if (photos.isEmpty()) {
                DishImage(null, dish.cover?.placeholderColor, dish.name, Modifier.fillMaxSize())
            } else {
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { i ->
                    DishImage(imageUrl(photos[i].imageUrl), photos[i].placeholderColor, dish.name, Modifier.fillMaxSize())
                }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Box {
                    RoundIcon(Icons.Filled.MoreVert, "More options") { menu = true }
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
            if (photos.size > 1) {
                Pill(
                    "${pager.currentPage + 1}/${photos.size}", Color.Black.copy(alpha = 0.55f), Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                )
            }
            if (currentPost?.isSponsored == true) {
                Pill("Sponsored", Color.Black.copy(alpha = 0.6f), Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
            }
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VegMark(dish.isVeg)
                Text(
                    dish.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Format.price(dish.priceInr)?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            }

            Surface(
                onClick = onOpenRestaurant,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Restaurant, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dish.restaurant.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(dish.restaurant.locality, Format.distance(dish.distanceM)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open place")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.recommended(engagement.stats.recommenders), style = MaterialTheme.typography.titleSmall)
                if (engagement.stats.tried > 0) {
                    Text(
                        " · tried by ${engagement.stats.tried}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (engagement.stats.saves > 0) {
                    Text(
                        " · ${engagement.stats.saves} saved", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (dish.recommenders.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(dish.recommenders, key = { it.handle }) { u ->
                        Column(
                            Modifier.width(56.dp).clip(RoundedCornerShape(8.dp)).clickable { onOpenUser(u.handle) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(u.handle, u.displayName, 44.dp)
                            Text("@${u.handle}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val rec = engagement.me.recommended
                Button(
                    onClick = { onToggle(Engagement.Recommend) },
                    modifier = Modifier.weight(1f),
                    colors = if (rec) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    else ButtonDefaults.buttonColors(),
                ) {
                    Icon(if (rec) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (rec) "Recommended" else "Recommend")
                }
                OutlinedButton(onClick = { onToggle(Engagement.Save) }, modifier = Modifier.weight(1f)) {
                    Icon(if (engagement.me.saved) AppIcons.Bookmark else AppIcons.BookmarkBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (engagement.me.saved) "Saved" else "Save")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onToggle(Engagement.Tried) }, modifier = Modifier.weight(1f)) {
                    Icon(if (engagement.me.tried) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (engagement.me.tried) "You tried it" else "I tried this")
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
            OutlinedButton(onClick = onMaps, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Directions, null, Modifier.size(18.dp), tint = Saffron)
                Spacer(Modifier.width(6.dp))
                Text("Open in Maps")
            }

            currentPost?.let { post ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.Top) {
                    val handle = post.authorHandle
                    if (handle != null) {
                        Avatar(handle, post.authorName, 36.dp, Modifier.clickable { onOpenUser(handle) })
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                handle != null -> "@$handle"
                                post.photoCredit != null -> "Photo: ${post.photoCredit}"
                                else -> "FoodFeed"
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                        post.caption?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (post.isSponsored) {
                            Text(
                                "Sponsored" + (post.sponsorNote?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            Text(
                "Veg/non-veg and allergen details come from users and aren't verified. Check with the restaurant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (dish.moreAtPlace.isNotEmpty()) {
            SectionTitle("More at ${dish.restaurant.name}")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(dish.moreAtPlace, key = { it.id }) { d ->
                    MiniDishTile(d, imageUrl(d.cover?.thumbUrl)) { onOpenDish(d.id) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MiniDishTile(card: DishCard, thumbUrl: String?, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        DishImage(thumbUrl, card.cover?.placeholderColor, card.name, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            VegMark(card.isVeg, size = 12.dp)
            Spacer(Modifier.width(4.dp))
            Text(card.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (card.stats.recommenders > 0) "♥ ${card.stats.recommenders}" else "New",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
    ) { Icon(icon, description) }
}
