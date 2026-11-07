package app.worthabite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.worthabite.data.DishCard

/**
 * Tags people give a dish (the server keeps the list: jain, healthy, spicy, mild). A tag shows on
 * a dish once someone the ranking trusts gave it; spicy and mild exclude each other.
 */
object DishTags {
    val all = listOf("jain", "healthy", "spicy", "mild")

    fun label(tag: String) = when (tag) {
        "jain" -> "Jain · no onion/garlic"
        "healthy" -> "Healthy"
        "spicy" -> "Spicy 🌶"
        "mild" -> "Mild"
        else -> tag
    }

    /** Adds or removes [tag]; picking spicy drops mild and the other way round. */
    fun toggle(current: Set<String>, tag: String): Set<String> = when {
        tag in current -> current - tag
        tag == "spicy" -> current - "mild" + tag
        tag == "mild" -> current - "spicy" + tag
        else -> current + tag
    }
}

/** Chips to pick tags (posting a dish, or tagging one you tried). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DishTags.all.forEach { tag ->
            FilterChip(
                selected = tag in selected,
                onClick = { onToggle(tag) },
                label = { Text(DishTags.label(tag)) },
                modifier = Modifier.testTag("tag_$tag"),
            )
        }
    }
}

/** A dish's tags as small read-only pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPills(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { tag ->
            Text(
                DishTags.label(tag),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

/** Filters on a list of dishes (search and craving results): all from data the cards carry. */
data class DishFilter(
    val vegOnly: Boolean = false,
    val maxPrice: Int? = null,
    val nearest: Boolean = false,
    val tag: String? = null,
) {
    val active get() = vegOnly || maxPrice != null || nearest || tag != null

    fun apply(dishes: List<DishCard>): List<DishCard> {
        val kept = dishes.filter { d ->
            (!vegOnly || d.isVeg == true) &&
                (maxPrice == null || (d.priceInr != null && d.priceInr <= maxPrice)) &&
                (tag == null || tag in d.tags)
        }
        return if (nearest) kept.sortedBy { it.distanceM ?: Int.MAX_VALUE } else kept
    }
}

/** One row of filter chips: veg, budget, nearest, and the tags. */
@Composable
fun DishFilterChips(filter: DishFilter, onChange: (DishFilter) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier.testTag("dish_filters"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { FilterChip(filter.vegOnly, { onChange(filter.copy(vegOnly = !filter.vegOnly)) }, { Text("Veg only") }) }
        items(listOf(100, 200, 500)) { p ->
            FilterChip(filter.maxPrice == p, { onChange(filter.copy(maxPrice = if (filter.maxPrice == p) null else p)) }, { Text("Under ₹$p") })
        }
        item { FilterChip(filter.nearest, { onChange(filter.copy(nearest = !filter.nearest)) }, { Text("Nearest") }) }
        items(DishTags.all) { t ->
            FilterChip(filter.tag == t, { onChange(filter.copy(tag = if (filter.tag == t) null else t)) }, { Text(DishTags.label(t)) })
        }
    }
}
