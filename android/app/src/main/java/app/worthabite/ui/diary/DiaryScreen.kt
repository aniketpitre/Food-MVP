package app.worthabite.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.DiaryEntry
import app.worthabite.data.DiarySummary
import app.worthabite.data.apiCall
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.VegMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DiaryUiState(
    val loading: Boolean = true,
    val summary: DiarySummary = DiarySummary(),
    val entries: List<DiaryEntry> = emptyList(),
    val nextOffset: Int? = null,
    val error: String? = null,
)

class DiaryViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(DiaryUiState())
    val state: StateFlow<DiaryUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = it.entries.isEmpty(), error = null) }
        try {
            val d = apiCall { app.api.diary(0) }
            _state.value = DiaryUiState(false, d.summary, d.entries, d.nextOffset)
        } catch (e: ApiException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    fun more() = viewModelScope.launch {
        val offset = _state.value.nextOffset ?: return@launch
        _state.update { it.copy(nextOffset = null) }
        try {
            val d = apiCall { app.api.diary(offset) }
            _state.update { it.copy(entries = it.entries + d.entries, nextOffset = d.nextOffset) }
        } catch (_: ApiException) {
            _state.update { it.copy(nextOffset = offset) }
        }
    }
}

/** "March 2026": entries grouped by the month you ate them, in your time zone. */
fun diaryMonth(at: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(at).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH)) }.getOrDefault("")

fun diaryVerdict(verdict: String?): String? = when (verdict) {
    "loved" -> "Loved it"
    "not_for_me" -> "Not for me"
    else -> null
}

/**
 * My food diary: every dish you posted or voted on, by month, with what you paid. The
 * point is your own record (what was that great misal last month?), not a show for others.
 */
@Composable
fun DiaryScreen(vm: DiaryViewModel, onBack: () -> Unit, onOpenDish: (String) -> Unit, onAddDish: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val app = LocalApp.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("My food diary", style = MaterialTheme.typography.titleLarge)
        }
        when {
            state.loading -> LoadingBox()
            state.error != null && state.entries.isEmpty() ->
                CenteredMessage("Couldn't open your diary", state.error, Icons.Filled.Refresh, "Try again", { vm.load() })
            state.entries.isEmpty() -> CenteredMessage(
                "Your diary is empty",
                "Every dish you post or vote on lands here, with what you paid. Start with the last thing you ate.",
                null, "Post a dish", onAddDish,
            )
            else -> LazyColumn(Modifier.testTag("diary_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
                item(key = "summary") { Summary(state.summary) }
                var lastMonth = ""
                state.entries.forEach { e ->
                    val month = diaryMonth(e.at)
                    if (month != lastMonth) {
                        lastMonth = month
                        item(key = "m-$month-${e.dishId}") {
                            KickerText(month, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
                        }
                    }
                    item(key = e.dishId) { EntryRow(e, app.mediaUrl(e.photoUrl)) { onOpenDish(e.dishId) } }
                }
                if (state.nextOffset != null) {
                    item(key = "more") {
                        TextButton(onClick = { vm.more() }, modifier = Modifier.padding(16.dp)) { Text("Show older") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Summary(s: DiarySummary) {
    Row(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Stat("${s.dishes}", if (s.dishes == 1) "dish" else "dishes")
        Stat("${s.places}", if (s.places == 1) "place" else "places")
        Stat("${s.loved}", "loved")
        if (s.spentInr > 0) Stat("₹${s.spentInr}", "spent")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
private fun EntryRow(e: DiaryEntry, photo: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DishImage(photo, null, e.dish, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (e.isVeg != null) VegMark(e.isVeg)
                Text(e.dish, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                listOfNotNull(e.place, e.locality).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    diaryVerdict(e.verdict),
                    e.pricePaid?.let { "paid ₹$it" },
                    if (e.photos > 0) "${e.photos} ${if (e.photos == 1) "photo" else "photos"}" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = if (e.verdict == "loved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
