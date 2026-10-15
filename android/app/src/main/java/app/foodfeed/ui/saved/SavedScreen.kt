package app.foodfeed.ui.saved

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import app.foodfeed.ui.components.EngageAction
import app.foodfeed.ui.theme.pressScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import app.foodfeed.data.EngagementState
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.components.rememberDishActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SavedUiState(val loading: Boolean = true, val items: List<DishCard> = emptyList(), val error: String? = null)

class SavedViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SavedUiState())
    val state: StateFlow<SavedUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.session, app.contentVersion) { s, _ -> s }.collect { if (it != null) load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.items.isEmpty(), error = null)
            try {
                val loc = app.location.value
                _state.value = SavedUiState(false, apiCall { app.api.saved(loc?.lat, loc?.lng) }.items)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}

@Composable
fun SavedScreen(
    vm: SavedViewModel,
    onOpenDish: (String) -> Unit,
    onSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onSignIn, showMessage)
    if (session == null) {
        CenteredMessage(
            "Dishes to try",
            "Sign in to keep a list of dishes you want to try.",
            null, "Sign in", onSignIn,
        )
        return
    }
    // Dishes un-saved on another screen disappear here at once.
    val visible = state.items.filter { (overrides[it.id]?.me ?: it.me).saved }
    SavedContent(
        loading = state.loading,
        error = state.error,
        items = visible,
        thumbOf = { app.mediaUrl(it.cover?.thumbUrl) },
        onOpenDish = onOpenDish,
        onUnsave = { actions.toggle(it, Engagement.Save) },
        onRetry = vm::load,
    )
}

@Composable
fun SavedContent(
    loading: Boolean,
    error: String?,
    items: List<DishCard>,
    thumbOf: (DishCard) -> String?,
    onOpenDish: (String) -> Unit,
    onUnsave: (DishCard) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        loading -> LoadingBox()
        error != null && items.isEmpty() -> CenteredMessage("Couldn't load saved dishes", error, Icons.Filled.Refresh, "Try again", onRetry)
        items.isEmpty() -> CenteredMessage(
            "Nothing saved yet",
            "Tap the bookmark on any dish to keep it here for later.",
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(2) }) {
                Text("Saved", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
            }
            items(items, key = { it.id }) { d ->
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .animateItem()
                        .pressScale(interaction, 0.97f)
                        .clickable(interaction, indication = null, role = Role.Button) { onOpenDish(d.id) },
                ) {
                    DishImage(
                        thumbOf(d), d.cover?.placeholderColor, d.name,
                        Modifier.fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(18.dp)),
                    )
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                Format.placeLine(d.restaurant.name, null, d.distanceM),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        EngageAction(Engagement.Save, true, "", null, "Remove from saved", { onUnsave(d) }, iconSize = 20.dp)
                    }
                }
            }
        }
    }
}
