package app.foodfeed.ui.map

import android.content.Context
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.RatingPill
import app.foodfeed.data.Rating
import app.foodfeed.ui.theme.Ink
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
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

data class MapUiState(val loading: Boolean = true, val places: List<MapPlace> = emptyList(), val showAll: Boolean = false, val error: String? = null)

class MapViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.city, app.contentVersion) { c, _ -> c }.collect { if (it != null) load() }
        }
    }

    fun toggleAll() {
        _state.update { it.copy(showAll = !it.showAll) }
        load()
    }

    fun load() {
        val city = app.city.value ?: return
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.map(city.id, _state.value.showAll) }
                _state.update { it.copy(loading = false, places = r.places, error = null) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

private const val SOURCE = "ff-places"
private const val ME = "ff-me"

/**
 * The in-app map, Swiggy style: our own cream-and-saffron map (no shop or restaurant icons
 * from the map data, only FoodFeed's places), a saffron pin per place sized by how many
 * dishes people posted there, and a strip of cards along the bottom. Swipe the cards and
 * the map glides to each place; tap a pin and the strip jumps to its card.
 */
@Composable
fun FoodMap(vm: MapViewModel, location: Coords?, onOpenDish: (String) -> Unit, onOpenPlace: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val places = state.places
    var selected by remember { mutableStateOf<String?>(null) }
    val strip = rememberLazyListState()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val onPinTap by rememberUpdatedState<(String) -> Unit> { id -> selected = id }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.setStyle(Style.Builder().fromJson(styleJson(context, dark))) { style ->
                    addLayers(style)
                    styleReady = true
                }
                m.addOnMapClickListener { point ->
                    val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(point), "ff-pins", "ff-quiet")
                    hit.firstOrNull()?.getStringProperty("id")?.let { onPinTap(it); true } ?: false
                }
                map = m
            }
        }
    }
    MapLifecycle(mapView)

    // Pins follow the data; the camera fits the city's places (or you) the first time.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(map, styleReady, places, location) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        m.style?.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(places.map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                    addStringProperty("id", p.id)
                    addNumberProperty("dishes", p.dishCount)
                }
            }),
        )
        m.style?.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(location?.let { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) })),
        )
        if (!framed && places.isNotEmpty()) {
            framed = true
            val pts = places.filter { it.dishCount > 0 }.ifEmpty { places }
            if (pts.size == 1) {
                m.cameraPosition = CameraPosition.Builder().target(LatLng(pts[0].lat, pts[0].lng)).zoom(14.0).build()
            } else {
                val b = LatLngBounds.Builder().apply { pts.forEach { include(LatLng(it.lat, it.lng)) } }.build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 120, 160, 120, 420))
            }
        } else if (!framed && location != null) {
            m.cameraPosition = CameraPosition.Builder().target(LatLng(location.lat, location.lng)).zoom(13.0).build()
        }
    }

    // Selection: highlight the pin, glide the camera, bring its card into view.
    val withDishes = places.filter { it.dishCount > 0 }
    LaunchedEffect(selected, styleReady) {
        val m = map ?: return@LaunchedEffect
        val id = selected ?: return@LaunchedEffect
        (m.style?.getLayer("ff-selected") as? CircleLayer)?.setFilter(Expression.eq(Expression.get("id"), Expression.literal(id)))
        places.firstOrNull { it.id == id }?.let { p ->
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lng), maxOf(m.cameraPosition.zoom, 14.0)), 450)
        }
        val i = withDishes.indexOfFirst { it.id == id }
        if (i >= 0 && strip.firstVisibleItemIndex != i) strip.animateScrollToItem(i)
    }
    // Swiping the strip selects the card that settles in front.
    LaunchedEffect(strip, withDishes) {
        snapshotFlow { strip.isScrollInProgress to strip.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { (scrolling, i) -> if (!scrolling) withDishes.getOrNull(i)?.let { selected = it.id } }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)))

        Row(Modifier.align(Alignment.TopStart).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapChip(if (state.showAll) "All food places" else "Places with dishes", AppIcons.Map) { vm.toggleAll() }
        }
        Text(
            "© OpenMapTiles © OpenStreetMap",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 16.dp),
        )

        AnimatedVisibility(
            visible = withDishes.isNotEmpty(),
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
                    PlaceCard(
                        p, selected == p.id, app.mediaUrl(p.topDish?.thumbUrl),
                        onClick = { p.topDish?.let { onOpenDish(it.dishId) } ?: onOpenPlace(p.id) },
                        onPlace = { onOpenPlace(p.id) },
                    )
                }
            }
        }
        if (!state.loading && places.isEmpty()) {
            Text(
                "No places on FoodFeed here yet",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MapChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, 0.94f)
            .shadow(6.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PlaceCard(p: MapPlace, selected: Boolean, thumb: String?, onClick: () -> Unit, onPlace: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .width(300.dp)
            .pressScale(interaction, 0.97f)
            .shadow(if (selected) 14.dp else 6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DishImage(thumb, null, p.topDish?.name, Modifier.size(84.dp).clip(RoundedCornerShape(18.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onPlace),
            )
            p.topDish?.let { t ->
                Text("Best here: ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RatingPill(Rating(loves = t.loves, passes = t.percent?.let { passesFrom(t.loves, it) } ?: 0, percent = t.percent))
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

private fun styleJson(context: Context, dark: Boolean): String =
    context.assets.open(if (dark) "map/dark.json" else "map/light.json").bufferedReader().use { it.readText() }

/** Saffron pins sized by dish count, a quieter dot for places nobody has posted yet, and you. */
private fun addLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE))
    style.addSource(GeoJsonSource(ME))
    val hasDishes = Expression.gt(Expression.get("dishes"), Expression.literal(0))
    style.addLayer(
        CircleLayer("ff-quiet", SOURCE).withProperties(
            PropertyFactory.circleRadius(4f),
            PropertyFactory.circleColor("#B49A86"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(1.5f),
        ).withFilter(Expression.not(hasDishes)),
    )
    style.addLayer(
        CircleLayer("ff-selected", SOURCE).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(Expression.linear(), Expression.get("dishes"), Expression.stop(1, 20f), Expression.stop(10, 28f)),
            ),
            PropertyFactory.circleColor("#22140D"),
            PropertyFactory.circleOpacity(0.18f),
        ).withFilter(Expression.eq(Expression.get("id"), Expression.literal(""))),
    )
    style.addLayer(
        CircleLayer("ff-pins", SOURCE).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(Expression.linear(), Expression.get("dishes"), Expression.stop(1, 11f), Expression.stop(10, 18f)),
            ),
            PropertyFactory.circleColor("#FF7A1A"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ).withFilter(hasDishes),
    )
    style.addLayer(
        SymbolLayer("ff-counts", SOURCE).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("dishes"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#22140D"),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ).withFilter(hasDishes),
    )
    style.addLayer(
        CircleLayer("ff-me", ME).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
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
