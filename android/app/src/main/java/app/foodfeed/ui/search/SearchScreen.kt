package app.foodfeed.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.data.SearchResult
import app.foodfeed.data.apiCall
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishRow
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.SectionTitle
import app.foodfeed.ui.components.rememberDishActions
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val result: SearchResult? = null,
    val error: String? = null,
)

class SearchViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        job?.cancel()
        val city = app.city.value ?: return
        if (q.trim().length < 2) {
            _state.update { it.copy(result = null, loading = false, error = null) }
            return
        }
        job = viewModelScope.launch {
            delay(300) // wait for the user to pause typing
            _state.update { it.copy(loading = true, error = null) }
            try {
                val loc = app.location.value
                val r = apiCall { app.api.search(q.trim(), city.id, loc?.lat, loc?.lng) }
                _state.update { it.copy(loading = false, result = r) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)
    SearchContent(
        state = state,
        onQuery = vm::onQuery,
        engagementOf = { overrides[it.id] ?: EngagementState(it.stats, it.me) },
        thumbOf = { app.mediaUrl(it.cover?.thumbUrl) },
        onOpenDish = onOpenDish,
        onOpenRestaurant = onOpenRestaurant,
        onSave = { actions.toggle(it, Engagement.Save) },
    )
}

@Composable
fun SearchContent(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    engagementOf: (DishCard) -> EngagementState,
    thumbOf: (DishCard) -> String?,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onSave: (DishCard) -> Unit,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = { Text("Dish or place", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        val r = state.result
        when {
            state.loading && r == null -> LoadingBox()
            state.error != null -> CenteredMessage("Search failed", state.error)
            r == null -> CenteredMessage(
                "Find a dish",
                "Spell it any way you like: wada pav, vada pav, vadapav.",
            )
            r.dishes.isEmpty() && r.restaurants.isEmpty() -> CenteredMessage(
                "Nothing found", "Try another spelling or a broader word like \"dosa\".",
            )
            else -> LazyColumn {
                if (r.restaurants.isNotEmpty()) {
                    item(key = "places-title") { SectionTitle("Places") }
                    items(r.restaurants, key = { "p" + it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenRestaurant(p.id) }.padding(horizontal = 20.dp, vertical = 12.dp).animateItem(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Restaurant, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            p.locality?.let {
                                Text("  ·  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (r.dishes.isNotEmpty()) item { SectionTitle("Dishes") }
                items(r.dishes, key = { it.id }) { d ->
                    DishRow(d, engagementOf(d), thumbOf(d), { onOpenDish(d.id) }, { onSave(d) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}
