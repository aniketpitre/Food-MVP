package app.foodfeed.ui.map

import app.foodfeed.ui.components.DemoTag
import app.foodfeed.ui.components.rememberTakeMeThere
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import app.foodfeed.data.DeviceLocation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.Coords
import app.foodfeed.data.MapPlace
import app.foodfeed.data.MapTopDish
import app.foodfeed.data.Rating
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.components.RatingPill
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.theme.LocalLiteMode
import app.foodfeed.ui.theme.pressScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class MapUiState(
    val loading: Boolean = true,
    val places: List<MapPlace> = emptyList(),
    val showAll: Boolean = false,
    val error: String? = null,
    /** "all", or only the places your friends ("friends") or taste matches ("taste") loved. */
    val filter: String = "all",
    /** Food stops along a drive, when one is planned. */
    val route: PlannedRoute? = null,
    val routing: Boolean = false,
)

/** A drive and the best dish at each place along it, in the order you reach them. */
data class PlannedRoute(
    val to: String,
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val line: List<LatLng>,
    val distanceM: Int?,
    val roadRoute: Boolean,
    val stops: List<MapPlace>,
)

class MapViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.city, app.contentVersion) { c, _ -> c }.collect { if (it != null) load() }
        }
    }

    private var userChose = false

    fun toggleAll() {
        userChose = true
        _state.update { it.copy(showAll = !it.showAll) }
        load()
    }

    /** People filters need an account; returns false (and changes nothing) for a guest. */
    fun setFilter(filter: String): Boolean {
        if (filter != "all" && app.session.value == null) return false
        if (filter == _state.value.filter) return true
        _state.update { it.copy(filter = filter, loading = true) }
        load()
        return true
    }

    /** Plans a drive from [from] to a destination and finds the food stops along it. */
    fun routeTo(from: Coords, toLat: Double, toLng: Double, label: String) {
        _state.update { it.copy(routing = true) }
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.routeStops(from.lat, from.lng, toLat, toLng) }
                val stops = r.stops.map { s ->
                    val c = s.card
                    MapPlace(
                        id = c.restaurant.id, name = c.restaurant.name, locality = c.restaurant.locality,
                        lat = c.restaurant.lat, lng = c.restaurant.lng, dishCount = 1, lovers = c.rating.loves,
                        demo = c.restaurant.isDemo, emoji = "🍽️",
                        topDish = MapTopDish(
                            dishId = c.id, name = c.name, loves = c.rating.loves, percent = c.rating.percent,
                            thumbUrl = c.cover?.thumbUrl, priceInr = c.priceInr, isVeg = c.isVeg,
                        ),
                    )
                }
                val line = r.route.coordinates.mapNotNull { pt -> if (pt.size >= 2) LatLng(pt[1], pt[0]) else null }
                _state.update {
                    it.copy(
                        routing = false,
                        route = PlannedRoute(label, from.lat, from.lng, toLat, toLng, line, r.route.distanceM, r.route.source == "osrm", stops),
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(routing = false, error = e.message) }
            }
        }
    }

    fun clearRoute() = _state.update { it.copy(route = null) }

    fun load() {
        val city = app.city.value ?: return
        viewModelScope.launch {
            try {
                val filter = _state.value.filter
                val r = apiCall { app.api.map(city.id, _state.value.showAll, filter) }
                if (filter != _state.value.filter) return@launch
                // Few real places with dishes yet? Show every real food place instead, so the
                // map is never a handful of pins (or empty) while a city is getting started.
                if (filter == "all" && !userChose && !_state.value.showAll && r.places.count { it.dishCount > 0 && !it.demo } < 3) {
                    _state.update { it.copy(showAll = true) }
                    load()
                    return@launch
                }
                _state.update { it.copy(loading = false, places = r.places, error = null) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

private const val SOURCE = "ff-places"
private const val ME = "ff-me"
private const val ROUTE = "ff-route"
private val PIN_LAYERS = arrayOf("ff-selected", "ff-pins", "ff-quiet")

/**
 * Lets the on-device test find pins (they are drawn by the map, not by Compose) and check
 * what the map shows. Holds nothing once the map screen is gone.
 */
@VisibleForTesting
object FoodMapProbe {
    @Volatile var map: MapLibreMap? = null
    @Volatile var view: MapView? = null
    @Volatile var places: List<MapPlace> = emptyList()
    @Volatile var styleReady: Boolean = false

    /** Where a place's pin is on screen, in screen pixels, or null if not ready. */
    fun pinOnScreen(id: String): Pair<Float, Float>? {
        val m = map ?: return null
        val v = view ?: return null
        val p = places.firstOrNull { it.id == id } ?: return null
        val pt = m.projection.toScreenLocation(LatLng(p.lat, p.lng))
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        return (loc[0] + pt.x) to (loc[1] + pt.y)
    }
}

/**
 * The in-app map, Swiggy style: our own cream-and-saffron map with no shop icons from the
 * map data, and a food logo per FoodFeed place (its best dish's category, or a guess from
 * the cuisine). Tap a logo and a small card flips up in 3D with the place, its best dish,
 * the people's rating and Take me there. The strip of cards along the bottom does the same.
 */
@Composable
fun FoodMap(
    vm: MapViewModel,
    location: Coords?,
    onOpenDish: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNeedSignIn: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val route = state.route
    val places = route?.stops ?: state.places
    var selected by remember { mutableStateOf<String?>(null) }
    var planning by remember { mutableStateOf(false) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // A long press on the map drops a pin there and plans a drive to it.
    val onLongPress by rememberUpdatedState<(LatLng) -> Unit> { point ->
        val from = location ?: map?.cameraPosition?.target?.let { Coords(it.latitude, it.longitude) }
        if (from != null) {
            selected = null
            vm.routeTo(from, point.latitude, point.longitude, "the dropped pin")
        }
    }
    val strip = rememberLazyListState()
    var styleReady by remember { mutableStateOf(false) }
    val onPinTap by rememberUpdatedState<(String?) -> Unit> { id -> selected = id }

    val mapView = remember {
        MapLibre.getInstance(context)
        // TextureView mode: the map draws inside the normal view tree, so it clips to our
        // rounded corners, fades with Compose animations and works on every GPU.
        MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = false
                // Our layers go on once the style is in. The style callback alone has been seen
                // to go missing on slow networks, so the style-finished event and a short
                // poll below can do the same job; whichever comes first wins.
                val ready: (Style) -> Unit = { style ->
                    if (!styleReady && style.isFullyLoaded) {
                        runCatching { addLayers(style) }.onFailure { android.util.Log.e("FoodMap", "map layers failed", it) }
                        styleReady = true
                        FoodMapProbe.styleReady = true
                        android.util.Log.d("FoodMap", "style ready (event)")
                    }
                }
                addOnDidFinishLoadingStyleListener { m.style?.let(ready) }
                m.setStyle(Style.Builder().fromJson(styleJson(context, dark))) { style -> ready(style) }
                m.addOnMapLongClickListener { point ->
                    onLongPress(point)
                    true
                }
                m.addOnMapClickListener { point ->
                    val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(point), *PIN_LAYERS)
                    // A tap on empty map closes the card.
                    onPinTap(hit.firstOrNull()?.getStringProperty("id"))
                    true
                }
                map = m
                FoodMapProbe.map = m
                android.util.Log.d("FoodMap", "map object ready")
            }
        }
    }
    MapLifecycle(mapView)
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        repeat(120) {
            if (styleReady) return@LaunchedEffect
            m.style?.takeIf { it.isFullyLoaded }?.let { style ->
                runCatching { addLayers(style) }.onFailure { android.util.Log.e("FoodMap", "map layers failed", it) }
                styleReady = true
                FoodMapProbe.styleReady = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(500)
        }
        android.util.Log.w("FoodMap", "map style never finished loading")
    }
    DisposableEffect(mapView) {
        FoodMapProbe.view = mapView
        onDispose {
            FoodMapProbe.map = null
            FoodMapProbe.view = null
            FoodMapProbe.styleReady = false
        }
    }

    // Logos follow the data; the camera fits the city's places (or you) the first time.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(map, styleReady, places) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        FoodMapProbe.places = places
        android.util.Log.d("FoodMap", "pins set: ${places.size} places")
        // One bitmap per food logo (a dozen or so), drawn once and kept by the map.
        places.map { it.emoji }.distinct().forEach { emoji ->
            if (style.getImage(logoId(emoji, false)) == null) {
                style.addImage(logoId(emoji, false), logoBitmap(context, emoji, selected = false))
                style.addImage(logoId(emoji, true), logoBitmap(context, emoji, selected = true))
            }
        }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(places.map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                    addStringProperty("id", p.id)
                    addStringProperty("logo", logoId(p.emoji, false))
                    addStringProperty("logoOn", logoId(p.emoji, true))
                    addNumberProperty("dishes", p.dishCount)
                    addNumberProperty("rank", -(p.lovers + p.dishCount))
                }
            }),
        )
        if (!framed && places.isNotEmpty()) {
            framed = true
            val pts = places.filter { it.dishCount > 0 }.ifEmpty { places }
            if (pts.size == 1) {
                m.cameraPosition = CameraPosition.Builder().target(LatLng(pts[0].lat, pts[0].lng)).zoom(14.0).build()
            } else {
                val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lng)) } }.build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 120, 200, 120, 460))
            }
        }
    }

    // The drive: a saffron line under the pins, framed whole when it's planned.
    LaunchedEffect(map, styleReady, route) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val line = route?.line.orEmpty()
        m.style?.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                if (line.size < 2) emptyList()
                else listOf(Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(line.map { Point.fromLngLat(it.longitude, it.latitude) }))),
            ),
        )
        if (line.size >= 2) {
            val b = LatLngBounds.Builder().apply { line.forEach { include(it) } }.build()
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 100, 240, 100, 420), 700)
        }
    }

    // You: a blue dot with a soft halo the size of the phone's own error estimate. It moves
    // on its own as new readings come in; only this tiny source is updated.
    LaunchedEffect(map, styleReady, location) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        m.style?.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(location?.let {
                Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)).apply { addNumberProperty("acc", (it.accuracyM ?: 0f).toDouble()) }
            })),
        )
        if (!framed && places.isEmpty() && location != null) {
            m.cameraPosition = CameraPosition.Builder().target(LatLng(location.lat, location.lng)).zoom(14.0).build()
        }
    }

    // While the map is on screen: a precise fix first, then follow the phone.
    val scope = rememberCoroutineScope()
    var askedPrecise by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun startFollowing(): () -> Unit = DeviceLocation.follow(context) { c ->
        val old = app.location.value
        // Ignore a vaguer reading when we already have a sharper one from moments ago.
        if (old?.accuracyM == null || c.accuracyM == null || c.accuracyM <= old.accuracyM * 2) app.location.value = c
    }
    DisposableEffect(lifecycle, askedPrecise) {
        var stop: () -> Unit = {}
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    stop()
                    stop = startFollowing()
                    scope.launch { DeviceLocation.current(context, precise = true)?.let { app.location.value = it } }
                }
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    val askPrecise = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        askedPrecise = !askedPrecise
        scope.launch {
            val c = DeviceLocation.current(context, precise = true)
            if (c != null) {
                app.location.value = c
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lng), 16.0), 600)
            }
        }
    }
    val locateMe: () -> Unit = {
        if (!DeviceLocation.hasPrecise(context)) {
            askPrecise.launch(DeviceLocation.PERMISSIONS)
        } else {
            location?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 16.0), 600) }
            scope.launch {
                val c = DeviceLocation.current(context, precise = true)
                if (c != null) {
                    app.location.value = c
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lng), 16.0), 600)
                }
            }
        }
    }

    // Selection: lift the logo, glide the camera so the pin sits above the card.
    val withDishes = places.filter { it.dishCount > 0 }
    val popupLift = with(density) { 300.dp.toPx() }.toDouble()
    LaunchedEffect(selected, styleReady) {
        val m = map ?: return@LaunchedEffect
        (m.style?.getLayer("ff-selected") as? SymbolLayer)?.setFilter(Expression.eq(Expression.get("id"), Expression.literal(selected ?: "")))
        val id = selected ?: return@LaunchedEffect
        places.firstOrNull { it.id == id }?.let { p ->
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(p.lat, p.lng))
                        .zoom(maxOf(m.cameraPosition.zoom, 14.5))
                        .padding(0.0, 0.0, 0.0, popupLift)
                        .build(),
                ),
                500,
            )
        }
    }
    // Swiping the strip selects the card that settles in front.
    LaunchedEffect(strip, withDishes) {
        snapshotFlow { strip.isScrollInProgress to strip.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { (scrolling, i) ->
                if (!scrolling && strip.firstVisibleItemScrollOffset > 0) withDishes.getOrNull(i)?.let { selected = it.id }
            }
    }

    val current = places.firstOrNull { it.id == selected }
    val takeMeThere = rememberTakeMeThere()
    Box(modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).testTag("food_map"))

        if (route == null) {
            Row(
                Modifier.align(Alignment.TopStart).padding(top = 14.dp)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.filter == "all") {
                    MapChip(if (state.showAll) "All food places" else "Places with dishes", AppIcons.Map) { vm.toggleAll() }
                }
                for ((key, label) in listOf("friends" to "Friends' picks", "taste" to "Taste match")) {
                    MapChip(label, AppIcons.Heart, on = state.filter == key, tag = "map_filter_$key") {
                        val next = if (state.filter == key) "all" else key
                        if (!vm.setFilter(next)) onNeedSignIn() else selected = null
                    }
                }
            }
            MapChip(
                "On the way", AppIcons.Directions, tag = "map_route",
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 122.dp, end = 14.dp),
            ) { planning = true }
        } else {
            RouteBanner(route, state.routing, Modifier.align(Alignment.TopStart).padding(14.dp).padding(end = 60.dp)) {
                selected = null
                vm.clearRoute()
            }
        }
        LocateButton(
            onClick = locateMe,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 14.dp),
        )
        Text(
            "© OpenMapTiles © OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 18.dp),
        )

        AnimatedVisibility(
            visible = withDishes.isNotEmpty() && current == null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LazyRow(
                state = strip,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(strip),
            ) {
                itemsIndexed(withDishes, key = { _, p -> p.id }) { _, p ->
                    PlaceCard(p, app.mediaUrl(p.topDish?.thumbUrl ?: p.photoUrl)) { selected = p.id }
                }
            }
        }

        MapPopupHost(
            place = current,
            distanceM = current?.let { p -> location?.let { metres(it.lat, it.lng, p.lat, p.lng) } },
            photoUrl = current?.let { app.mediaUrl(it.topDish?.thumbUrl ?: it.photoUrl) },
            onDismiss = { selected = null },
            onTakeMeThere = { p ->
                if (route != null) openDriveWithStop(context, route, p) else takeMeThere.go(p.id, p.lat, p.lng, p.name, p.demo)
            },
            onRoute = route != null,
            onOpenDish = onOpenDish,
            onOpenPlace = onOpenPlace,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (planning) {
            RoutePlanner(
                onDismiss = { planning = false },
                onPick = { city ->
                    planning = false
                    val from = location ?: map?.cameraPosition?.target?.let { Coords(it.latitude, it.longitude) }
                    if (from != null) vm.routeTo(from, city.lat, city.lng, city.name)
                },
            )
        }

        if (!state.loading && places.isEmpty()) {
            Text(
                when {
                    route != null -> "No dishes on FoodFeed along this drive yet"
                    state.filter == "friends" -> "No picks from people you follow here yet"
                    state.filter == "taste" -> "No picks from your taste matches here yet"
                    else -> "No places on FoodFeed here yet"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Shows [MapPopup] for the selected place with a 3D entrance: the card swings up from
 * lying flat (rotationX) with a soft spring, like a menu card being stood up on the table.
 * Changing place flips the card over to the new one; dragging it down puts it away. Lite
 * mode uses a plain fade and slide. Everything runs in the graphics layer, so it costs no
 * recomposition per frame.
 */
@Composable
fun MapPopupHost(
    place: MapPlace?,
    distanceM: Int?,
    photoUrl: String?,
    onDismiss: () -> Unit,
    onTakeMeThere: (MapPlace) -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRoute: Boolean = false,
) {
    val lite = LocalLiteMode.current
    // Keep showing the last place while the card swings away.
    var shown by remember { mutableStateOf(place) }
    val tilt = remember { Animatable(if (place != null) 0f else 80f) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(place?.id) {
        if (place != null) {
            if (shown != null && shown?.id != place.id && !lite) {
                // Flip over to the next place.
                tilt.animateTo(-90f, tween(140))
                shown = place
                tilt.snapTo(90f)
            } else {
                shown = place
                if (tilt.value == 0f) tilt.snapTo(80f)
            }
            drag.snapTo(0f)
            tilt.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
        } else if (shown != null) {
            tilt.animateTo(80f, tween(if (lite) 120 else 200))
            shown = null
        }
    }
    val p = shown ?: return
    Box(
        modifier
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .graphicsLayer {
                val t = tilt.value
                val progress = 1f - (kotlin.math.abs(t) / 90f).coerceIn(0f, 1f)
                if (lite) {
                    alpha = progress
                    translationY = (1f - progress) * 120f + drag.value
                } else {
                    rotationX = t
                    cameraDistance = 14f * density
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    alpha = (progress * 1.6f).coerceIn(0f, 1f)
                    val s = 0.86f + 0.14f * progress
                    scaleX = s
                    scaleY = s
                    translationY = drag.value
                }
            }
            .pointerInput(p.id) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (drag.value > 140f) onDismiss() else drag.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                        }
                    },
                ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
            },
    ) {
        MapPopup(p, distanceM, photoUrl, onDismiss, { onTakeMeThere(p) }, onOpenDish, onOpenPlace, onRoute)
    }
}

/** The card itself: photo with the food logo, place, best dish, rating, hours, and actions. */
@Composable
fun MapPopup(
    p: MapPlace,
    distanceM: Int?,
    photoUrl: String?,
    onDismiss: () -> Unit,
    onTakeMeThere: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    onRoute: Boolean = false,
) {
    val top = p.topDish
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .semantics { contentDescription = "Place card for ${p.name}" }
            .testTag("map_popup")
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box {
                DishImage(photoUrl, null, top?.name ?: p.name, Modifier.size(96.dp).clip(RoundedCornerShape(20.dp)))
                // The pin's food logo, stuck on the photo's corner.
                Text(
                    p.emoji,
                    fontSize = 20.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 8.dp, y = 8.dp)
                        .graphicsLayer { rotationZ = -8f }
                        .shadow(4.dp, CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .padding(6.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // The name opens the place page: its photos, hours and every dish.
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpenPlace(p.id) },
                )
                if (p.demo) DemoTag()
                val line = listOfNotNull(p.cuisine?.replaceFirstChar { it.uppercase() }, p.locality, Format.distance(distanceM)).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (top != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VegMark(top.isVeg)
                        Text(
                            "Best here: ${top.name}" + (Format.price(top.priceInr)?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RatingPill(Rating(loves = top.loves, passes = top.percent?.let { passesFrom(top.loves, it) } ?: 0, percent = top.percent))
                    p.lovedBy?.let { by ->
                        Text(
                            "♥ Loved by @${by.handle}" + (by.match?.let { " · $it% taste match" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("loved_by"),
                        )
                    }
                } else {
                    Text("No dishes posted yet. Be the first!", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(6.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        p.openingHours?.let {
            Text(
                "Hours: ${friendlyHours(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (p.dishCount > 0 || p.lovers > 0) {
            Text(
                listOfNotNull(
                    if (p.dishCount == 1) "1 dish on FoodFeed" else "${p.dishCount} dishes on FoodFeed",
                    if (p.lovers > 0) "loved by ${Format.compact(p.lovers)} people" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (onRoute) "Stop on the way" else "Take me there", onTakeMeThere, Modifier.weight(1f), leading = AppIcons.Directions)
            if (top != null) {
                QuietButton("Dish", { onOpenDish(top.dishId) })
            } else {
                QuietButton("Place", { onOpenPlace(p.id) })
            }
        }
    }
}

/** "Mo-Su 08:00-23:00" → "Mon–Sun 8:00–23:00": OpenStreetMap hours, a little friendlier. */
fun friendlyHours(raw: String): String =
    raw.replace("Mo", "Mon").replace("Tu", "Tue").replace("We", "Wed").replace("Th", "Thu")
        .replace("Fr", "Fri").replace("Sa", "Sat").replace("Su", "Sun").replace("-", "–")
        .replace(Regex("\\b0(\\d):"), "$1:").replace(";", ",")

/** Round "show me where I am" button, like every maps app. */
@Composable
private fun LocateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(46.dp)
            .pressScale(interaction, 0.9f)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Show my location" }
            .testTag("locate_me"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.MyLocation, null, tint = Color(0xFF1E88E5), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MapChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean = false,
    tag: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .pressScale(interaction, 0.94f)
            .shadow(6.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (on) stateDescription = "On" }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = if (on) fg else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Shown during a drive: where to, how far, how many stops, and a close button. */
@Composable
private fun RouteBanner(route: PlannedRoute, loading: Boolean, modifier: Modifier = Modifier, onClose: () -> Unit) {
    Row(
        modifier
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .testTag("route_banner")
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Directions, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 10.dp)) {
            Text("Food on the way to ${route.to}", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    route.distanceM?.let { Format.distance(it) },
                    if (route.stops.size == 1) "1 stop" else "${route.stops.size} stops",
                    if (!route.roadRoute) "straight line" else null,
                    if (loading) "updating…" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.Close,
            contentDescription = "End the drive",
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose).padding(8.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where to? A city by name, or a long press anywhere on the map. */
@Composable
private fun RoutePlanner(onDismiss: () -> Unit, onPick: (app.foodfeed.data.City) -> Unit) {
    val app = LocalApp.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<app.foodfeed.data.City>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        results = runCatching { apiCall { app.api.cities(query.trim(), 8) } }.getOrDefault(emptyList())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Food on the way") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Where are you driving? We'll show the best dish at each place along the road. You can also long-press anywhere on the map.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(40) },
                    placeholder = { Text("A city, like Pune") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("route_to"),
                )
                results.forEach { c ->
                    Text(
                        listOfNotNull(c.name, c.state).joinToString(", "),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(c) }
                            .testTag("route_city_${c.slug}").padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens the phone's maps app for the drive, with [stop] as a stop on the way. */
private fun openDriveWithStop(context: Context, route: PlannedRoute, stop: MapPlace) {
    val uri = android.net.Uri.parse(
        "https://www.google.com/maps/dir/?api=1&travelmode=driving" +
            "&origin=${route.fromLat},${route.fromLng}&destination=${route.toLat},${route.toLng}" +
            "&waypoints=${stop.lat},${stop.lng}",
    )
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun PlaceCard(p: MapPlace, thumb: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .width(280.dp)
            .pressScale(interaction, 0.97f)
            .shadow(6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .testTag("map_card_${p.id}")
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            DishImage(thumb, null, p.topDish?.name, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
            Text(p.emoji, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopStart).offset((-4).dp, (-4).dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.demo) DemoTag()
            }
            p.topDish?.let { t ->
                Text("Best here: ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(p.locality, if (p.dishCount == 1) "1 dish" else "${p.dishCount} dishes").joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Recovers the "not for me" count from loves and the rounded percentage, for the vote total. */
private fun passesFrom(loves: Int, percent: Int): Int =
    if (percent <= 0 || percent >= 100) 0 else Math.round(loves * (100f - percent) / percent)

private fun metres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
    val r = Math.PI / 180
    val x = (lng2 - lng1) * r * cos((lat1 + lat2) / 2 * r)
    val y = (lat2 - lat1) * r
    return (sqrt(x * x + y * y) * 6_371_000).roundToInt()
}

private fun styleJson(context: Context, dark: Boolean): String =
    context.assets.open(if (dark) "map/dark.json" else "map/light.json").bufferedReader().use { it.readText() }

private fun logoId(emoji: String, selected: Boolean) = "logo-${if (selected) "on" else "off"}-$emoji"

/**
 * A food-logo pin: the emoji on a round cream badge with a saffron ring and a little pointer,
 * or on solid saffron when selected. Drawn once per logo at the screen's density.
 */
internal fun logoBitmap(context: Context, emoji: String, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val badge = (if (selected) 46f else 36f) * d
    val pointer = 7f * d
    val pad = 4f * d
    val w = (badge + pad * 2).toInt()
    val h = (badge + pointer + pad * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = w / 2f
    val cy = pad + badge / 2f
    val saffron = 0xFFFF7A1A.toInt()
    val cream = 0xFFFFF7EC.toInt()
    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.FILL
    }
    c.drawOval(RectF(cx - badge * 0.32f, h - pad - 2.5f * d, cx + badge * 0.32f, h - pad + 0.5f * d), shadow)
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) saffron else cream }
    val tip = Path().apply {
        moveTo(cx - pointer, cy + badge / 2f - pointer * 0.6f)
        lineTo(cx, cy + badge / 2f + pointer)
        lineTo(cx + pointer, cy + badge / 2f - pointer * 0.6f)
        close()
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (selected) 0xFFFFFFFF.toInt() else saffron }
    c.drawPath(tip, ring)
    c.drawCircle(cx, cy, badge / 2f, ring)
    c.drawCircle(cx, cy, badge / 2f - 2.5f * d, body)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = badge * 0.5f
        textAlign = Paint.Align.CENTER
    }
    val fm = text.fontMetrics
    c.drawText(emoji, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    return bmp
}

/** Logos for places with dishes always show; quieter, smaller ones for the rest make room. */
private fun addLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE))
    style.addSource(GeoJsonSource(ME))
    style.addSource(GeoJsonSource(ROUTE))
    style.addLayer(
        LineLayer("ff-route-line", ROUTE).withProperties(
            PropertyFactory.lineColor("#FF7A1A"),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    val hasDishes = Expression.gt(Expression.get("dishes"), Expression.literal(0))
    style.addLayer(
        SymbolLayer("ff-quiet", SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("logo")),
            PropertyFactory.iconSize(0.72f),
            PropertyFactory.iconOpacity(0.9f),
            PropertyFactory.iconAnchor("bottom"),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.iconPadding(1f),
        ).withFilter(Expression.not(hasDishes)),
    )
    style.addLayer(
        SymbolLayer("ff-pins", SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("logo")),
            PropertyFactory.iconAnchor("bottom"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.get("rank")),
        ).withFilter(hasDishes),
    )
    // The accuracy halo: the phone's error estimate in metres, shown as a soft circle. At
    // zoom 15 one pixel is about 4.8 m here; every zoom level halves that. Optional: if this
    // layer can't be added, the map still works.
    runCatching {
        style.addLayer(
            CircleLayer("ff-me-halo", ME).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.exponential(2f), Expression.zoom(),
                        Expression.stop(10f, Expression.product(Expression.toNumber(Expression.get("acc")), Expression.literal(1f / 150f))),
                        Expression.stop(20f, Expression.product(Expression.toNumber(Expression.get("acc")), Expression.literal(1f / 0.146f))),
                    ),
                ),
                PropertyFactory.circleColor("#1E88E5"),
                PropertyFactory.circleOpacity(0.15f),
                PropertyFactory.circleStrokeColor("#1E88E5"),
                PropertyFactory.circleStrokeOpacity(0.35f),
                PropertyFactory.circleStrokeWidth(1f),
            ),
        )
    }.onFailure { android.util.Log.w("FoodMap", "accuracy halo skipped", it) }
    style.addLayer(
        CircleLayer("ff-me", ME).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    style.addLayer(
        SymbolLayer("ff-selected", SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("logoOn")),
            PropertyFactory.iconAnchor("bottom"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.eq(Expression.get("id"), Expression.literal(""))),
    )
}

/** Forwards the screen's lifecycle to the MapView (it needs start/resume/pause/stop/destroy). */
@Composable
private fun MapLifecycle(view: MapView) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, view) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }
}
