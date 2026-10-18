package app.worthabite.ui.saved

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import app.worthabite.ui.components.EngageAction
import app.worthabite.ui.theme.pressScale
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import app.worthabite.ui.components.ActionLook
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.dishHero
import kotlinx.coroutines.delay
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
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.DishCard
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.data.apiCall
import app.worthabite.ui.Format
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.VegMark
import app.worthabite.ui.components.rememberDishActions
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
            AppIcons.BookmarkBorder,
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp)) {
                    KickerText(if (items.size == 1) "1 dish to try" else "${items.size} dishes to try")
                    Text("Your cravings", style = MaterialTheme.typography.displaySmall)
                }
            }
            itemsIndexed(items, key = { _, d -> d.id }) { i, d ->
                // Polaroids pinned to a board: each leans a little, and drops in a beat after the last.
                val tilt = listOf(-2f, 1.6f, 1.2f, -1.4f)[i % 4]
                val drop = remember { Animatable(0f) }
                LaunchedEffect(Unit) { delay(60L * i.coerceAtMost(8)); drop.animateTo(1f, Motion.bouncy()) }
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .animateItem()
                        .graphicsLayer {
                            val p = drop.value
                            alpha = p.coerceIn(0f, 1f)
                            translationY = (1f - p) * -80f
                            rotationZ = tilt + (1f - p) * tilt * 4f
                        }
                        .pressScale(interaction, 0.95f)
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(16.dp))
                        .clickable(interaction, indication = null, role = Role.Button) { onOpenDish(d.id) }
                        .padding(8.dp),
                ) {
                    DishImage(
                        thumbOf(d), d.cover?.placeholderColor, d.name,
                        Modifier.fillMaxWidth().aspectRatio(0.9f).dishHero(d.id, 10.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Row(Modifier.padding(top = 8.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                Format.placeLine(d.restaurant.name, null, d.distanceM),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        EngageAction(
                            Engagement.Save, true, "", null, "Remove from saved", { onUnsave(d) },
                            look = ActionLook.Bare, iconSize = 20.dp,
                        )
                    }
                }
            }
        }
    }
}
