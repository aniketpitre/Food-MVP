package app.worthabite.ui.restaurant

import app.worthabite.ui.components.OpenHours
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import app.worthabite.data.HoursBody
import app.worthabite.data.PilotOffer
import app.worthabite.data.VisitCode
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.worthabite.data.GooglePhoto
import app.worthabite.data.PlacePhoto
import app.worthabite.data.apiCall
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.openUrl
import app.worthabite.ui.map.friendlyHours
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.worthabite.ui.components.DashedLine
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.components.TicketShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.engagement
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.DishCard
import app.worthabite.data.Engagement
import app.worthabite.data.EngagementState
import app.worthabite.data.RestaurantDetail
import app.worthabite.ui.Format
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishRow
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.QuietButton
import app.worthabite.ui.components.rememberTakeMeThere
import app.worthabite.ui.components.DemoTag
import app.worthabite.ui.components.Reveal
import app.worthabite.ui.components.Tag
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.rememberDishActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RestaurantUiState(val loading: Boolean = true, val place: RestaurantDetail? = null, val error: String? = null)

class RestaurantViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(RestaurantUiState())
    val state: StateFlow<RestaurantUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.place == null, error = null)
            try {
                val loc = app.location.value
                _state.value = RestaurantUiState(false, apiCall { app.api.restaurant(id, loc?.lat, loc?.lng) })
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    /** A visit code for the pilot offer: shown at the counter; the owner checks it. */
    val visitCode = MutableStateFlow<VisitCode?>(null)

    fun getVisitCode(say: (String) -> Unit) = viewModelScope.launch {
        try {
            visitCode.value = apiCall { app.api.visitCode(id) }
        } catch (e: ApiException) { say(e.message ?: "Couldn't get a code.") }
    }

    /** Opening hours for a place the map data has none for (or that people added). */
    fun setHours(hours: String, say: (String) -> Unit) = viewModelScope.launch {
        try {
            val r = apiCall { app.api.setHours(id, HoursBody(hours)) }
            _state.value.place?.let { _state.value = _state.value.copy(place = it.copy(openingHours = r.openingHours)) }
            say("Thanks! Hours added.")
        } catch (e: ApiException) { say(e.message ?: "Couldn't save the hours.") }
    }
}

@Composable
fun RestaurantScreen(
    vm: RestaurantViewModel,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by app.overrides.collectAsState()
    val actions = rememberDishActions(onNeedSignIn, showMessage)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Google Maps photos are fetched live by the server (never stored), only if it has a key.
    val takeMeThere = rememberTakeMeThere()
    var googlePhotos by remember { mutableStateOf<List<GooglePhoto>>(emptyList()) }
    LaunchedEffect(state.place?.id, state.place?.googlePhotos) {
        val p = state.place
        if (p != null && p.googlePhotos) {
            googlePhotos = runCatching { apiCall { app.api.googlePhotos(p.id) }.photos }.getOrDefault(emptyList())
        }
    }
    when {
        state.loading -> LoadingBox()
        state.place == null -> CenteredMessage(
            "Couldn't open this place", state.error, Icons.Filled.Refresh, "Try again", vm::load, "Go back", onBack,
        )
        else -> RestaurantContent(
            place = state.place!!,
            engagementOf = { overrides[it.id] ?: it.engagement() },
            thumbOf = { app.mediaUrl(it.cover?.thumbUrl) },
            onBack = onBack,
            onOpenDish = onOpenDish,
            onSave = { actions.toggle(it, Engagement.Save) },
            onMaps = { p -> takeMeThere.go(p.id, p.lat, p.lng, p.name, p.isDemo) },
            mediaUrl = { app.mediaUrl(it) },
            googlePhotos = googlePhotos,
            onOpenLink = { openUrl(context, it) },
            onSetHours = { if (app.session.value == null) onNeedSignIn() else vm.setHours(it, showMessage) },
            onVisitCode = { if (app.session.value == null) onNeedSignIn() else vm.getVisitCode(showMessage) },
        )
    }
    val code by vm.visitCode.collectAsState()
    code?.let { c -> VisitCodeDialog(c) { vm.visitCode.value = null } }
}

