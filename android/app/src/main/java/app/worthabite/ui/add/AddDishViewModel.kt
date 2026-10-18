package app.worthabite.ui.add

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.Category
import app.worthabite.data.CreatedPost
import app.worthabite.data.DishSuggestion
import app.worthabite.data.ImageCompressor
import app.worthabite.data.PlaceOption
import app.worthabite.data.SimilarDish
import app.worthabite.data.SimilarPlace
import androidx.exifinterface.media.ExifInterface
import app.worthabite.data.apiCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class VegChoice { Veg, NonVeg, Unsure }

/** "Worth a bite?": your verdict when you post, which counts as your vote on the dish. */
enum class Verdict(val api: String) { Loved("loved"), Pass("pass") }

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
    val verdict: Verdict? = null,
    /** "camera" (taken just now in the app) or "gallery". */
    val photoSource: String? = null,
    /** When the photo was taken, from the photo itself (ISO time), if it says. */
    val takenAt: String? = null,
    /** Dishes already at the chosen place with a similar name ("Did you mean…?"). */
    val similarDishes: List<SimilarDish> = emptyList(),
    /** Places already near here with a similar name, before adding a new one. */
    val similarPlaces: List<SimilarPlace> = emptyList(),
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

    fun setPhoto(uri: Uri?) = _state.update { it.copy(photo = uri, error = null, photoSource = null, takenAt = null) }

    /**
     * A photo from the camera (taken now) or the gallery (when it was taken comes from the
     * photo's own details). With your location, that's how a post becomes a verified visit.
     */
    fun setPhoto(context: Context, uri: Uri?, source: String) {
        _state.update { it.copy(photo = uri, error = null, photoSource = uri?.let { source }, takenAt = null) }
        if (uri == null) return
        if (source == "camera") {
            _state.update { it.copy(takenAt = java.time.Instant.now().toString()) }
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val taken = runCatching { context.contentResolver.openInputStream(uri)?.use { photoTakenAt(ExifInterface(it)) } }.getOrNull()
            _state.update { if (it.photo == uri) it.copy(takenAt = taken) else it }
        }
    }

    fun setVerdict(v: Verdict?) = _state.update { it.copy(verdict = if (it.verdict == v) null else v) }

    fun setDishName(name: String) {
        _state.update { it.copy(dishName = name.take(60)) }
        checkSimilarDishes()
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

    fun selectPlace(p: PlaceOption?) {
        _state.update { it.copy(selectedPlace = p, addingNewPlace = false, similarPlaces = emptyList()) }
        checkSimilarDishes()
    }

    /** Uses a place that's already on WorthAbite instead of adding it again. */
    fun useSimilarPlace(p: SimilarPlace) = selectPlace(PlaceOption(p.id, p.name, p.locality, p.distanceM))

    /** Takes the existing dish's name, so your photo joins it instead of splitting its votes. */
    fun useSimilarDish(d: SimilarDish) = _state.update {
        it.copy(dishName = d.name, similarDishes = emptyList(), suggestions = emptyList(), categoryId = d.categoryId ?: it.categoryId)
    }

    private var similarDishJob: Job? = null
    private fun checkSimilarDishes() {
        similarDishJob?.cancel()
        val s = _state.value
        val place = s.selectedPlace
        if (place == null || s.dishName.trim().length < 3) {
            _state.update { it.copy(similarDishes = emptyList()) }
            return
        }
        similarDishJob = viewModelScope.launch {
            delay(400)
            val found = runCatching { apiCall { app.api.similarDishes(place.id, s.dishName.trim()) } }.getOrDefault(emptyList())
            _state.update { it.copy(similarDishes = found) }
        }
    }

    private var similarPlaceJob: Job? = null
    private fun checkSimilarPlaces() {
        similarPlaceJob?.cancel()
        val name = _state.value.newPlaceName.trim()
        val loc = app.location.value
        if (!_state.value.addingNewPlace || name.length < 3 || loc == null) {
            _state.update { it.copy(similarPlaces = emptyList()) }
            return
        }
        similarPlaceJob = viewModelScope.launch {
            delay(400)
            val found = runCatching { apiCall { app.api.similarPlaces(name, loc.lat, loc.lng) } }.getOrDefault(emptyList())
            _state.update { it.copy(similarPlaces = found) }
        }
    }

    fun startNewPlace() {
        _state.update { it.copy(addingNewPlace = true, selectedPlace = null, newPlaceName = it.placeQuery, similarDishes = emptyList()) }
        checkSimilarPlaces()
    }

    fun cancelNewPlace() = _state.update { it.copy(addingNewPlace = false) }
    fun setNewPlaceName(v: String) {
        _state.update { it.copy(newPlaceName = v.take(80)) }
        checkSimilarPlaces()
    }
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
                    s.verdict?.let { put("verdict", it.api) }
                    s.photoSource?.let { put("capture", it) }
                    s.takenAt?.let { put("takenAt", it) }
                    // Where the phone is now: near the place, that's a verified visit.
                    app.location.value?.let {
                        put("lat", it.lat.toString())
                        put("lng", it.lng.toString())
                    }
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

/**
 * When a photo was taken, from its EXIF details ("2026:10:18 13:05:22", in the phone's
 * time zone unless the photo says otherwise), as an ISO time; null if it doesn't say.
 */
internal fun photoTakenAt(exif: ExifInterface): String? {
    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
    val local = runCatching {
        java.time.LocalDateTime.parse(raw.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
    }.getOrNull() ?: return null
    val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        ?.let { runCatching { java.time.ZoneOffset.of(it.trim()) }.getOrNull() }
    val zoned = if (offset != null) local.atOffset(offset).toInstant() else local.atZone(java.time.ZoneId.systemDefault()).toInstant()
    return zoned.toString()
}
