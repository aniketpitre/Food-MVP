package app.foodfeed.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.PlaceFacets
import app.foodfeed.data.PlaceItem
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DemoTag
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.components.rememberTakeMeThere
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.SkeletonBlock
import app.foodfeed.ui.theme.VegGreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlaceSort(val api: String, val label: String, val needsLocation: Boolean = false) {
    Loved("loved", "Most loved"),
    Nearest("nearest", "Nearest", needsLocation = true),
    Dishes("dishes", "Most dishes"),
    New("new", "Newest posts"),
}

/** What the list is narrowed to. Nothing here changes how dishes are ranked. */
data class PlaceFilters(
    val sort: PlaceSort = PlaceSort.Loved,
    val veg: Boolean = false,
    val area: String? = null,
    val cuisine: String? = null,
    val category: Int? = null,
    val maxKm: Double? = null,
) {
    /** How many filters are on (the sort doesn't count). */
    val count: Int get() = listOf(veg, area != null, cuisine != null, category != null, maxKm != null).count { it }
    val needsLocation: Boolean get() = sort.needsLocation || maxKm != null
}

data class PlacesUiState(
    val filters: PlaceFilters = PlaceFilters(),
    val facets: PlaceFacets = PlaceFacets(),
    val items: List<PlaceItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val needsLocation: Boolean = false,
    val endReached: Boolean = false,
)

class PlacesViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PlacesUiState())
    val state: StateFlow<PlacesUiState> = _state.asStateFlow()
    private var next: Int? = 0
    private var job: Job? = null

    init {
        viewModelScope.launch {
            combine(app.city, app.contentVersion) { c, _ -> c }.distinctUntilChanged().collect { city ->
                if (city == null) return@collect
                runCatching { apiCall { app.api.placeFacets(city.id) } }.onSuccess { f -> _state.update { it.copy(facets = f) } }
                reload()
            }
        }
        // A first fix (or a better one) re-sorts by distance when that's what's asked.
        viewModelScope.launch {
            app.location.collect { if (it != null && (_state.value.needsLocation || _state.value.filters.needsLocation)) reload() }
        }
    }

    fun setFilters(f: PlaceFilters) {
        if (f == _state.value.filters) return
        _state.update { it.copy(filters = f) }
        reload()
    }

    fun reload() {
        next = 0
        job?.cancel()
        _state.update { it.copy(loading = true, error = null, needsLocation = false, endReached = false, items = emptyList()) }
        job = viewModelScope.launch { fetch(replace = true) }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || next == null || job?.isActive == true) return
        job = viewModelScope.launch { fetch(replace = false) }
    }

    private suspend fun fetch(replace: Boolean) {
        val city = app.city.value ?: return
        val f = _state.value.filters
        val loc = app.location.value
        if (f.needsLocation && loc == null) {
            _state.update { it.copy(loading = false, needsLocation = true) }
            return
        }
        try {
            val page = apiCall {
                app.api.places(
                    city = city.id, lat = loc?.lat, lng = loc?.lng, sort = f.sort.api, area = f.area, cuisine = f.cuisine,
                    category = f.category, veg = f.veg, maxKm = f.maxKm, offset = if (replace) 0 else next ?: 0,
                )
            }
            next = page.nextOffset
            _state.update {
                it.copy(
                    items = if (replace) page.items else (it.items + page.items).distinctBy { p -> p.id },
                    loading = false,
                    endReached = page.nextOffset == null,
                )
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }
}

/**
 * Explore › Places: the city's places with what people loved at each, narrowed by area,
 * dish type, cuisine, pure veg and distance, sorted by most loved, nearest, most dishes or
 * newest. Tap a place for its dishes; the arrow starts directions inside the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(vm: PlacesViewModel, onOpenPlace: (String) -> Unit, onAskLocation: () -> Unit, onAddDish: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val takeMeThere = rememberTakeMeThere()
    var sheet by remember { mutableStateOf(false) }
    var areas by remember { mutableStateOf(false) }
    val f = state.filters
    val list = rememberLazyListState()
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= list.layoutInfo.totalItemsCount - 4) vm.loadMore() }
    }

    Column(Modifier.fillMaxSize().testTag("places")) {
        // Quick controls: veg, area, sort, everything else.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(
                if (f.veg) "Veg only" else "Veg",
                on = f.veg,
                tag = "places_veg",
                leading = { VegMark(true, size = 12.dp) },
            ) { vm.setFilters(f.copy(veg = !f.veg)) }
            Pill(f.area ?: "All areas", on = f.area != null, tag = "places_area", leading = { Icon(AppIcons.Near, null, Modifier.size(14.dp)) }) { areas = true }
            Pill(f.sort.label, on = f.sort != PlaceSort.Loved, tag = "places_sort") {
                // Tap to cycle through the sorts; the sheet lists them too.
                val all = PlaceSort.entries
                vm.setFilters(f.copy(sort = all[(all.indexOf(f.sort) + 1) % all.size]))
            }
            Pill(if (f.count > 0) "Filters · ${f.count}" else "Filters", on = f.count > 0, tag = "places_filters") { sheet = true }
        }
        // Explore by dish type.
        if (state.facets.categories.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.facets.categories.take(16).forEach { c ->
                    val on = f.category == c.id
                    Column(
                        Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { vm.setFilters(f.copy(category = if (on) null else c.id)) }
                            .testTag("places_cat_${c.id}")
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) { Text(c.emoji ?: "🍽️", fontSize = 26.sp) }
                        Spacer(Modifier.height(4.dp))
                        Text(c.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                state.needsLocation -> CenteredMessage(
                    "Where are you?",
                    "Allow location to sort places by distance.",
                    AppIcons.Near, "Allow location", onAskLocation,
                    secondaryLabel = "Sort by most loved",
                    onSecondary = { vm.setFilters(f.copy(sort = PlaceSort.Loved, maxKm = null)) },
                )
                state.loading && state.items.isEmpty() -> Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { SkeletonBlock(Modifier.fillMaxWidth().height(112.dp), RoundedCornerShape(22.dp)) }
                }
                state.error != null && state.items.isEmpty() -> CenteredMessage("Couldn't load places", state.error, null, "Try again", vm::reload)
                state.items.isEmpty() -> CenteredMessage(
                    "No places match",
                    if (f.count > 0) "Try fewer filters." else "Post a dish you love to put a place on WorthAbite.",
                    null,
                    if (f.count > 0) "Clear filters" else "Post a dish",
                    if (f.count > 0) ({ vm.setFilters(PlaceFilters(sort = f.sort.takeUnless { it.needsLocation } ?: PlaceSort.Loved)) }) else onAddDish,
                )
                else -> LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("places_list"),
                ) {
                    items(state.items, key = { it.id }) { p ->
                        PlaceCard(
                            p,
                            imageUrl = app.mediaUrl(p.photoUrl ?: p.topDish?.thumbUrl),
                            onOpen = { onOpenPlace(p.id) },
                            onGo = { takeMeThere.go(p.id, p.lat, p.lng, p.name, p.demo) },
                        )
                    }
                }
            }
        }
    }

    if (sheet) FilterSheet(state, onApply = { vm.setFilters(it); sheet = false }, onDismiss = { sheet = false })
    if (areas) AreaSheet(state, onPick = { vm.setFilters(f.copy(area = it)); areas = false }, onDismiss = { areas = false })
}

@Composable
private fun Pill(label: String, on: Boolean, tag: String, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (on) Ink else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.width(6.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceCard(p: PlaceItem, imageUrl: String?, onOpen: () -> Unit, onGo: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("place_${p.id}"),
    ) {
        Row(Modifier.padding(10.dp)) {
            Box(Modifier.size(92.dp).clip(RoundedCornerShape(16.dp))) {
                if (imageUrl != null) DishImage(imageUrl, null, p.name, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Text(p.emoji, fontSize = 36.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.demo) { Spacer(Modifier.width(6.dp)); DemoTag() }
                }
                val where = listOfNotNull(p.locality, p.cuisine?.split(';', ',')?.firstOrNull()?.trim()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() })
                if (where.isNotEmpty()) {
                    Text(where.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                val facts = listOfNotNull(
                    p.distanceM?.let { listOfNotNull(Format.distance(it), Format.driveTime(it)).joinToString(" · ") },
                    if (p.lovers > 0) "♥ ${p.lovers} loved" else null,
                    if (p.dishCount > 0) "${p.dishCount} dish${if (p.dishCount == 1) "" else "es"}" else null,
                )
                Text(facts.joinToString("   "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.pureVeg || p.dishNames.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), maxLines = 1) {
                        if (p.pureVeg) {
                            Text(
                                "Pure veg",
                                style = MaterialTheme.typography.labelSmall,
                                color = VegGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(VegGreen.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        p.dishNames.forEach { d ->
                            Text(
                                d,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onGo, modifier = Modifier.align(Alignment.CenterVertically).testTag("place_go_${p.id}")) {
                Icon(AppIcons.Directions, "Take me there", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Every filter at once, applied together (like FavHiker's sheet, minus the ratings we don't keep). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(state: PlacesUiState, onApply: (PlaceFilters) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(state.filters) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.navigationBarsPadding().testTag("places_sheet")) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { f = PlaceFilters() }, modifier = Modifier.testTag("places_clear")) { Text("Clear all") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Section("Sort by")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceSort.entries.forEach { s -> Pill(s.label, f.sort == s, "sheet_sort_${s.api}") { f = f.copy(sort = s) } }
                }
                Section("Dietary")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VegMark(true, size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Pure vegetarian places", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(f.veg, { f = f.copy(veg = it) }, Modifier.testTag("sheet_veg"))
                }
                Section("Distance")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Double?>(null, 1.0, 3.0, 5.0, 10.0).forEach { km ->
                        Pill(km?.let { "Within ${it.toInt()} km" } ?: "Any", f.maxKm == km, "sheet_km_${km?.toInt() ?: 0}") { f = f.copy(maxKm = km) }
                    }
                }
                if (state.facets.categories.isNotEmpty()) {
                    Section("Dish type")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.facets.categories.forEach { c ->
                            Pill(listOfNotNull(c.emoji, c.name).joinToString(" "), f.category == c.id, "sheet_cat_${c.id}") {
                                f = f.copy(category = if (f.category == c.id) null else c.id)
                            }
                        }
                    }
                }
                if (state.facets.cuisines.isNotEmpty()) {
                    Section("Cuisine")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.facets.cuisines.take(24).forEach { c ->
                            Pill("${c.name} (${c.places})", f.cuisine == c.name, "sheet_cuisine_${c.name}") {
                                f = f.copy(cuisine = if (f.cuisine == c.name) null else c.name)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            PrimaryButton("Show places", { onApply(f) }, Modifier.fillMaxWidth().padding(20.dp).testTag("places_apply"))
        }
    }
}

/** "Explore around you": pick an area of the city, or all of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AreaSheet(state: PlacesUiState, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp).testTag("area_sheet")) {
            KickerText("Explore around you", Modifier.padding(horizontal = 20.dp))
            Text("Areas in ${app.city.value?.name ?: "your city"}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                item {
                    AreaRow("All areas", null, state.filters.area == null) { onPick(null) }
                }
                items(state.facets.areas, key = { it.name }) { a ->
                    AreaRow(a.name, if (a.withDishes > 0) "${a.withDishes} with dishes" else "${a.places} places", state.filters.area == a.name) { onPick(a.name) }
                }
            }
        }
    }
}

@Composable
private fun AreaRow(name: String, detail: String?, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("area_$name").padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Near, null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
}
