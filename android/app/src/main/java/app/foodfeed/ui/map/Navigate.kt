package app.foodfeed.ui.map

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.foodfeed.data.Coords
import app.foodfeed.data.DeviceLocation
import app.foodfeed.data.Directions
import app.foodfeed.data.LocationResult
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.components.directionsTo
import app.foodfeed.ui.theme.Ink
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where Take me there is going. */
data class NavTarget(val placeId: String, val lat: Double, val lng: Double, val name: String)

/** Within this many metres of the place, you're there. */
private const val ARRIVED_M = 40.0
/** Further than this from the line, the route is worked out again (at most every 15 s). */
private const val OFF_ROUTE_M = 70.0

/** Distance in metres between two points on the earth. */
internal fun metres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLng = Math.toRadians(bLng - aLng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/** Compass bearing from a to b, degrees clockwise from north. */
internal fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val y = sin(Math.toRadians(bLng - aLng)) * cos(Math.toRadians(bLat))
    val x = cos(Math.toRadians(aLat)) * sin(Math.toRadians(bLat)) -
        sin(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * cos(Math.toRadians(bLng - aLng))
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * Where you are on a route: how far along it, how far off it, and what's left. The line is
 * [lng, lat] pairs as the server sends them. Flat-earth maths per segment, which is plenty
 * for a drive across town.
 */
internal class RouteProgress(line: List<List<Double>>) {
    private val pts = line.filter { it.size >= 2 }.map { it[1] to it[0] }
    /** Distance from the start to each point. */
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + metres(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
    }
    val length: Double get() = cum.lastOrNull() ?: 0.0

    /** (metres along the line, metres off it) for a point. */
    fun locate(lat: Double, lng: Double): Pair<Double, Double> {
        if (pts.size < 2) return 0.0 to 0.0
        var best = Double.MAX_VALUE
        var along = 0.0
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        for (i in 0 until pts.size - 1) {
            val (aLat, aLng) = pts[i]
            val (bLat, bLng) = pts[i + 1]
            val ax = (aLng - lng) * kx; val ay = (aLat - lat) * ky
            val bx = (bLng - lng) * kx; val by = (bLat - lat) * ky
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < best) {
                best = d
                along = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return along to best
    }
}

/** What the screen shows at a moment: the next turn, what's left and whether you're there. */
internal data class NavProgress(
    val nextInstruction: String,
    val toNextM: Double?,
    val remainingM: Double,
    val remainingS: Double?,
    val offRouteM: Double,
    val arrived: Boolean,
)

internal fun progressOf(route: Directions?, here: Coords, target: NavTarget): NavProgress {
    val straight = metres(here.lat, here.lng, target.lat, target.lng)
    if (straight <= ARRIVED_M) return NavProgress("You have arrived", null, 0.0, 0.0, 0.0, true)
    if (route == null || route.coordinates.size < 2 || route.source != "osrm") {
        return NavProgress("Head towards ${target.name}", null, straight, route?.durationS?.toDouble(), 0.0, false)
    }
    val p = RouteProgress(route.coordinates)
    val (along, off) = p.locate(here.lat, here.lng)
    val remaining = (p.length - along).coerceAtLeast(0.0)
    val seconds = route.durationS?.let { d -> if (p.length > 0) d * remaining / p.length else null }
    // The next turn is the first one still ahead of you.
    val next = route.steps.drop(1).map { it to p.locate(it.lat, it.lng).first }.firstOrNull { it.second > along + 8 }
    return NavProgress(
        nextInstruction = next?.first?.instruction ?: "Head towards ${target.name}",
        toNextM = next?.let { it.second - along },
        remainingM = remaining,
        remainingS = seconds,
        offRouteM = off,
        arrived = false,
    )
}

/** Lets the on-device test check the screen's state without reading the map's pixels. */
@VisibleForTesting
object NavigateProbe {
    @Volatile var route: Directions? = null
    @Volatile var reroutes: Int = 0
    /** Tests move "you" by setting the app's location; the phone's own GPS stays out of it. */
    @Volatile var testLocation: Boolean = false
}

/**
 * Take me there, inside WorthAbite: the drive drawn on our own map from where you are, the
 * map following you, the next turn, the time and distance left, a new route if you leave
 * this one, and a "you're here" with the place's best dishes when you arrive. Google Maps is
 * one tap away for voice guidance.
 */
@Composable
fun NavigateScreen(target: NavTarget, onBack: () -> Unit, onOpenPlace: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val here by app.location.collectAsState()
    var route by remember { mutableStateOf<Directions?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var lastFetch by remember { mutableLongStateOf(0L) }
    var fetching by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    var heading by remember { mutableStateOf<Double?>(null) }
    var prev by remember { mutableStateOf<Coords?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(NavigateProbe.testLocation || DeviceLocation.hasPermission(context)) }
    var dismissedArrival by remember { mutableStateOf(false) }

    // The screen stays on while you drive.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Follow the phone while this screen is open.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, hasPermission) {
        if (NavigateProbe.testLocation) return@DisposableEffect onDispose { }
        var stop: () -> Unit = {}
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    stop()
                    stop = DeviceLocation.follow(context) { c -> app.location.value = c }
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
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result.values.any { it }
        if (hasPermission) scope.launch {
            val found = DeviceLocation.locate(context, precise = true)
            (found as? LocationResult.Found)?.let { app.location.value = it.coords }
            if (found == LocationResult.Off) DeviceLocation.openSettings(context)
        }
    }

    fun fetchRoute(from: Coords) {
        if (fetching) return
        fetching = true
        lastFetch = System.currentTimeMillis()
        scope.launch {
            runCatching { apiCall { app.api.directions(from.lat, from.lng, target.lat, target.lng) } }
                .onSuccess {
                    if (route != null) NavigateProbe.reroutes++
                    route = it
                    NavigateProbe.route = it
                    routeError = null
                }
                .onFailure { routeError = it.message ?: "Couldn't get directions." }
            fetching = false
        }
    }

    val progress = here?.let { progressOf(route, it, target) }
    // First route as soon as we know where you are; a new one when you leave it.
    LaunchedEffect(here) {
        val h = here ?: return@LaunchedEffect
        prev?.let { p -> if (metres(p.lat, p.lng, h.lat, h.lng) > 8) heading = bearing(p.lat, p.lng, h.lat, h.lng) }
        if (prev == null || metres(prev!!.lat, prev!!.lng, h.lat, h.lng) > 8) prev = h
        val now = System.currentTimeMillis()
        val off = progress?.offRouteM ?: 0.0
        if (route == null && routeError == null) fetchRoute(h)
        else if (progress?.arrived != true && off > OFF_ROUTE_M && now - lastFetch > 15_000) fetchRoute(h)
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.cameraPosition = CameraPosition.Builder().target(LatLng(target.lat, target.lng)).zoom(14.0).build()
                m.setStyle(Style.Builder().fromJson(styleJson(context, dark))) { style ->
                    runCatching { addNavLayers(style) }.onFailure { android.util.Log.e("Navigate", "layers failed", it) }
                    styleReady = true
                }
                map = m
            }
        }
    }
    MapLifecycle(mapView)
    val stopFollowing by rememberUpdatedState { following = false }
    LaunchedEffect(map) {
        map?.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) stopFollowing()
        }
    }

    // The road, the place and you.
    LaunchedEffect(map, styleReady, route) {
        val style = map?.style ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val line = route?.coordinates.orEmpty().filter { it.size >= 2 }
        style.getSourceAs<GeoJsonSource>(NAV_ROUTE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                if (line.size < 2) emptyList() else listOf(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it[0], it[1]) }))),
            ),
        )
        style.getSourceAs<GeoJsonSource>(NAV_DEST)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(target.lng, target.lat)))
        if (!following && line.size >= 2) {
            val b = LatLngBounds.Builder().apply { line.forEach { include(LatLng(it[1], it[0])) } }.build()
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120, 320, 120, 420), 600)
        }
    }
    LaunchedEffect(map, styleReady, here, following, heading) {
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val h = here ?: return@LaunchedEffect
        m.style?.getSourceAs<GeoJsonSource>(NAV_ME)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(h.lng, h.lat)))
        if (following) {
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(h.lat, h.lng)).zoom(16.5).bearing(heading ?: 0.0).build(),
                ),
                700,
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("navigate")) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // Top: the next turn.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("nav_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Ink,
                    shadowElevation = 6.dp,
                    modifier = Modifier.weight(1f).testTag("nav_banner"),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Directions, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            val toNext = progress?.toNextM?.let { Format.distance(it.toInt()) }
                            if (toNext != null) {
                                Text(toNext, style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                when {
                                    !hasPermission -> "Allow location to start"
                                    here == null -> "Finding you…"
                                    else -> progress?.nextInstruction ?: "Finding the way…"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = androidx.compose.ui.graphics.Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("nav_instruction"),
                            )
                        }
                    }
                }
            }
            if (route?.source == "straight") {
                Text(
                    "Road directions aren't available right now; this is the straight line.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            routeError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // Back to following you after a pan.
        AnimatedVisibility(
            visible = !following && here != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                IconButton(onClick = { following = true }, modifier = Modifier.testTag("nav_recenter")) {
                    Icon(AppIcons.Near, "Follow me", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Bottom: time and distance left, Google Maps, End.
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                if (!hasPermission) {
                    Text("Directions start from where you are.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Allow location", { askLocation.launch(DeviceLocation.PERMISSIONS) }, Modifier.fillMaxWidth().testTag("nav_allow"))
                } else {
                    val left = progress?.remainingS?.let { Format.duration(it.toInt()) } ?: progress?.remainingM?.let { Format.driveTime(it.toInt()) }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            left ?: "–",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("nav_eta"),
                        )
                        Spacer(Modifier.width(10.dp))
                        val arrive = progress?.remainingS?.let {
                            java.time.LocalTime.now().plusSeconds(it.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                        }
                        Text(
                            listOfNotNull(progress?.remainingM?.let { Format.distance(it.toInt()) }, arrive?.let { "arrive $it" }).joinToString("  ·  "),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp).testTag("nav_left"),
                        )
                    }
                    Text(target.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuietButton(
                            "Google Maps",
                            { directionsTo(scope, app.api, context, target.placeId, target.lat, target.lng, target.name) },
                            Modifier.weight(1f).testTag("nav_google"),
                        )
                        PrimaryButton("End", onBack, Modifier.weight(1f).testTag("nav_end"), leading = Icons.Filled.Close)
                    }
                }
            }
        }

        // You're there.
        AnimatedVisibility(
            visible = progress?.arrived == true && !dismissedArrival,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth().testTag("nav_arrived"),
            ) {
                Column(Modifier.navigationBarsPadding().padding(24.dp)) {
                    Text("You're here 🎉", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(target.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("See what people loved here, and tell us how it was after.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuietButton("Done", { dismissedArrival = true; onBack() }, Modifier.weight(1f).testTag("nav_done"))
                        PrimaryButton("What's good here", { onOpenPlace(target.placeId) }, Modifier.weight(1f).testTag("nav_open_place"))
                    }
                }
            }
        }

        if (hasPermission && here == null && !NavigateProbe.testLocation && !DeviceLocation.isOn(context)) {
            Box(Modifier.align(Alignment.Center).padding(24.dp)) {
                Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 8.dp) {
                    Box(Modifier.height(260.dp)) {
                        CenteredMessage(
                            "Location is off",
                            "Turn on location so WorthAbite can guide you there.",
                            AppIcons.Near,
                            "Open settings",
                            onAction = { DeviceLocation.openSettings(context) },
                        )
                    }
                }
            }
        }
    }
}

private const val NAV_ROUTE = "nav-route"
private const val NAV_DEST = "nav-dest"
private const val NAV_ME = "nav-me"

private fun addNavLayers(style: Style) {
    style.addSource(GeoJsonSource(NAV_ROUTE))
    style.addSource(GeoJsonSource(NAV_DEST))
    style.addSource(GeoJsonSource(NAV_ME))
    // A white casing under the ember road so it reads on any street colour.
    style.addLayer(
        LineLayer("nav-route-casing", NAV_ROUTE).withProperties(
            PropertyFactory.lineColor("#FFFFFF"),
            PropertyFactory.lineWidth(10f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer("nav-route-line", NAV_ROUTE).withProperties(
            PropertyFactory.lineColor("#F04E23"),
            PropertyFactory.lineWidth(6f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer("nav-dest", NAV_DEST).withProperties(
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleColor("#F04E23"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(4f),
        ),
    )
    style.addLayer(
        CircleLayer("nav-me", NAV_ME).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor("#1E88E5"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}