/** The code, big enough to show across a counter, with the offer and when it runs out. */
@Composable
private fun VisitCodeDialog(code: VisitCode, onDismiss: () -> Unit) {
    val until = remember(code.expiresAt) {
        runCatching {
            java.time.OffsetDateTime.parse(code.expiresAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show this at the counter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    code.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("visit_code"),
                )
                code.offer?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(
                    listOfNotNull(code.place.ifBlank { null }, until?.let { "good until $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The place is on the pilot: its offer and a button for a code. */
@Composable
private fun PilotCard(pilot: PilotOffer, onVisitCode: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp)
            .testTag("pilot_card"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(pilot.offer ?: "WorthAbite visit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "Going here? Get a code and show it at the counter." + (pilot.quietHours?.let { " Works during $it." } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TextButton(onClick = onVisitCode, modifier = Modifier.testTag("get_visit_code")) { Text("Get a visit code") }
    }
}

@Composable
fun RestaurantContent(
    place: RestaurantDetail,
    engagementOf: (DishCard) -> EngagementState,
    thumbOf: (DishCard) -> String?,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onSave: (DishCard) -> Unit,
    onMaps: (RestaurantDetail) -> Unit,
    mediaUrl: (String?) -> String? = { it },
    googlePhotos: List<GooglePhoto> = emptyList(),
    onOpenLink: (String) -> Unit = {},
    onSend: (RestaurantDetail) -> Unit = {},
    onSetHours: (String) -> Unit = {},
    onVisitCode: () -> Unit = {},
) {
    val totalRecs = place.dishes.sumOf { engagementOf(it).stats.recommenders }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
                Reveal(0) {
                    // The place as a big restaurant token: name on top, perforation, then the numbers.
                    // The notches follow the perforation, wherever the name's length puts it.
                    var stub by remember { mutableStateOf(120.dp) }
                    val density = LocalDensity.current
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth()
                            .clip(TicketShape(corner = 28.dp, notch = 13.dp, notchFromTop = stub))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .onSizeChanged { stub = with(density) { it.height.toDp() } }
                                .padding(horizontal = 22.dp, vertical = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            KickerText(
                                listOfNotNull(place.locality, Format.distance(place.distanceM), Format.driveTime(place.distanceM)?.let { "$it drive" }).joinToString("  ·  ").ifEmpty { "Place" },
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            Text(
                                place.name, style = MaterialTheme.typography.displaySmall, maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (place.status == "pending") Tag("Waiting for review")
                            if (place.isDemo) DemoTag()
                        }
                        DashedLine(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f), Modifier.padding(horizontal = 20.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BigStat(Format.compact(totalRecs), "recommends")
                            Spacer(Modifier.width(22.dp))
                            BigStat(place.dishes.size.toString(), if (place.dishes.size == 1) "dish" else "dishes")
                            place.priceBand?.let {
                                Spacer(Modifier.width(22.dp))
                                BigStat("₹".repeat(it), "price")
                            }
                            Spacer(Modifier.weight(1f))
                            PrimaryButton("Go", { onMaps(place) }, leading = AppIcons.Directions)
                        }
                    }
                }
            }
        }
        item(key = "info") { PlaceInfo(place, onOpenLink, onSetHours) }
        place.pilot?.let { pilot -> item(key = "pilot") { PilotCard(pilot, onVisitCode) } }
        if (place.photos.isNotEmpty() || googlePhotos.isNotEmpty()) {
            item(key = "photos") { PlacePhotos(place.photos, googlePhotos, mediaUrl, onOpenLink) }
        }
        item(key = "title") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 6.dp)) {
                Text("Best dishes here", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Ranked by what people who ate them said: loves against \"not for me\", trusted tasters counting more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (place.dishes.isEmpty()) {
            item(key = "empty") { CenteredMessage("No photos yet", "Post the first dish from this place.") }
        }
        itemsIndexed(place.dishes, key = { _, d -> d.id }) { i, d ->
            Reveal(60 + 50 * i.coerceAtMost(8)) {
                DishRow(d, engagementOf(d), thumbOf(d), { onOpenDish(d.id) }, { onSave(d) }, showPlace = false, rank = i + 1)
            }
        }
        item(key = "end") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

/**
 * Open now or not (from the hours), a Call button, then cuisine, hours, address and website, from
 * the map data or from people. Missing hours can be added by anyone signed in a day or more.
 */
@Composable
private fun PlaceInfo(place: RestaurantDetail, onOpenLink: (String) -> Unit, onSetHours: (String) -> Unit) {
    var addingHours by remember { mutableStateOf(false) }
    val open = remember(place.openingHours) { place.openingHours?.let { OpenHours.stateAt(it) } }
    val lines = listOfNotNull(
        place.cuisine?.let { "🍽️  ${it.replaceFirstChar { c -> c.uppercase() }}" to null },
        place.openingHours?.let { "🕒  ${friendlyHours(it)}" to null },
        place.address?.let { "📍  $it" to null },
        place.website?.let { "🌐  ${it.removePrefix("https://").removePrefix("http://").trimEnd('/')}" to it },
    )
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            open?.let {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (it.open) androidx.compose.ui.graphics.Color(0xFF1B7F3B) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("open_now"),
                )
            }
            place.phone?.let { phone ->
                androidx.compose.material3.OutlinedButton(
                    onClick = { onOpenLink("tel:${phone.filter { c -> c.isDigit() || c == '+' }}") },
                    modifier = Modifier.testTag("call_place"),
                ) { Text("📞  Call") }
            }
        }
        lines.forEach { (text, link) ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = if (link != null) Modifier.clickable { onOpenLink(link) } else Modifier,
            )
        }
        if (place.hoursEditable && !place.isDemo) {
            androidx.compose.material3.TextButton(
                onClick = { addingHours = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.testTag("add_hours"),
            ) { Text(if (place.openingHours == null) "🕒  Know when it's open? Add the hours" else "Hours wrong? Fix them") }
        }
    }
    if (addingHours) HoursDialog(onDismiss = { addingHours = false }) { addingHours = false; onSetHours(it) }
}

/** A simple hours picker: which days, and from when to when (or open all day). */
@Composable
private fun HoursDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val dayOptions = listOf("Mo-Su" to "Every day", "Mo-Sa" to "Mon–Sat", "Mo-Fr" to "Mon–Fri")
    var days by remember { mutableStateOf("Mo-Su") }
    var open by remember { mutableStateOf("09:00") }
    var close by remember { mutableStateOf("22:00") }
    var allDay by remember { mutableStateOf(false) }
    val tp = OpenHours.timePattern
    val ok = allDay || (tp.matches(open) && tp.matches(close) && open != close)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When is it open?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    dayOptions.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = days == value,
                            onClick = { days = value },
                            shape = SegmentedButtonDefaults.itemShape(i, dayOptions.size),
                        ) { Text(label) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = allDay, onCheckedChange = { allDay = it })
                    Text("Open 24 hours")
                }
                if (!allDay) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.OutlinedTextField(open, { open = it.take(5) }, Modifier.weight(1f), label = { Text("Opens (24h)") }, singleLine = true)
                    androidx.compose.material3.OutlinedTextField(close, { close = it.take(5) }, Modifier.weight(1f), label = { Text("Closes (24h)") }, singleLine = true)
                }
                Text(
                    "Closing after midnight? Put the time it closes, like 01:30.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(OpenHours.build(days, open, close, allDay)) },
                enabled = ok,
            ) { Text("Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Real photos of the place: openly licensed ones we keep (Wikimedia Commons, Mapillary
 * street views) with their credit under each, then Google Maps photos with Google's
 * attribution and the photographers' names, as Google requires.
 */
@Composable
private fun PlacePhotos(
    photos: List<PlacePhoto>,
    google: List<GooglePhoto>,
    mediaUrl: (String?) -> String?,
    onOpenLink: (String) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("Photos of this place", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(photos.size, key = { "p$it" }) { i ->
                val p = photos[i]
                Column(Modifier.width(220.dp)) {
                    DishImage(
                        mediaUrl(p.thumbUrl), p.placeholderColor, p.attribution,
                        Modifier.width(220.dp).height(160.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                    )
                    Text(
                        p.attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp).then(p.sourceUrl?.let { u -> Modifier.clickable { onOpenLink(u) } } ?: Modifier),
                    )
                }
            }
            items(google.size, key = { "g$it" }) { i ->
                val g = google[i]
                Column(Modifier.width(220.dp)) {
                    DishImage(
                        g.url, null, "Photo from Google Maps",
                        Modifier.width(220.dp).height(160.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
                    )
                    Text(
                        "Google Maps · " + g.authors.joinToString { it.name }.ifEmpty { "Google Maps user" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp).then(g.authors.firstOrNull()?.uri?.let { u -> Modifier.clickable { onOpenLink(u) } } ?: Modifier),
                    )
                }
            }
        }
    }
}
