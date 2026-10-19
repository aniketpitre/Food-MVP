package app.worthabite.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.worthabite.BuildConfig
import app.worthabite.data.Coords
import app.worthabite.data.MapPlace
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng as GLatLng
import com.google.android.gms.maps.model.LatLngBounds as GLatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * Whether the maps draw with Google Maps: the app was built with a Maps key (the
 * MAPS_API_KEY secret) and the phone has Google Play services. Otherwise MapLibre.
 */
fun googleMapsUsable(context: Context): Boolean =
    BuildConfig.GOOGLE_MAPS && !GoogleMapsHealth.failed && runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

/**
 * Set when Google's map didn't draw (a key Google refuses, say): from then on, until the app
 * restarts, every map uses MapLibre straight away instead of showing an empty Google map.
 */
object GoogleMapsHealth {
    @Volatile var failed: Boolean = false
}

/** How long Google's map gets to draw its first tiles before we switch to MapLibre. */
private const val GOOGLE_LOAD_TIMEOUT_MS = 10_000L

/**
 * Our own cream-and-espresso look on Google's map (espresso and ember at night), matching
 * the open map: no business icons (WorthAbite's food logos are the places), landmarks named
 * without icons. The styles live in assets/map/google_*.json.
 */
internal fun googleMapStyle(context: Context, dark: Boolean): MapStyleOptions =
    MapStyleOptions(context.assets.open(if (dark) "map/google_dark.json" else "map/google_light.json").bufferedReader().use { it.readText() })

/** A Google [MapView] for Compose: created once, given the screen's lifecycle. */
@Composable
internal fun rememberGoogleMapView(dark: Boolean, onFailed: () -> Unit, onReady: (GoogleMap) -> Unit): MapView {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    val failed by rememberUpdatedState(onFailed)
    var loaded by remember { mutableStateOf(false) }
    val view = remember {
        MapsInitializer.initialize(context.applicationContext)
        MapView(context, GoogleMapOptions().mapToolbarEnabled(false).compassEnabled(false).rotateGesturesEnabled(false).tiltGesturesEnabled(false)).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.uiSettings.isMapToolbarEnabled = false
                m.uiSettings.isIndoorLevelPickerEnabled = false
                m.isBuildingsEnabled = true
                runCatching { m.setMapStyle(googleMapStyle(context, dark)) }.onFailure { android.util.Log.w("GoogleMaps", "map style not applied", it) }
                m.setOnMapLoadedCallback { loaded = true }
                ready(m)
            }
        }
    }
    // A refused key (or Google unreachable) leaves the map empty with no error to catch:
    // if no tiles have drawn in time, hand over to MapLibre.
    LaunchedEffect(view) {
        kotlinx.coroutines.delay(GOOGLE_LOAD_TIMEOUT_MS)
        if (!loaded) {
            android.util.Log.w("GoogleMaps", "Google map didn't load; using MapLibre")
            GoogleMapsHealth.failed = true
            failed()
        }
    }
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
    return view
}

/** Our blue "you are here" dot, drawn once. */
internal fun meDotBitmap(context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (22 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = 0xFF1E88E5.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 3 * d, p)
    return bmp
}

/** The same logo, smaller and a little faded: places with no dishes yet. */
private fun quietBitmap(full: Bitmap): Bitmap =
    Bitmap.createScaledBitmap(full, (full.width * 0.72f).toInt().coerceAtLeast(1), (full.height * 0.72f).toInt().coerceAtLeast(1), true)

/** Places with no dishes yet only show once you zoom in to a neighbourhood. */
private const val QUIET_FROM_ZOOM = 13.5f

/**
 * Google Maps under [FoodMap]: the same food-logo pins (places with dishes always, the rest
 * from neighbourhood zoom), the drive as an ember line, you as a blue dot with your accuracy
 * as a soft circle. A tap on a logo selects it; a tap elsewhere clears; a long press plans a
 * drive there.
 */
