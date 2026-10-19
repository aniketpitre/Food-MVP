package app.worthabite.ui.search

import app.worthabite.data.Category
import app.worthabite.data.CuisineInfo
import app.worthabite.data.Suggestion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import app.worthabite.ui.components.DashedLine
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.TicketShape
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.pressScale
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
import app.worthabite.data.engagement
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.DishCard
import app.worthabite.data.Person
import app.worthabite.ui.components.Avatar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.data.SearchResult
import app.worthabite.data.apiCall
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishRow
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.SectionTitle
import app.worthabite.ui.components.rememberDishActions
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
    /** Autocomplete for what's typed so far. */
    val suggestions: List<Suggestion> = emptyList(),
    /** True once you pick a suggestion or press search: the suggestions step aside. */
    val committed: Boolean = false,
    /** For browsing before you type: cuisines, dish types, what's popular, your recent searches. */
    val cuisines: List<CuisineInfo> = emptyList(),
    val categories: List<Category> = emptyList(),
    val popular: List<Suggestion> = emptyList(),
    val recent: List<String> = emptyList(),
)

class SearchViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            app.loadRecentSearches()
            app.recentSearches.collect { r -> _state.update { it.copy(recent = r) } }
        }
        viewModelScope.launch {
            app.city.collect { city ->
                if (city == null) return@collect
                val cuisines = runCatching { apiCall { app.api.cuisines(city.id) } }.getOrDefault(emptyList())
                val cats = runCatching { apiCall { app.api.categories() } }.getOrDefault(emptyList())
                val popular = runCatching { apiCall { app.api.suggest("", city.id) }.items }.getOrDefault(emptyList())
                _state.update { it.copy(cuisines = cuisines, categories = cats, popular = popular) }
            }
        }
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q, committed = false) }
        job?.cancel()
        suggestJob?.cancel()
        val city = app.city.value ?: return
        if (q.isBlank()) {
            _state.update { it.copy(result = null, loading = false, error = null, suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(120)
            val items = runCatching { apiCall { app.api.suggest(q.trim(), city.id) }.items }.getOrNull() ?: return@launch
            if (_state.value.query == q) _state.update { it.copy(suggestions = items) }
        }
        if (q.trim().length < 2) {
            _state.update { it.copy(result = null, loading = false, error = null) }
            return
        }
        run(q.trim(), delayMs = 300)
    }

    /** The search key, or a dish or area suggestion: search for exactly this now. */
    fun commit(text: String = _state.value.query) {
        val q = text.trim()
        if (q.length < 2) return
        app.rememberSearch(q)
        suggestJob?.cancel()
        _state.update { it.copy(query = q, committed = true) }
        run(q, delayMs = 0)
    }

    /** Everything in a cuisine ("Italian") or a dish type ("Momos"). */
    fun browse(name: String, cuisine: String? = null, category: Int? = null) {
        app.rememberSearch(name)
        suggestJob?.cancel()
        _state.update { it.copy(query = name, committed = true, suggestions = emptyList()) }
        run(name, delayMs = 0, cuisine = cuisine, category = category)
    }

    fun forgetRecent() = app.forgetSearches()

    private fun run(q: String, delayMs: Long, cuisine: String? = null, category: Int? = null) {
        val city = app.city.value ?: return
        job?.cancel()
        job = viewModelScope.launch {
            delay(delayMs) // wait for the user to pause typing
            _state.update { it.copy(loading = true, error = null) }
            try {
                val loc = app.location.value
                val r = apiCall { app.api.search(q, city.id, loc?.lat, loc?.lng, cuisine, category) }
                val people = if (r.cuisine != null || r.category != null) emptyList()
                else runCatching { apiCall { app.api.people(q) } }.getOrDefault(emptyList())
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
        onCommit = { vm.commit() },
        onSuggestion = { sg ->
            when (sg.type) {
                "place" -> sg.id?.let(onOpenRestaurant)
                "person" -> sg.id?.let(onOpenUser)
                "cuisine" -> vm.browse(sg.text, cuisine = sg.id)
                "category" -> vm.browse(sg.text, category = sg.id?.toIntOrNull())
                else -> vm.commit(sg.text)
            }
        },
        onCuisine = { c -> vm.browse(c.name, cuisine = c.slug) },
        onCategory = { c -> vm.browse(c.name, category = c.id) },
        onForgetRecent = vm::forgetRecent,
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
    onCommit: () -> Unit = {},
    onSuggestion: (Suggestion) -> Unit = {},
    onCuisine: (CuisineInfo) -> Unit = {},
    onCategory: (Category) -> Unit = {},
    onForgetRecent: () -> Unit = {},
) {
    val focus = remember { FocusRequester() }
    var tab by remember { mutableStateOf(SearchTab.Top) }
    // A cuisine or dish type shows everything at once, without the tabs.
    LaunchedEffect(state.result?.cuisine, state.result?.category) {
        if (state.result?.cuisine != null || state.result?.category != null) tab = SearchTab.Top
    }
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onCommit() }),
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
        // Autocomplete while typing; it steps aside once you pick one or press search.
        val showSuggestions = !state.committed && state.query.isNotBlank() && state.suggestions.isNotEmpty()
        if (showSuggestions) {
            SuggestionList(state.query, state.suggestions, onSuggestion)
            return@Column
        }
        if (state.result != null && state.result.cuisine == null && state.result.category == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchTab.entries.forEach { t ->
                    val on = t == tab
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceContainerHigh)
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
            r == null -> SearchIdeas(state, onQuery = { onSuggestion(Suggestion("dish", it)) }, onCuisine, onCategory, onForgetRecent)
            r.dishes.isEmpty() && r.restaurants.isEmpty() && state.people.isEmpty() && tab == SearchTab.Top -> CenteredMessage(
                "Nothing found", "Try another spelling or a broader word like \"dosa\".", Icons.Filled.Search,
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                val q = state.query.trim()
                val facet = r.cuisine ?: r.category
                if (facet != null) {
                    item(key = "facet") {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("search_facet"), verticalAlignment = Alignment.CenterVertically) {
                            facet.emoji?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(facet.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    listOf(
                                        if (r.dishes.size == 1) "1 dish" else "${r.dishes.size} dishes",
                                        if (r.restaurants.size == 1) "1 place" else "${r.restaurants.size} places",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                // The craving question: where's the best of this? (ranked by people who tried it)
                if (facet == null && (tab == SearchTab.Top || tab == SearchTab.Dishes) && q.length >= 2 && r.dishes.isNotEmpty()) {
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

/** Autocomplete lines under the search box, the typed part in bold. */
@Composable
private fun SuggestionList(query: String, items: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().testTag("search_suggestions"), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items, key = { it.type + it.text + (it.id ?: "") }) { sg ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(sg) }.padding(horizontal = 20.dp, vertical = 11.dp).testTag("suggestion_${sg.type}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sg.emoji != null) {
                        Text(sg.emoji, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(
                            when (sg.type) {
                                "place" -> AppIcons.Restaurant
                                "area" -> AppIcons.Pin
                                "person" -> Icons.Filled.Person
                                else -> Icons.Filled.Search
                            },
                            null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(highlight(sg.text, query), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    sg.sub?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** [text] with the part matching what was typed in bold. */
private fun highlight(text: String, query: String) = buildAnnotatedString {
    val q = query.trim()
    val at = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (at < 0) {
        append(text)
    } else {
        append(text.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(text.substring(at, at + q.length)) }
        append(text.substring(at + q.length))
    }
}

/**
 * Before typing: your recent searches, what's popular, then every cuisine and dish type to
 * browse, popping in one after another.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchIdeas(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onCuisine: (CuisineInfo) -> Unit,
    onCategory: (Category) -> Unit,
    onForgetRecent: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().testTag("search_ideas"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)) {
        item {
            Text(
                "Spell it any way you like: wada pav, vada pav, vadapav.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "Clear", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onForgetRecent).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.recent.forEachIndexed { i, r -> IdeaChip("🕘 $r", i) { onQuery(r) } }
                }
            }
        }
        item {
            Text("Popular now", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            val popular = state.popular.map { it.text }.ifEmpty { listOf("Misal", "Vada pav", "Biryani", "Dosa", "Cutting chai", "Jalebi", "Thali", "Pav bhaji") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                popular.forEachIndexed { i, idea -> IdeaChip(idea, i) { onQuery(idea) } }
            }
        }
        if (state.cuisines.isNotEmpty()) {
            item {
                Text("Cuisines", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("search_cuisines")) {
                    state.cuisines.forEachIndexed { i, c ->
                        val count = when {
                            c.dishes > 0 -> " · ${c.dishes}"
                            c.places > 0 -> " · ${c.places} places"
                            else -> ""
                        }
                        IdeaChip("${c.emoji ?: ""} ${c.name}$count".trim(), i, tag = "cuisine_${c.slug}") { onCuisine(c) }
                    }
                }
            }
        }
        if (state.categories.isNotEmpty()) {
            item {
                Text("Dish types", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.categories.forEachIndexed { i, c -> IdeaChip("${c.emoji ?: ""} ${c.name}".trim(), i) { onCategory(c) } }
                }
            }
        }
    }
}

@Composable
private fun IdeaChip(text: String, index: Int, tag: String? = null, onClick: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(30L * index.coerceAtMost(14)); appear.animateTo(1f, Motion.bouncy()) }
    val interaction = remember { MutableInteractionSource() }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer {
                val p = appear.value
                alpha = p.coerceIn(0f, 1f)
                scaleX = 0.5f + 0.5f * p
                scaleY = 0.5f + 0.5f * p
                rotationZ = (if (index % 2 == 0) -2f else 2f) * p
            }
            .pressScale(interaction, 0.9f)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
