package app.foodfeed.ui.restaurant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.foodfeed.ui.components.DashedLine
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.TicketShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.data.RestaurantDetail
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishRow
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.components.Reveal
import app.foodfeed.ui.components.Tag
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.rememberDishActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RestaurantUiState(val loading: Boolean = true, val place: RestaurantDetail? = null, val error: String? = null)

class RestaurantViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(RestaurantUiState())
    val state: StateFlow<RestaurantUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.place == null, error = null)
            try {
                val loc = app.location.value
                _state.value = RestaurantUiState(false, apiCall { app.api.restaurant(id, loc?.lat, loc?.lng) })
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}

@Composable
fun RestaurantScreen(
    vm: RestaurantViewModel,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)
    when {
        state.loading -> LoadingBox()
        state.place == null -> CenteredMessage(
            "Couldn't open this place", state.error, Icons.Filled.Refresh, "Try again", vm::load, "Go back", onBack,
        )
        else -> RestaurantContent(
            place = state.place!!,
            engagementOf = { overrides[it.id] ?: EngagementState(it.stats, it.me) },
            thumbOf = { app.mediaUrl(it.cover?.thumbUrl) },
            onBack = onBack,
            onOpenDish = onOpenDish,
            onSave = { actions.toggle(it, Engagement.Save) },
            onMaps = { p -> actions.openMaps(p.lat, p.lng, p.name) },
        )
    }
}

@Composable
fun RestaurantContent(
    place: RestaurantDetail,
    engagementOf: (DishCard) -> EngagementState,
    thumbOf: (DishCard) -> String?,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onSave: (DishCard) -> Unit,
    onMaps: (RestaurantDetail) -> Unit,
) {
    val totalRecs = place.dishes.sumOf { engagementOf(it).stats.recommenders }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Reveal(0) {
                    // The place as a big restaurant token: name on top, perforation, then the numbers.
                    // The notches follow the perforation, wherever the name's length puts it.
                    var stub by remember { mutableStateOf(120.dp) }
                    val density = LocalDensity.current
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .clip(TicketShape(corner = 28.dp, notch = 13.dp, notchFromTop = stub))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { stub = with(density) { it.height.toDp() } }
                                .padding(horizontal = 22.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            KickerText(
                                listOfNotNull(place.locality, Format.distance(place.distanceM)).joinToString("  ·  ").ifEmpty { "Place" },
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            Text(
                                place.name, style = MaterialTheme.typography.displaySmall, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (place.status == "pending") Tag("Waiting for review")
                        }
                        DashedLine(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f), Modifier.padding(horizontal = 20.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BigStat(Format.compact(totalRecs), "recommends")
                            Spacer(Modifier.width(22.dp))
                            BigStat(place.dishes.size.toString(), if (place.dishes.size == 1) "dish" else "dishes")
                            place.priceBand?.let {
                                Spacer(Modifier.width(22.dp))
                                BigStat("₹".repeat(it), "price")
                            }
                            Spacer(Modifier.weight(1f))
                            PrimaryButton("Go", { onMaps(place) }, leading = AppIcons.Directions)
                        }
                    }
                }
            }
        }
        item(key = "title") {
            Text(
                "Most recommended here",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 6.dp),
            )
        }
        if (place.dishes.isEmpty()) {
            item(key = "empty") { CenteredMessage("No photos yet", "Post the first dish from this place.") }
        }
        itemsIndexed(place.dishes, key = { _, d -> d.id }) { i, d ->
            Reveal(60 + 50 * i.coerceAtMost(8)) {
                DishRow(d, engagementOf(d), thumbOf(d), { onOpenDish(d.id) }, { onSave(d) }, showPlace = false, rank = i + 1)
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}
