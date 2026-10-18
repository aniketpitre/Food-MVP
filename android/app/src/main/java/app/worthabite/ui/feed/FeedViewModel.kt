package app.worthabite.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.Category
import app.worthabite.data.DishCard
import app.worthabite.data.SelectedCity
import app.worthabite.data.apiCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class FeedTab(val api: String, val label: String) {
    /** Dishes from people whose taste matches yours and people you follow (signed in only). */
    ForYou("foryou", "For you"),
    Top("top", "Top"),
    Nearby("nearby", "Nearby"),
    /** Places, with filters: shown by its own screen (ui/explore), not the dish pager. */
    Places("places", "Places"),
}

data class FeedUiState(
    val city: SelectedCity? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategory: Int? = null,
    val tab: FeedTab = FeedTab.Top,
    val items: List<DishCard> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
    val needsLocation: Boolean = false,
    /** Changes whenever the list is replaced, so the pager jumps back to the first card. */
    val generation: Int = 0,
    val signedIn: Boolean = false,
) {
    val tabs: List<FeedTab> get() = FeedTab.entries.filter { it != FeedTab.ForYou || signedIn }
}

class FeedViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(FeedUiState(city = app.city.value))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    // One seed per app session keeps the order of new dishes stable while scrolling.
    private val seed = UUID.randomUUID().toString().take(12)
    private var nextOffset: Int? = 0
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            app.pendingExploreTab.collect { api ->
                val tab = FeedTab.entries.firstOrNull { it.api == api } ?: return@collect
                app.pendingExploreTab.value = null
                selectTab(tab)
            }
        }
        viewModelScope.launch {
            app.session.collect { s ->
                val signedIn = s != null
                if (signedIn == _state.value.signedIn) return@collect
                _state.update { it.copy(signedIn = signedIn) }
                // Signed out on For You: back to Top.
                if (!signedIn && _state.value.tab == FeedTab.ForYou) selectTab(FeedTab.Top)
            }
        }
        viewModelScope.launch {
            combine(app.city, app.contentVersion, app.serverUrl) { city, version, url -> Triple(city, version, url) }
                .distinctUntilChanged()
                .collect { (city, _, _) ->
                    _state.update { it.copy(city = city) }
                    if (city != null) {
                        loadCategories()
                        reload()
                    }
                }
        }
    }

    private fun loadCategories() {
        if (_state.value.categories.isNotEmpty()) return
        viewModelScope.launch {
            runCatching { apiCall { app.api.categories() } }.onSuccess { cats -> _state.update { it.copy(categories = cats) } }
        }
    }

    fun selectTab(tab: FeedTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab) }
        reload()
    }

    fun selectCategory(id: Int?) {
        if (id == _state.value.selectedCategory) return
        _state.update { it.copy(selectedCategory = id) }
        reload()
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        reload(keepItems = true)
    }

    /** Called when location permission or a fix arrives, so Nearby and distances update. */
    fun locationChanged() {
        if (_state.value.tab == FeedTab.Nearby || _state.value.needsLocation) reload()
    }

    fun reload(keepItems: Boolean = false) {
        nextOffset = 0
        loadJob?.cancel()
        _state.update {
            it.copy(
                loading = !keepItems,
                error = null,
                endReached = false,
                needsLocation = false,
                items = if (keepItems) it.items else emptyList(),
            )
        }
        loadJob = viewModelScope.launch { fetch(replace = true) }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || loadJob?.isActive == true || nextOffset == null) return
        loadJob = viewModelScope.launch { fetch(replace = false) }
    }

    private suspend fun fetch(replace: Boolean) {
        val s = _state.value
        val city = s.city ?: return
        val loc = app.location.value
        if (s.tab == FeedTab.Places) {
            _state.update { it.copy(loading = false, refreshing = false, items = emptyList()) }
            return
        }
        if (s.tab == FeedTab.Nearby && loc == null) {
            _state.update { it.copy(loading = false, refreshing = false, needsLocation = true, items = emptyList()) }
            return
        }
        try {
            val page = apiCall {
                app.api.feed(
                    city = city.id, category = s.selectedCategory, tab = s.tab.api, limit = 20,
                    offset = if (replace) 0 else nextOffset ?: 0, seed = seed, lat = loc?.lat, lng = loc?.lng,
                )
            }
            nextOffset = page.nextOffset
            _state.update { cur ->
                val merged = if (replace) page.items
                else (cur.items + page.items).distinctBy { it.id } // scores can shift between pages
                cur.copy(
                    items = merged,
                    loading = false,
                    refreshing = false,
                    endReached = page.nextOffset == null,
                    generation = if (replace) cur.generation + 1 else cur.generation,
                )
            }
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, refreshing = false, error = e.message) }
        }
    }
}
