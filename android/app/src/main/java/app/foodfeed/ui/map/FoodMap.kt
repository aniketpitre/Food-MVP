package app.foodfeed.ui.map

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
import app.foodfeed.ui.components.takeMeThere
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
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
fun FoodMap(vm: MapViewModel, location: Coords?, onOpenDish: (String) -> Unit, onOpenPlace: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val places = state.places
    var selected by remember { mutableStateOf<String?>(null) }
    val strip = rememberLazyListState()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
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
                m.setStyle(Style.Builder().fromJson(styleJson(context, dark))) { style ->
                    addLayers(style)
                    styleReady = true
                    FoodMapProbe.styleReady = true
                }
                m.addOnMapClickListener { point ->
                    val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(point), *PIN_LAYERS)
                    // A tap on empty map closes the card.
                    onPinTap(hit.firstOrNull()?.getStringProperty("id"))
                    true
                }
                map = m
                FoodMapProbe.map = m
            }
        }
    }
    MapLifecycle(mapView)
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
    LaunchedEffect(map, styleReady, places, location) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = m.style ?: return@LaunchedEffect
        FoodMapProbe.places = places
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
        style.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(location?.let { Feature.fromGeometry(Point.fromLngLat(it.lng, it.lat)) })),
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
        } else if (!framed && location != null) {
            m.cameraPosition = CameraPosition.Builder().target(LatLng(location.lat, location.lng)).zoom(13.0).build()
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
    Box(modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).testTag("food_map"))

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
            onTakeMeThere = { p -> takeMeThere(context, p.lat, p.lng, p.name) },
            onOpenDish = onOpenDish,
            onOpenPlace = onOpenPlace,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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
        MapPopup(p, distanceM, photoUrl, onDismiss, { onTakeMeThere(p) }, onOpenDish, onOpenPlace)
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
                )
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
            PrimaryButton("Take me there", onTakeMeThere, Modifier.weight(1f), leading = AppIcons.Directions)
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
            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
