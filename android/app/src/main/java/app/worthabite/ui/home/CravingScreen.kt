package app.worthabite.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.components.QuietButton
import app.worthabite.ui.components.DishFilter
import app.worthabite.ui.components.DishFilterChips
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.CravingPlace
import app.worthabite.data.CravingResult
import app.worthabite.data.DishCard
import app.worthabite.data.apiCall
import app.worthabite.ui.Format
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.ChampionLine
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.PriceTag
import app.worthabite.ui.components.RatingPill
import app.worthabite.ui.components.Reveal
import app.worthabite.ui.components.Sticker
import app.worthabite.ui.components.TasterCard
import app.worthabite.ui.components.VegMark
import app.worthabite.ui.theme.CardTitle
import app.worthabite.ui.theme.Turmeric
import app.worthabite.ui.theme.dishHero
import app.worthabite.ui.theme.pressScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CravingUiState(val loading: Boolean = true, val result: CravingResult? = null, val error: String? = null, val city: String = "")

class CravingViewModel(private val app: AppContainer, private val query: String) : ViewModel() {
    private val _state = MutableStateFlow(CravingUiState(city = app.city.value?.name.orEmpty()))
    val state: StateFlow<CravingUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        val city = app.city.value ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = it.result == null, error = null) }
            try {
                val loc = app.location.value
                val r = apiCall { app.api.craving(query, city.id, loc?.lat, loc?.lng) }
                _state.update { it.copy(loading = false, result = r) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }
}

@Composable
fun CravingScreen(
    vm: CravingViewModel,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onAddDish: () -> Unit,
    onOpenPlace: (String) -> Unit = {},
    onCraving: (String) -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    val context = LocalContext.current
    val config by app.appConfig.collectAsState()
    val city = app.city.collectAsState().value
    CravingContent(
        state, { app.mediaUrl(it) }, onBack, onOpenDish, onOpenUser, onAddDish, vm::load, onOpenPlace,
        onShare = { r ->
            val link = bestLink(app.serverUrl.value, city?.id, r.query)
            val text = shareText(r, state.city, link)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Share this list").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        suggestions = config.signatureDishes,
        onTry = onCraving,
    )
}

/** The public web page of a craving's list (opens without the app; WhatsApp shows a preview). */
fun bestLink(serverUrl: String, cityId: Int?, query: String): String =
    "${serverUrl.trimEnd('/')}/best?" + (if (cityId != null) "city=$cityId&" else "") + "q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")

/** "Best Pav Bhaji in Nashik, ranked by people who ate it": the top five with their verdicts, then the link. */
fun shareText(r: CravingResult, city: String, link: String): String {
    val lines = r.results.take(5).mapIndexed { i, c ->
        val verdict = c.rating.percent?.let { p -> " ($p% loved, ${c.rating.votes} ${if (c.rating.votes == 1) "vote" else "votes"})" } ?: ""
        "${i + 1}. ${c.name} at ${c.restaurant.name}$verdict"
    }
    val head = "Best ${r.label}" + (if (city.isNotBlank()) " in $city" else "") + ", ranked by people who ate it:"
    return (listOf(head) + lines + listOf("", "Full list: $link")).joinToString("\n")
}

/** Few votes so far: the list is still settling and every vote moves it. */
fun isEarlyDays(r: CravingResult): Boolean = r.results.size < 3 || r.results.sumOf { it.rating.votes } < 10

