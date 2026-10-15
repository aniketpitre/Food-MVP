package app.foodfeed.ui.restaurant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.Pill
import app.foodfeed.ui.components.rememberDishActions
import app.foodfeed.ui.theme.Saffron
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
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                    .statusBarsPadding()
                    .padding(bottom = 18.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (place.status == "pending") {
                        Pill("Waiting for review", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text(place.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        listOfNotNull(place.locality, Format.distance(place.distanceM), place.priceBand?.let { "₹".repeat(it) })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Favorite, null, tint = Saffron, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$totalRecs recommendations across ${place.dishes.size} dishes",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    OutlinedButton(onClick = { onMaps(place) }) {
                        Icon(AppIcons.Directions, null, Modifier.size(18.dp), tint = Saffron)
                        Spacer(Modifier.width(6.dp))
                        Text("Directions")
                    }
                }
            }
        }
        item {
            Text(
                "What people recommend here",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
            )
        }
        if (place.dishes.isEmpty()) {
            item { CenteredMessage("No photos yet", "Post the first dish from this place.", AppIcons.Restaurant) }
        }
        itemsIndexed(place.dishes, key = { _, d -> d.id }) { i, d ->
            DishRow(d, engagementOf(d), thumbOf(d), { onOpenDish(d.id) }, { onSave(d) }, showPlace = false, rank = i + 1)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
