package app.foodfeed.ui.add

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.Category
import app.foodfeed.data.CreatedPost
import app.foodfeed.data.DishSuggestion
import app.foodfeed.data.ImageCompressor
import app.foodfeed.data.PlaceOption
import app.foodfeed.data.apiCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class VegChoice { Veg, NonVeg, Unsure }

data class AddDishUiState(
    val photo: Uri? = null,
    val dishName: String = "",
    val suggestions: List<DishSuggestion> = emptyList(),
    val categories: List<Category> = emptyList(),
    val categoryId: Int? = null,
    val placeQuery: String = "",
    val places: List<PlaceOption> = emptyList(),
    val placesNearby: Boolean = false,
    val selectedPlace: PlaceOption? = null,
    val addingNewPlace: Boolean = false,
    val newPlaceName: String = "",
    val newPlaceLocality: String = "",
    val price: String = "",
    val veg: VegChoice = VegChoice.Unsure,
    val caption: String = "",
    val sponsored: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val created: CreatedPost? = null,
) {
    val hasLocation get() = placesNearby
    val canSubmit: Boolean
        get() = !submitting && photo != null && dishName.trim().length >= 2 &&
            (selectedPlace != null || (addingNewPlace && newPlaceName.trim().length >= 2))
}

class AddDishViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AddDishUiState())
    val state: StateFlow<AddDishUiState> = _state.asStateFlow()

    // One key per attempt: a retried upload after a network drop never creates a duplicate.
    private var idempotencyKey = UUID.randomUUID().toString()
    private var suggestJob: Job? = null
    private var placeJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { apiCall { app.api.categories() } }.onSuccess { c -> _state.update { it.copy(categories = c) } }
        }
        loadPlaces()
    }

    fun setPhoto(uri: Uri?) = _state.update { it.copy(photo = uri, error = null) }

    fun setDishName(name: String) {
        _state.update { it.copy(dishName = name.take(60)) }
        suggestJob?.cancel()
        if (name.isBlank()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            runCatching { apiCall { app.api.suggestDishes(name.trim(), app.city.value?.id) } }
                .onSuccess { s ->
                    _state.update { st -> st.copy(suggestions = s.filterNot { it.name.equals(name.trim(), ignoreCase = true) }.take(5)) }
                }
        }
    }

    fun pickSuggestion(s: DishSuggestion) = _state.update {
        it.copy(dishName = s.name, suggestions = emptyList(), categoryId = s.categoryId ?: it.categoryId)
    }

    fun setCategory(id: Int?) = _state.update { it.copy(categoryId = if (it.categoryId == id) null else id) }

    fun setPlaceQuery(q: String) {
        _state.update { it.copy(placeQuery = q.take(60)) }
        placeJob?.cancel()
        placeJob = viewModelScope.launch {
            delay(300)
            fetchPlaces()
        }
    }

    fun loadPlaces() {
        placeJob?.cancel()
        placeJob = viewModelScope.launch { fetchPlaces() }
    }

    private suspend fun fetchPlaces() {
        val q = _state.value.placeQuery.trim()
        val loc = app.location.value
        val city = app.city.value
        try {
            val list = when {
                loc != null -> apiCall { app.api.nearbyRestaurants(loc.lat, loc.lng, q.ifBlank { null }) }
                city != null -> apiCall { app.api.restaurants(city.id, q) }
                else -> emptyList()
            }
            _state.update { it.copy(places = list, placesNearby = loc != null) }
        } catch (e: ApiException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun selectPlace(p: PlaceOption?) = _state.update { it.copy(selectedPlace = p, addingNewPlace = false) }

    fun startNewPlace() = _state.update {
        it.copy(addingNewPlace = true, selectedPlace = null, newPlaceName = it.placeQuery)
    }

    fun cancelNewPlace() = _state.update { it.copy(addingNewPlace = false) }
    fun setNewPlaceName(v: String) = _state.update { it.copy(newPlaceName = v.take(80)) }
    fun setNewPlaceLocality(v: String) = _state.update { it.copy(newPlaceLocality = v.take(60)) }
    fun setPrice(v: String) = _state.update { it.copy(price = v.filter(Char::isDigit).take(5)) }
    fun setVeg(v: VegChoice) = _state.update { it.copy(veg = v) }
    fun setCaption(v: String) = _state.update { it.copy(caption = v.take(280)) }
    fun setSponsored(v: Boolean) = _state.update { it.copy(sponsored = v) }

    fun submit(context: Context) {
        val s = _state.value
        if (!s.canSubmit) return
        val photo = s.photo ?: return
        if (s.addingNewPlace && app.location.value == null) {
            _state.update { it.copy(error = "To add a new place we need your approximate location for its map pin. Allow location and try again.") }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val jpeg = try {
                    ImageCompressor.compress(context, photo)
                } catch (e: Exception) {
                    throw ApiException("bad_image", "Couldn't read that photo. Try another one.")
                }
                val fields = buildMap {
                    put("idempotencyKey", idempotencyKey)
                    put("dishName", s.dishName.trim())
                    s.categoryId?.let { put("categoryId", it.toString()) }
                    if (s.addingNewPlace) {
                        val loc = app.location.value!!
                        put("newPlaceName", s.newPlaceName.trim())
                        if (s.newPlaceLocality.isNotBlank()) put("newPlaceLocality", s.newPlaceLocality.trim())
                        put("newPlaceLat", loc.lat.toString())
                        put("newPlaceLng", loc.lng.toString())
                    } else {
                        put("restaurantId", s.selectedPlace!!.id)
                    }
                    s.price.toIntOrNull()?.takeIf { it > 0 }?.let { put("priceInr", it.toString()) }
                    when (s.veg) {
                        VegChoice.Veg -> put("isVeg", "true")
                        VegChoice.NonVeg -> put("isVeg", "false")
                        VegChoice.Unsure -> Unit
                    }
                    if (s.caption.isNotBlank()) put("caption", s.caption.trim())
                    put("isSponsored", s.sponsored.toString())
                }
                val created = app.createPost(fields, jpeg)
                _state.update { it.copy(submitting = false, created = created) }
            } catch (e: ApiException) {
                _state.update { it.copy(submitting = false, error = e.message) }
            }
        }
    }

    /** Clears the form for the next post. */
    fun reset() {
        idempotencyKey = UUID.randomUUID().toString()
        val categories = _state.value.categories
        val places = _state.value.places
        _state.value = AddDishUiState(categories = categories, places = places, placesNearby = app.location.value != null)
    }
}