/**
 * "Best pav bhaji in Nashik": every place's pav bhaji, ranked by the People's Pick. The
 * winner gets a big card; the rest follow as ranked rows with their verdicts. Then the
 * people whose pav bhaji picks others backed, and how the ranking works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CravingContent(
    state: CravingUiState,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onAddDish: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlace: (String) -> Unit = {},
    onShare: ((CravingResult) -> Unit)? = null,
    suggestions: List<String> = emptyList(),
    onTry: (String) -> Unit = {},
) {
    var explain by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DishFilter()) }
    val r = state.result
    // Places that may serve it and aren't already ranked above.
    val morePlaces = r?.places.orEmpty().filter { p -> r?.results.orEmpty().none { it.restaurant.id == p.id } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            if (onShare != null && r != null && r.results.isNotEmpty()) {
                IconButton(onClick = { onShare(r) }, modifier = Modifier.testTag("craving_share")) { Icon(Icons.Filled.Share, "Share this list") }
            }
            IconButton(onClick = { explain = true }) { Icon(Icons.Filled.Info, "How the ranking works") }
        }
        when {
            state.loading -> LoadingBox()
            r == null -> CenteredMessage("Couldn't search", state.error, Icons.Filled.Refresh, "Try again", onRetry)
            r.results.isEmpty() && morePlaces.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "title") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        KickerText(state.city)
                        Text(r.label, style = MaterialTheme.typography.displaySmall)
                        Text(
                            "No photos of it here yet, but these places may serve it. Tried it at one? Post it, and it'll be the first people find.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PrimaryButton("Post ${r.label}", onAddDish, Modifier.padding(top = 12.dp).testTag("craving_post"), leading = AppIcons.Camera)
                    }
                }
                items(morePlaces, key = { "place-${it.id}" }) { p -> PlaceRow(p, onOpenPlace) }
            }
            r.results.isEmpty() -> EmptyCraving(r.label, state.city, suggestions.filterNot { it.equals(r.label, ignoreCase = true) }, onAddDish, onTry)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "title") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        KickerText("People's Pick · ${state.city}")
                        Text("Best ${r.label}", style = MaterialTheme.typography.displaySmall)
                        val votes = r.results.sumOf { it.rating.votes }
                        Text(
                            if (votes > 0) "Ranked by what ${Format.compact(votes)} people who tried it decided"
                            else "No verdicts yet: these are the newest. Tried one? Tell people.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isEarlyDays(r)) {
                    item(key = "early") { EarlyDaysCard(r.label, state.city, r.results.sumOf { it.rating.votes }, onAddDish) }
                }
                item(key = "filters") {
                    DishFilterChips(filter, { filter = it }, Modifier.padding(top = 12.dp))
                }
                val shown = filter.apply(r.results)
                if (shown.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            "None match these filters yet. Prices and tags come from people who tried the dish.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else item(key = "winner") {
                    Reveal(60) { WinnerCard(shown.first(), imageUrl, onOpenDish, onOpenUser) }
                }
                itemsIndexed(shown.drop(1), key = { _, c -> c.id }) { i, c ->
                    Reveal(100 + 50 * i.coerceAtMost(8)) { RankedRow(i + 2, c, imageUrl, onOpenDish, onOpenUser) }
                }
                if (morePlaces.isNotEmpty()) {
                    item(key = "places-title") { SectionHeader("More places that may serve it", "No photos of it there yet") }
                    items(morePlaces, key = { "place-${it.id}" }) { p -> PlaceRow(p, onOpenPlace) }
                }
                if (r.tasters.isNotEmpty()) {
                    item(key = "tasters-title") { SectionHeader("People to trust for ${r.label.lowercase()}", "Their picks were backed by the people who came after") }
                    item(key = "tasters") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(r.tasters, key = { it.handle }) { t -> TasterCard(t, { onOpenUser(t.handle) }) }
                        }
                    }
                }
            }
        }
    }
    if (explain) HowItWorksSheet { explain = false }
}

/** Nothing posted for this craving yet: say so plainly, ask for the first post, offer dishes that have some. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyCraving(label: String, city: String, suggestions: List<String>, onAddDish: () -> Unit, onTry: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("craving_empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KickerText(city)
        Text("No $label here yet", style = MaterialTheme.typography.headlineMedium)
        Text(
            "WorthAbite is new in ${city.ifBlank { "your city" }}, and every dish on it comes from someone who ate it. Had a great $label? Post it in 30 seconds and it'll be the first one people find.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Post $label", onAddDish, Modifier.testTag("craving_post"), leading = AppIcons.Camera)
        if (suggestions.isNotEmpty()) {
            Text("Or try one people are filling first", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.take(10).forEach { s -> SuggestionChip(onClick = { onTry(s) }, label = { Text(s) }) }
            }
        }
    }
}

/** A list with few votes: honest about it, and a nudge to add one. */
@Composable
private fun EarlyDaysCard(label: String, city: String, votes: Int, onAddDish: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp)
            .testTag("craving_early"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Early days for $label${if (city.isNotBlank()) " in $city" else ""}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            (if (votes == 0) "No votes yet, so this list is newest first." else "Only $votes ${if (votes == 1) "vote" else "votes"} so far, so this list can still change.") +
                " Tried one? Your vote or photo moves it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        QuietButton("Post $label", onAddDish, leading = AppIcons.Camera)
    }
}