@Composable
internal fun GoogleFoodMapView(
    places: List<MapPlace>,
    location: Coords?,
    route: PlannedRoute?,
    selected: String?,
    onSelect: (String?) -> Unit,
    onLongPress: (Double, Double) -> Unit,
    camera: MapCamera,
    popupLift: Float,
    dark: Boolean,
    modifier: Modifier,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    val select by rememberUpdatedState(onSelect)
    val longPress by rememberUpdatedState(onLongPress)
    val markers = remember { HashMap<String, Marker>() }
    val quiet = remember { HashSet<String>() }
    val icons = remember { HashMap<String, BitmapDescriptor>() }
    fun icon(emoji: String, on: Boolean, small: Boolean): BitmapDescriptor = icons.getOrPut("$emoji|$on|$small") {
        val full = logoBitmap(context, emoji, on)
        BitmapDescriptorFactory.fromBitmap(if (small) quietBitmap(full) else full)
    }
    var zoom by remember { mutableStateOf(12f) }
    val view = rememberGoogleMapView(dark, onFailed) { m ->
        m.setOnMarkerClickListener { mk ->
            (mk.tag as? String)?.let { select(it) }
            true
        }
        m.setOnMapClickListener { select(null) }
        m.setOnMapLongClickListener { p -> longPress(p.latitude, p.longitude) }
        m.setOnCameraIdleListener { zoom = m.cameraPosition.zoom }
        camera.target = { m.cameraPosition.target.let { Coords(it.latitude, it.longitude) } }
        camera.animateTo = { lat, lng, z -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(GLatLng(lat, lng), z.toFloat()), 600, null) }
        map = m
    }

    // Pins follow the data; the camera fits the city's places (or you) the first time.
    var framed by remember { mutableStateOf(false) }
    LaunchedEffect(map, places) {
        val m = map ?: return@LaunchedEffect
        val ids = places.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { id -> markers.remove(id)?.remove(); quiet.remove(id) }
        for (p in places) {
            if (p.id in markers) continue
            val small = p.dishCount == 0
            val mk = m.addMarker(
                MarkerOptions()
                    .position(GLatLng(p.lat, p.lng))
                    .icon(icon(p.emoji, on = p.id == selected, small = small))
                    .anchor(0.5f, 1f)
                    .alpha(if (small) 0.9f else 1f)
                    .zIndex(if (small) 0f else 1f + (p.lovers + p.dishCount) / 1000f)
                    .visible(!small || zoom >= QUIET_FROM_ZOOM),
            ) ?: continue
            mk.tag = p.id
            markers[p.id] = mk
            if (small) quiet.add(p.id)
        }
        if (!framed && places.isNotEmpty()) {
            framed = true
            val pts = places.filter { it.dishCount > 0 }.ifEmpty { places }
            if (pts.size == 1) {
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(GLatLng(pts[0].lat, pts[0].lng), 14f))
            } else {
                val b = GLatLngBounds.Builder().apply { pts.forEach { include(GLatLng(it.lat, it.lng)) } }.build()
                // The map may not be laid out yet on the very first frame; then aim at the middle.
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 140)) }
                    .onFailure { m.moveCamera(CameraUpdateFactory.newLatLngZoom(b.center, 12f)) }
            }
        }
    }
    // Places with no dishes come in from neighbourhood zoom, so the city view stays readable.
    LaunchedEffect(zoom) {
        val show = zoom >= QUIET_FROM_ZOOM
        quiet.forEach { id -> markers[id]?.takeIf { it.isVisible != show && it.tag != selected }?.isVisible = show }
    }

    // Selection: the chosen logo turns ember and the camera glides it above the card.
    var lastSelected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(map, selected) {
        val m = map ?: return@LaunchedEffect
        lastSelected?.let { old ->
            places.firstOrNull { it.id == old }?.let { p ->
                markers[old]?.setIcon(icon(p.emoji, on = false, small = p.dishCount == 0))
                if (p.dishCount == 0) markers[old]?.isVisible = zoom >= QUIET_FROM_ZOOM
            }
        }
        lastSelected = selected
        val id = selected
        if (id == null) {
            m.setPadding(0, 0, 0, 0)
            return@LaunchedEffect
        }
        val p = places.firstOrNull { it.id == id } ?: return@LaunchedEffect
        markers[id]?.apply {
            setIcon(icon(p.emoji, on = true, small = false))
            isVisible = true
            zIndex = 10f
        }
        m.setPadding(0, 0, 0, popupLift.toInt())
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(GLatLng(p.lat, p.lng), maxOf(m.cameraPosition.zoom, 14.5f)), 500, null)
    }

    // The drive: an ember line under the pins, framed whole when it's planned.
    var line by remember { mutableStateOf<Polyline?>(null) }
    LaunchedEffect(map, route) {
        val m = map ?: return@LaunchedEffect
        line?.remove()
        line = null
        val pts = route?.line.orEmpty().map { GLatLng(it.latitude, it.longitude) }
        if (pts.size >= 2) {
            line = m.addPolyline(PolylineOptions().addAll(pts).color(0xFFE2531E.toInt()).width(14f).zIndex(0.5f))
            val b = GLatLngBounds.Builder().apply { pts.forEach { include(it) } }.build()
            runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 160), 700, null) }
        }
    }

    // You: a blue dot, with the phone's own error estimate as a soft circle around it.
    var me by remember { mutableStateOf<Marker?>(null) }
    var halo by remember { mutableStateOf<Circle?>(null) }
    LaunchedEffect(map, location) {
        val m = map ?: return@LaunchedEffect
        if (location == null) {
            me?.remove(); me = null
            halo?.remove(); halo = null
            return@LaunchedEffect
        }
        val at = GLatLng(location.lat, location.lng)
        val dot = me ?: m.addMarker(
            MarkerOptions().position(at).icon(BitmapDescriptorFactory.fromBitmap(meDotBitmap(context))).anchor(0.5f, 0.5f).zIndex(20f).flat(true),
        )?.also { me = it }
        dot?.position = at
        val acc = (location.accuracyM ?: 0f).toDouble()
        val ring = halo ?: m.addCircle(
            CircleOptions().center(at).radius(acc).fillColor(0x261E88E5).strokeColor(0x591E88E5).strokeWidth(2f),
        ).also { halo = it }
        ring.center = at
        ring.radius = acc
        if (!framed && places.isEmpty()) m.moveCamera(CameraUpdateFactory.newLatLngZoom(at, 14f))
    }

    AndroidView({ view }, modifier)
}
