package app.foodfeed.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foodfeed.data.Category
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.AvatarStack
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.EngageAction
import app.foodfeed.ui.components.Tag
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.theme.CardTitle
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.pressScale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** City on the left, search and filter on the right. Nothing else. */
@Composable
fun FeedHeader(cityName: String, filterActive: Boolean, onCityClick: () -> Unit, onSearch: () -> Unit, onFilter: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .weight(1f)
                .pressScale(interaction, 0.97f)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onCityClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(cityName, style = MaterialTheme.typography.headlineSmall)
            Icon(
                Icons.Filled.KeyboardArrowDown, "Change city",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 3.dp).size(22.dp),
            )
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search dishes and places") }
        IconButton(onClick = onFilter) {
            Box {
                Icon(AppIcons.Tune, "Filter by dish type")
                if (filterActive) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp).size(7.dp)
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * Text tabs with one ink underline that glides between them on a spring and stretches to
 * each label's width. Labels fade between ink and muted.
 */
@Composable
fun FeedTabs(selected: FeedTab, onSelect: (FeedTab) -> Unit) {
    val density = LocalDensity.current
    val positions = remember { mutableStateMapOf<FeedTab, Pair<Float, Float>>() } // x, width in px
    val target = positions[selected]
    val x by animateDpAsState(with(density) { (target?.first ?: 0f).toDp() }, Motion.glide(), label = "tab-x")
    val w by animateDpAsState(with(density) { (target?.second ?: 0f).toDp() }, Motion.glide(), label = "tab-w")
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            FeedTab.entries.forEach { tab ->
                val color by animateColorAsState(
                    if (tab == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    Motion.enter(Motion.SHORT),
                    label = "tab-color",
                )
                Text(
                    tab.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    modifier = Modifier
                        .onGloballyPositioned { positions[tab] = it.positionInParent().x to it.size.width.toFloat() }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = 6.dp),
                )
            }
        }
        if (target != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = x)
                    .width(w)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** The active dish-type filter as a small dismissible chip; slides in and out horizontally. */
@Composable
fun ActiveFilterChip(category: Category?, onClear: () -> Unit) {
    AnimatedVisibility(
        visible = category != null,
        enter = fadeIn(Motion.enter()) + expandHorizontally(Motion.enter()),
        exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.exit()),
    ) {
        Row(
            Modifier
                .padding(start = 20.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurface)
                .clickable(onClick = onClear)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(category?.name.orEmpty(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.surface)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.Close, "Clear filter", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategorySheet(categories: List<Category>, selected: Int?, onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text("Dish type", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterOption("All", selected == null) { onSelect(null) }
                categories.forEach { c -> FilterOption(c.name, selected == c.id) { onSelect(c.id) } }
            }
        }
    }
}

@Composable
private fun FilterOption(label: String, on: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
        Motion.enter(Motion.SHORT), label = "opt-bg",
    )
    val fg by animateColorAsState(
        if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        Motion.enter(Motion.SHORT), label = "opt-fg",
    )
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = bg,
        contentColor = fg,
        interactionSource = interaction,
        modifier = Modifier.pressScale(interaction, 0.95f),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/**
 * One full-height dish card.
 *
 * Motion while paging (all read inside graphicsLayer, so scrolling never recomposes):
 *  - the photo moves at ~78% of the card's speed (parallax) and is overscaled so edges never show;
 *  - the card eases down to 94% scale and dims as it leaves, so the next one feels like it rises;
 *  - the text block drifts and fades a little faster than the card, which separates the layers.
 * Double-tap anywhere recommends, with a heart that springs in, holds, and fades.
 */
@Composable
fun DishPage(
    card: DishCard,
    engagement: EngagementState,
    imageUrl: String?,
    onOpen: () -> Unit,
    onRestaurant: () -> Unit,
    onToggle: (Engagement) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    pageOffset: () -> Float = { 0f },
) {
    var burst by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                val o = abs(pageOffset()).coerceAtMost(1f)
                val s = 1f - 0.06f * o
                scaleX = s
                scaleY = s
                alpha = 1f - 0.35f * o
            }
            .clip(RoundedCornerShape(26.dp))
            .pointerInput(card.id, engagement.me.recommended) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onDoubleTap = {
                        burst++
                        if (!engagement.me.recommended) onToggle(Engagement.Recommend)
                    },
                )
            },
    ) {
        DishImage(
            imageUrl, card.cover?.placeholderColor, card.name,
            Modifier.fillMaxSize().graphicsLayer {
                translationY = pageOffset() * size.height * 0.22f
                scaleX = 1.14f
                scaleY = 1.14f
            },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.18f),
                    0.18f to Color.Transparent,
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.72f),
                ),
            ),
        )

        val tag = when {
            card.lane == "gem" -> "Hidden gem"
            card.lane == "fresh" && engagement.stats.recommenders < 3 -> "New"
            card.cover?.isSponsored == true -> "Sponsored"
            else -> null
        }
        if (tag != null) Tag(tag, onPhoto = true, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 80.dp, bottom = 22.dp)
                .graphicsLayer {
                    val o = abs(pageOffset()).coerceAtMost(1f)
                    translationY = o * 90f
                    alpha = (1f - o * 1.8f).coerceIn(0f, 1f)
                },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(card.name, color = Color.White, style = CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(card.restaurant.name, card.restaurant.locality).joinToString(", "),
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onRestaurant),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VegMark(card.isVeg, Modifier.background(Color.White, RoundedCornerShape(2.dp)))
                Text(
                    listOfNotNull(Format.price(card.priceInr), Format.distance(card.distanceM)).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val people = card.recentRecommenders
                if (people.isNotEmpty() && engagement.stats.recommenders > 0) {
                    AvatarStack(people, ringColor = Color.Black.copy(alpha = 0.6f))
                    Spacer(Modifier.width((8 + 13 * people.size.coerceAtMost(3)).dp))
                }
                Text(
                    Format.recommended(engagement.stats.recommenders),
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 18.dp)
                .graphicsLayer {
                    val o = abs(pageOffset()).coerceAtMost(1f)
                    translationY = o * 140f
                    alpha = (1f - o * 2f).coerceIn(0f, 1f)
                },
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EngageAction(
                Engagement.Recommend, engagement.me.recommended, "Love", engagement.stats.recommenders,
                if (engagement.me.recommended) "Remove recommendation" else "Recommend",
                { onToggle(Engagement.Recommend) }, onPhoto = true,
            )
            EngageAction(
                Engagement.Save, engagement.me.saved, if (engagement.me.saved) "Saved" else "Save", null,
                if (engagement.me.saved) "Remove from saved" else "Save", { onToggle(Engagement.Save) }, onPhoto = true,
            )
            EngageAction(
                Engagement.Tried, engagement.me.tried, "Tried", null,
                if (engagement.me.tried) "Unmark tried" else "I tried this", { onToggle(Engagement.Tried) }, onPhoto = true,
            )
            EngageAction(null, false, "Share", null, "Share", onShare, onPhoto = true)
        }

        HeartBurst(burst, Modifier.align(Alignment.Center))
    }
}

/**
 * The double-tap heart: springs from 40% to full size with a soft overshoot and a slight
 * tilt, holds for a beat, then drifts up while fading. Restarts cleanly on rapid taps.
 */
@Composable
private fun HeartBurst(trigger: Int, modifier: Modifier) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        rise.snapTo(0f)
        scale.snapTo(0.4f)
        launch { scale.animateTo(1f, Motion.pop()) }
        delay(520)
        launch { rise.animateTo(-60f, Motion.exit(260)) }
        alpha.animateTo(0f, Motion.exit(260))
    }
    if (alpha.value > 0.01f) {
        Icon(
            Icons.Filled.Favorite, null, tint = Color.White,
            modifier = modifier.size(104.dp).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                translationY = rise.value
                rotationZ = -8f * (1f - scale.value.coerceIn(0f, 1f)) - 6f
            },
        )
    }
}