@Composable
private fun WinnerCard(card: DishCard, imageUrl: (String?) -> String?, onOpenDish: (String) -> Unit, onOpenUser: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(28.dp))
            .clickable(interaction, indication = null, role = Role.Button) { onOpenDish(card.id) },
    ) {
        DishImage(imageUrl(card.cover?.imageUrl), card.cover?.placeholderColor, card.name, Modifier.fillMaxSize().dishHero(card.id, 28.dp), thumbUrl = imageUrl(card.cover?.thumbUrl))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xFF1A0D06).copy(alpha = 0.9f))))
        Sticker("#1 pick", Turmeric, tilt = -6f, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RatingPill(card.rating, onPhoto = true)
            Text(card.restaurant.name, color = Color.White, style = CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (card.isVeg != null) {
                    Box(Modifier.background(Color.White, RoundedCornerShape(4.dp)).padding(3.dp)) { VegMark(card.isVeg) }
                }
                Format.price(card.priceInr)?.let { PriceTag(it, tilt = -2f) }
                Text(
                    listOfNotNull(card.name, card.restaurant.locality, Format.distance(card.distanceM)).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            card.champion?.let { ChampionLine(it, onOpenUser, onPhoto = true) }
        }
    }
}

@Composable
private fun PlaceRow(place: CravingPlace, onOpenPlace: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button) { onOpenPlace(place.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(
            place.locality,
            place.cuisine?.replaceFirstChar { it.uppercase() },
            Format.distance(place.distanceM),
            if (place.dishCount > 0) "${place.dishCount} dishes" else null,
        ).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RankedRow(rank: Int, card: DishCard, imageUrl: (String?) -> String?, onOpenDish: (String) -> Unit, onOpenUser: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button) { onOpenDish(card.id) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(40.dp).padding(start = 6.dp),
        )
        DishImage(
            imageUrl(card.cover?.thumbUrl), card.cover?.placeholderColor, card.name,
            Modifier.size(78.dp).dishHero(card.id, 16.dp).clip(RoundedCornerShape(16.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.restaurant.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(card.name, Format.price(card.priceInr), Format.distance(card.distanceM)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RatingPill(card.rating)
            card.champion?.let { ChampionLine(it, onOpenUser) }
        }
    }
}

/** Plain-language explanation of the People's Pick, one tap away from every ranking. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("How People's Pick works", style = MaterialTheme.typography.headlineSmall)
            Point("1", "Only people who tried it decide", "After trying a dish, people say Loved it or Not for me. Saves and shares never count.")
            Point("2", "Track records matter", "When you love a dish and the people who try it after you love it too, your future votes count a little more (up to 1.4×). Get it wrong and they count a little less (down to 0.6×).")
            Point("3", "Many votes beat a lucky few", "A dish needs both a high share of loves and enough votes to reach the top. One love doesn't beat thirty.")
            Point("4", "Nobody can buy it", "New accounts grow to full weight over their first week, accounts voting in lockstep count as one, whoever added a place can't vote for its dishes, and one person is one vote per dish.")
            Point("5", "Being there counts", "A vote from someone past their first week who posted a photo at the place, with their phone there (a verified visit), counts a little more (1.25×).")
        }
    }
}

@Composable
private fun Point(n: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center,
        ) { Text(n, style = MaterialTheme.typography.labelLarge, color = app.worthabite.ui.theme.Ink) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
    }
}
