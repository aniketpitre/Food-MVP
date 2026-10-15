package app.foodfeed.ui.feed

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.data.Category
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.AvatarStack
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.Pill
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.theme.CardTitle
import app.foodfeed.ui.theme.GemPurple
import app.foodfeed.ui.theme.Saffron
import app.foodfeed.ui.theme.StoryRing
import kotlinx.coroutines.delay

/** Swiggy-style location header: pin, city, chevron; search on the right. */
@Composable
fun FeedHeader(cityName: String, onCityClick: () -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onCityClick).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(4.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cityName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Icon(Icons.Filled.KeyboardArrowDown, "Change city")
                }
                Text(
                    "Dishes people here recommend",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search dishes and places") }
    }
}

/** Instagram-stories-style category bubbles. */
@Composable
fun CategoryStories(categories: List<Category>, selected: Int?, onSelect: (Int?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        item(key = "all") { StoryBubble("All", "🍽️", selected == null) { onSelect(null) } }
        items(categories, key = { it.id }) { c ->
            StoryBubble(c.name, c.emoji ?: "🍴", selected == c.id) { onSelect(if (selected == c.id) null else c.id) }
        }
    }
}

@Composable
private fun StoryBubble(label: String, emoji: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .border(
                    width = if (selected) 3.dp else 1.5.dp,
                    brush = if (selected) StoryRing else SolidColor(MaterialTheme.colorScheme.outline),
                    shape = CircleShape,
                )
                .padding(5.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 24.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun FeedTabs(selected: FeedTab, onSelect: (FeedTab) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        items(FeedTab.entries) { tab ->
            val on = tab == selected
            val icon = when (tab) {
                FeedTab.Top -> AppIcons.Fire
                FeedTab.Trending -> AppIcons.Trending
                FeedTab.Gems -> AppIcons.Gem
                FeedTab.Nearby -> AppIcons.Near
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tab.label, color = fg, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * One full-height dish card, Reels style: photo, info at the bottom left, actions on the right.
 * Double-tap the photo to recommend, like a double-tap like on Instagram.
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
) {
    var burst by remember { mutableIntStateOf(0) }
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
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
        DishImage(imageUrl, card.cover?.placeholderColor, card.name, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.25f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            ),
        )

        Row(Modifier.align(Alignment.TopStart).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (card.lane) {
                "gem" -> Pill("Hidden gem", GemPurple, Color.White, AppIcons.Gem)
                "trending" -> Pill("Trending", Saffron, Color.White, AppIcons.Trending)
                "fresh" -> if (engagement.stats.recommenders < 3) Pill("New", Color.White, Color.Black)
            }
            if (card.cover?.isSponsored == true) Pill("Sponsored", Color.Black.copy(alpha = 0.6f), Color.White)
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 84.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VegMark(card.isVeg)
                Format.price(card.priceInr)?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
            Text(card.name, color = Color.White, style = CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRestaurant).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Restaurant, null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    Format.placeLine(card.restaurant.name, card.restaurant.locality, card.distanceM),
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.recentRecommenders.isNotEmpty() && engagement.stats.recommenders > 0) {
                    AvatarStack(card.recentRecommenders, ringColor = Color.Black)
                    Spacer(Modifier.width((10 + 14 * card.recentRecommenders.size.coerceAtMost(3)).dp))
                }
                Text(
                    Format.recommended(engagement.stats.recommenders),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailButton(
                icon = if (engagement.me.recommended) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = if (engagement.stats.recommenders > 0) Format.compact(engagement.stats.recommenders) else "Recommend",
                active = engagement.me.recommended,
                description = if (engagement.me.recommended) "Remove recommendation" else "Recommend",
            ) { onToggle(Engagement.Recommend) }
            RailButton(
                icon = if (engagement.me.saved) AppIcons.Bookmark else AppIcons.BookmarkBorder,
                label = if (engagement.me.saved) "Saved" else "Save",
                active = engagement.me.saved,
                description = if (engagement.me.saved) "Remove from saved" else "Save",
            ) { onToggle(Engagement.Save) }
            RailButton(
                icon = if (engagement.me.tried) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                label = "Tried",
                active = engagement.me.tried,
                description = if (engagement.me.tried) "Unmark tried" else "I tried this",
            ) { onToggle(Engagement.Tried) }
            RailButton(Icons.Filled.Share, "Share", false, "Share") { onShare() }
        }

        HeartBurst(burst, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, active: Boolean, description: String, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        if (active) 1.12f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rail",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, description,
                tint = if (active) Saffron else Color.White,
                modifier = Modifier.size(28.dp).scale(scale),
            )
        }
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun HeartBurst(trigger: Int, modifier: Modifier) {
    var visible by remember { mutableIntStateOf(0) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            visible = trigger
            delay(650)
            visible = 0
        }
    }
    val scale by animateFloatAsState(
        if (visible > 0) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "burst",
    )
    if (scale > 0.01f) {
        Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = modifier.size(110.dp).scale(scale))
    }
}
