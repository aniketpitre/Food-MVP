package app.foodfeed.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.Format
import app.foodfeed.ui.theme.Turmeric
import app.foodfeed.ui.theme.accent
import app.foodfeed.ui.theme.dishHero
import app.foodfeed.ui.theme.pressScale

/**
 * A dish as a menu card: photo on the left (with a rank sticker when ranked), the name set
 * big, the place underneath, and a price tag + chili heart count along the bottom. The
 * whole card presses down; the save mark pops and bursts on its own.
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
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            DishImage(
                thumbUrl, card.cover?.placeholderColor, card.name,
                Modifier.size(92.dp).dishHero(card.id, 18.dp).clip(RoundedCornerShape(18.dp)),
            )
            if (rank != null) {
                Sticker(
                    "#$rank", if (rank == 1) Turmeric else MaterialTheme.colorScheme.surfaceContainerLowest,
                    content = if (rank == 1) app.foodfeed.ui.theme.Ink else MaterialTheme.colorScheme.onSurface,
                    tilt = -8f,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VegMark(card.isVeg)
                if (card.isVeg != null) Spacer(Modifier.width(7.dp))
                Text(card.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Format.price(card.priceInr)?.let {
                    PriceTag(it, tilt = -2f)
                    Spacer(Modifier.width(10.dp))
                }
                val recs = engagement.stats.recommenders
                if (recs > 0) {
                    Icon(Icons.Filled.Favorite, null, tint = accent(), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        Format.compact(recs), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Tag("New")
                }
            }
        }
        EngageAction(
            Engagement.Save, engagement.me.saved, "", null,
            if (engagement.me.saved) "Remove from saved" else "Save",
            onSave, look = ActionLook.Bare, iconSize = 24.dp,
        )
    }
}
