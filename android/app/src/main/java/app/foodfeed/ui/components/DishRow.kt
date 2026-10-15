package app.foodfeed.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.ui.Format
import app.foodfeed.ui.theme.pressScale

/**
 * A quiet list row: optional rank numeral, text on the left, a square photo on the right,
 * and a save mark that pops when tapped. The whole row presses down slightly.
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
            .pressScale(interaction, 0.985f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                rank.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(34.dp),
            )
        }
        Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VegMark(card.isVeg)
                if (card.isVeg != null) Spacer(Modifier.width(8.dp))
                Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val recs = engagement.stats.recommenders
            Text(
                listOfNotNull(Format.price(card.priceInr), if (recs > 0) "$recs recommend" else "New").joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        DishImage(thumbUrl, card.cover?.placeholderColor, card.name, Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)))
        EngageAction(
            Engagement.Save, engagement.me.saved, "", null,
            if (engagement.me.saved) "Remove from saved" else "Save",
            onSave, iconSize = 22.dp,
        )
    }
}
