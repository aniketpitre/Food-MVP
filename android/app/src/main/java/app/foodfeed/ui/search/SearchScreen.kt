package app.foodfeed.ui.search

import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.foodfeed.ui.components.DashedLine
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.TicketShape
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.pressScale
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
import app.foodfeed.data.engagement
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.Person
import app.foodfeed.ui.components.Avatar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val people: List<Person> = emptyList(),
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
                val people = runCatching { apiCall { app.api.people(q.trim()) } }.getOrDefault(emptyList())
                _state.update { it.copy(loading = false, result = r, people = people) }
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
    onOpenUser: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onCraving: (String) -> Unit = {},
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)
    SearchContent(
        state = state,
        onQuery = vm::onQuery,
        engagementOf = { overrides[it.id] ?: it.engagement() },
        thumbOf = { app.mediaUrl(it.cover?.thumbUrl) },
        onOpenDish = onOpenDish,
        onOpenRestaurant = onOpenRestaurant,
        onSave = { actions.toggle(it, Engagement.Save) },
        onOpenUser = onOpenUser,
        onBack = onBack,
        onCraving = onCraving,
    )
}

/** What the results show: everything, or only dishes, people or places. */
enum class SearchTab(val label: String) { Top("Top"), Dishes("Dishes"), People("People"), Places("Places") }

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
    onOpenUser: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onCraving: (String) -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    var tab by remember { mutableStateOf(SearchTab.Top) }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
            KickerText("Dishes, places and people")
            Text("Search", style = MaterialTheme.typography.displaySmall)
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = { Text("Dishes, places, people…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurface) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            textStyle = MaterialTheme.typography.titleMedium,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).focusRequester(focus).testTag("search_input"),
        )
        if (state.result != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchTab.entries.forEach { t ->
                    val on = t == tab
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(role = Role.Tab) { tab = t }
                            .testTag("search_tab_${t.name.lowercase()}")
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        val r = state.result
        when {
            state.loading && r == null -> LoadingBox()
            state.error != null -> CenteredMessage("Search failed", state.error)
            r == null -> SearchIdeas(onQuery)
            r.dishes.isEmpty() && r.restaurants.isEmpty() && state.people.isEmpty() && tab == SearchTab.Top -> CenteredMessage(
                "Nothing found", "Try another spelling or a broader word like \"dosa\".", Icons.Filled.Search,
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                val q = state.query.trim()
                // The craving question: where's the best of this? (ranked by people who tried it)
                if ((tab == SearchTab.Top || tab == SearchTab.Dishes) && q.length >= 2 && r.dishes.isNotEmpty()) {
                    item(key = "craving") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { onCraving(q) }
                                .testTag("search_craving")
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🏆", style = MaterialTheme.typography.titleLarge)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Best “$q” near you", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(
                                    "Ranked by people who tried it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (tab == SearchTab.People) {
                    if (state.people.isEmpty()) item { SectionTitle("No people found") }
                    items(state.people, key = { "pu" + it.handle }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenUser(p.handle) }.padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("search_person_${p.handle}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p.handle, p.displayName, 48.dp, photo = p.avatarUrl)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.displayName ?: p.handle, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull("@${p.handle}", if (p.isFollowing) "Following" else null, if (p.followers > 0) "${p.followers} followers" else null)
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (tab == SearchTab.Places) {
                    if (r.restaurants.isEmpty()) item { SectionTitle("No places found") }
                    items(r.restaurants, key = { "pl" + it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenRestaurant(p.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(AppIcons.Restaurant, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                p.locality?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                if (tab == SearchTab.Dishes && r.dishes.isEmpty()) item { SectionTitle("No dishes found") }
                if (tab == SearchTab.Top && state.people.isNotEmpty()) {
                    item(key = "people-title") { SectionTitle("People") }
                    item(key = "people") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(state.people, key = { "u" + it.handle }) { p ->
                                Column(
                                    Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpenUser(p.handle) }.padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Avatar(p.handle, p.displayName, 60.dp, photo = p.avatarUrl)
                                    Text(
                                        p.handle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (tab == SearchTab.Top && r.restaurants.isNotEmpty()) {
                    item(key = "places-title") { SectionTitle("Places") }
                    item(key = "places") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.restaurants, key = { "p" + it.id }) { p -> PlaceTicket(p.name, p.locality) { onOpenRestaurant(p.id) } }
                        }
                    }
                }
                val showDishes = tab == SearchTab.Top || tab == SearchTab.Dishes
                if (showDishes && r.dishes.isNotEmpty()) item { SectionTitle("Dishes") }
                if (showDishes) items(r.dishes, key = { it.id }) { d ->
                    DishRow(d, engagementOf(d), thumbOf(d), { onOpenDish(d.id) }, { onSave(d) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

/** A place as a little paper token, with notches and a perforation. */
@Composable
private fun PlaceTicket(name: String, locality: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = TicketShape(corner = 16.dp, notch = 8.dp, notchFromTop = 46.dp)
    Column(
        Modifier
            .width(180.dp)
            .pressScale(interaction, 0.95f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.height(46.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Restaurant, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DashedLine(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.25f), Modifier.padding(horizontal = 12.dp))
        Text(
            locality ?: "See the menu",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Before typing: a few tappable cravings that pop in one after another. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchIdeas(onQuery: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "Spell it any way you like: wada pav, vada pav, vadapav.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Misal", "Vada pav", "Biryani", "Dosa", "Cutting chai", "Jalebi", "Thali", "Pav bhaji").forEachIndexed { i, idea ->
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) { delay(45L * i); appear.animateTo(1f, Motion.bouncy()) }
                val interaction = remember { MutableInteractionSource() }
                Text(
                    idea,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .graphicsLayer {
                            val p = appear.value
                            alpha = p.coerceIn(0f, 1f)
                            scaleX = 0.5f + 0.5f * p
                            scaleY = 0.5f + 0.5f * p
                            rotationZ = (if (i % 2 == 0) -2f else 2f) * p
                        }
                        .pressScale(interaction, 0.9f)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(interaction, indication = null, role = Role.Button) { onQuery(idea.lowercase()) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
