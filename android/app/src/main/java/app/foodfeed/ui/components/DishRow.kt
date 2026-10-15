package app.foodfeed.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foodfeed.data.DishCard
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.Format
import app.foodfeed.ui.theme.Saffron

/**
 * A menu-style row (as on food delivery apps): details on the left, photo on the right
 * with a Save button sitting on its bottom edge.
 */
@Composable
fun DishRow(
    card: DishCard,
    engagement: EngagementState,
    thumbUrl: String?,
    onClick: () -> Unit,
    onSave: () -> Unit,
    showPlace: Boolean = true,
    rank: Int? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VegMark(card.isVeg, size = 14.dp)
                if (rank != null && rank <= 3 && engagement.stats.recommenders > 0) {
                    Text("#$rank here", style = MaterialTheme.typography.labelMedium, color = Saffron, fontWeight = FontWeight.Bold)
                }
            }
            Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Format.price(card.priceInr)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (engagement.stats.recommenders > 0) {
                    Icon(Icons.Filled.Favorite, null, tint = Saffron, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${engagement.stats.recommenders} recommend",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("New", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showPlace) {
                Text(
                    Format.placeLine(card.restaurant.name, card.restaurant.locality, card.distanceM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.width(118.dp), contentAlignment = Alignment.TopCenter) {
            DishImage(
                thumbUrl, card.cover?.placeholderColor, card.name,
                Modifier.size(118.dp).clip(RoundedCornerShape(16.dp)),
            )
            Surface(
                onClick = onSave,
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .padding(top = 100.dp)
                    .width(92.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            ) {
                Row(
                    Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (engagement.me.saved) AppIcons.Bookmark else AppIcons.BookmarkBorder, null,
                        tint = Saffron, modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (engagement.me.saved) "SAVED" else "SAVE",
                        color = Saffron,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
    }
}
