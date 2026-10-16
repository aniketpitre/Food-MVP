package app.foodfeed.ui.home

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
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.CravingResult
import app.foodfeed.data.DishCard
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.ChampionLine
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PriceTag
import app.foodfeed.ui.components.RatingPill
import app.foodfeed.ui.components.Reveal
import app.foodfeed.ui.components.Sticker
import app.foodfeed.ui.components.TasterCard
import app.foodfeed.ui.components.VegMark
import app.foodfeed.ui.theme.CardTitle
import app.foodfeed.ui.theme.Turmeric
import app.foodfeed.ui.theme.dishHero
import app.foodfeed.ui.theme.pressScale
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
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    CravingContent(state, { app.mediaUrl(it) }, onBack, onOpenDish, onOpenUser, onAddDish, vm::load)
}

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
) {
    var explain by remember { mutableStateOf(false) }
    val r = state.result
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { explain = true }) { Icon(Icons.Filled.Info, "How the ranking works") }
        }
        when {
            state.loading -> LoadingBox()
            r == null -> CenteredMessage("Couldn't search", state.error, Icons.Filled.Refresh, "Try again", onRetry)
            r.results.isEmpty() -> CenteredMessage(
                "No ${r.label} in ${state.city} yet",
                "Had a great one? Post it, and it'll be the first people find.",
                null, "Post a dish", onAddDish,
            )
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
                item(key = "winner") {
                    Reveal(60) { WinnerCard(r.results.first(), imageUrl, onOpenDish, onOpenUser) }
                }
                itemsIndexed(r.results.drop(1), key = { _, c -> c.id }) { i, c ->
                    Reveal(100 + 50 * i.coerceAtMost(8)) { RankedRow(i + 2, c, imageUrl, onOpenDish, onOpenUser) }
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
        DishImage(imageUrl(card.cover?.imageUrl), card.cover?.placeholderColor, card.name, Modifier.fillMaxSize().dishHero(card.id, 28.dp))
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
            Point("4", "Nobody can buy it", "New accounts count half for their first day, and one person is one vote per dish.")
        }
    }
}

@Composable
private fun Point(n: String, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text(n, style = MaterialTheme.typography.labelLarge, color = app.foodfeed.ui.theme.Ink) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
    }
}
