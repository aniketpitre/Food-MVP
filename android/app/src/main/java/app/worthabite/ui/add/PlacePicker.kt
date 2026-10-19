package app.worthabite.ui.add

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.worthabite.data.Coords
import app.worthabite.data.PlaceOption
import app.worthabite.data.apiCall
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.map.MapLifecycle
import app.worthabite.ui.map.styleJson
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
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

/** What was picked on the map: a place already on WorthAbite, or a new one at a spot. */
sealed interface PickedPlace {
    data class Existing(val place: PlaceOption) : PickedPlace
    /** [name] is the map's name for a café or restaurant you tapped, or blank for a bare spot. */
    data class New(val name: String, val lat: Double, val lng: Double) : PickedPlace
}

private const val OURS = "picker-ours"
private const val OURS_DOT = "picker-ours-dot"
private const val OURS_LABEL = "picker-ours-label"

/**
 * "Where did you eat it?" on a map. It opens where you are and shows the places on WorthAbite
 * (saffron dots with names: every restaurant, cafe and stall from Overture Maps, plus the ones
 * people added). Tap one to pick it; or move the map so the pin sits on the spot and add a
 * new place there.
 */
@Composable
fun PlacePicker(start: Coords?, onPicked: (PickedPlace) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val app = LocalApp.current
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var center by remember { mutableStateOf(start) }
    var places by remember { mutableStateOf<List<PlaceOption>>(emptyList()) }
    var chosen by remember { mutableStateOf<PickedPlace?>(null) }
    val pick by rememberUpdatedState(onPicked)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.uiSettings.isCompassEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                val at = start ?: Coords(19.9975, 73.7898)
                m.cameraPosition = CameraPosition.Builder().target(LatLng(at.lat, at.lng)).zoom(17.0).build()
                m.setStyle(Style.Builder().fromJson(styleJson(context, dark))) { style ->
                    runCatching { addPickerLayers(style) }.onFailure { android.util.Log.e("PlacePicker", "layers failed", it) }
                    styleReady = true
                }
                m.addOnCameraIdleListener {
                    val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                    center = Coords(t.latitude, t.longitude)
                }
                m.addOnMapClickListener { at ->
                    val p = m.projection.toScreenLocation(at)
                    val ours = m.queryRenderedFeatures(p, OURS_DOT, OURS_LABEL).firstOrNull()
                    when {
                        ours != null -> {
                            val id = ours.getStringProperty("id")
                            places.firstOrNull { it.id == id }?.let { chosen = PickedPlace.Existing(it) }
                        }
                        else -> chosen = null
                    }
                    true
                }
                map = m
            }
        }
    }
    MapLifecycle(mapView)

    // The WorthAbite places around wherever the map is.
    LaunchedEffect(center) {
        val c = center ?: return@LaunchedEffect
        delay(250)
        places = runCatching { apiCall { app.api.nearbyRestaurants(c.lat, c.lng, null) } }.getOrDefault(places)
    }
    LaunchedEffect(map, styleReady, places) {
        val style = map?.style ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(OURS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                places.filter { it.lat != null && it.lng != null }.map { p ->
                    Feature.fromGeometry(Point.fromLngLat(p.lng!!, p.lat!!)).apply {
                        addStringProperty("id", p.id)
                        addStringProperty("name", p.name)
                    }
                },
            ),
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("place_picker")) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // The pin in the middle: where a new place goes when nothing is tapped.
        if (chosen == null) {
            Icon(
                AppIcons.Pin, null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.Center).offset(y = (-18).dp).size(40.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("picker_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Where did you eat it?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tap the café or restaurant, or move the map so the pin is on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val c = chosen) {
                    is PickedPlace.Existing -> {
                        Text(c.place.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(c.place.locality, "Already on WorthAbite").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrimaryButton("Use this place", { pick(c) }, Modifier.fillMaxWidth().testTag("picker_use"))
                    }
                    is PickedPlace.New -> {
                        Text(c.name.ifBlank { "This spot" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Not on WorthAbite yet. Add it, and it shows for everyone after a quick review.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrimaryButton("Add this place", { pick(c) }, Modifier.fillMaxWidth().testTag("picker_add"))
                    }
                    null -> {
                        Text("Nothing picked yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Can't see it on the map? Put the pin on it and add it yourself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrimaryButton(
                            "Add a new place at the pin",
                            { center?.let { pick(PickedPlace.New("", it.lat, it.lng)) } },
                            Modifier.fillMaxWidth().testTag("picker_add_here"),
                            enabled = center != null,
                        )
                    }
                }
            }
        }
    }
}

private fun addPickerLayers(style: Style) {
    // Places already on WorthAbite: bigger saffron dots, on top.
    style.addSource(GeoJsonSource(OURS))
    style.addLayer(
        CircleLayer(OURS_DOT, OURS).withProperties(
            PropertyFactory.circleRadius(9f),
            PropertyFactory.circleColor("#F5C518"),
            PropertyFactory.circleStrokeColor("#2B1B12"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        SymbolLayer(OURS_LABEL, OURS).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textColor("#2B1B12"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
        ),
    )
}
