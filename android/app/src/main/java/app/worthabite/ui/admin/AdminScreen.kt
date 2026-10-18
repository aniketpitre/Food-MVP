package app.worthabite.ui.admin

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.aspectRatio
import app.worthabite.data.MergeBody
import app.worthabite.data.SimilarDishPair
import app.worthabite.data.AlgorithmHealth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.AdminQueue
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.apiCall
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.SectionTitle
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.openMaps
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdminUiState(
    val loading: Boolean = true,
    val queue: AdminQueue? = null,
    val error: String? = null,
    val health: AlgorithmHealth? = null,
    val similar: List<SimilarDishPair> = emptyList(),
)

class AdminViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                val queue = apiCall { app.api.adminQueue() }
                val health = runCatching { apiCall { app.api.adminHealth() } }.getOrNull()
                val similar = runCatching { apiCall { app.api.adminSimilarDishes() } }.getOrDefault(emptyList())
                _state.value = AdminUiState(false, queue, null, health, similar)
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun act(block: suspend () -> Unit, showMessage: (String) -> Unit, done: String) {
        viewModelScope.launch {
            try {
                block()
                showMessage(done)
                app.contentVersion.value++
                load()
            } catch (e: ApiException) {
                showMessage(e.message)
            }
        }
    }
}

@Composable
fun AdminScreen(vm: AdminViewModel, onBack: () -> Unit, onOpenDish: (String) -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Moderation", style = MaterialTheme.typography.titleLarge)
        }
        val q = state.queue
        when {
            state.loading -> LoadingBox()
            q == null -> CenteredMessage("Couldn't load the queue", state.error)
            else -> LazyColumn(Modifier.fillMaxSize().testTag("admin_list")) {
                state.health?.let { h -> item(key = "health") { HealthCard(h) } }
                if (q.pendingPlaces.isEmpty() && q.reported.isEmpty() && q.heldPhotos.isEmpty() && state.similar.isEmpty()) {
                    item(key = "clear") { CenteredMessage("All clear", "No new places, reports or photos to review.", AppIcons.Shield) }
                }
                if (q.heldPhotos.isNotEmpty()) item { SectionTitle("Possible copies (${q.heldPhotos.size})") }
                items(q.heldPhotos, key = { "h" + it.id }) { h ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(h.dishName, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HeldThumb(app.mediaUrl(h.thumbUrl), "New, by @${h.authorHandle ?: "?"}", Modifier.weight(1f))
                                HeldThumb(app.mediaUrl(h.originalThumbUrl), "Earlier, by @${h.originalAuthorHandle ?: "?"}", Modifier.weight(1f))
                            }
                            Text(
                                "These look like the same photo. Allow it if it's genuinely theirs (say, friends at one table); remove it if it was copied.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.act({ apiCall { app.api.adminContent("posts", h.id, "restore") } }, showMessage, "Allowed") }) { Text("Allow") }
                                OutlinedButton(onClick = { vm.act({ apiCall { app.api.adminContent("posts", h.id, "hide") } }, showMessage, "Removed") }) { Text("Remove") }
                            }
                        }
                    }
                }
                if (state.similar.isNotEmpty()) item { SectionTitle("Same dish, two names? (${state.similar.size})") }
                items(state.similar, key = { "s" + it.aId + it.bId }) { d ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("\"${d.aName}\" and \"${d.bName}\"", style = MaterialTheme.typography.titleMedium)
                            Text("${d.placeName} · ${d.aPosts} and ${d.bPosts} photo(s)", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // The one with more photos keeps its name.
                                val (from, into, name) = if (d.aPosts >= d.bPosts) Triple(d.bId, d.aId, d.aName) else Triple(d.aId, d.bId, d.bName)
                                Button(onClick = { vm.act({ apiCall { app.api.adminMergeDish(from, MergeBody(into)) } }, showMessage, "Merged into $name") }) {
                                    Text("Merge as \"$name\"")
                                }
                            }
                        }
                    }
                }
                if (q.pendingPlaces.isNotEmpty()) item { SectionTitle("New places (${q.pendingPlaces.size})") }
                items(q.pendingPlaces, key = { "p" + it.id }) { p ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(p.locality, p.createdBy?.let { "added by @$it" }, "${p.dishes} dish(es)").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.act({ apiCall { app.api.adminPlace(p.id, "approve") } }, showMessage, "Approved ${p.name}") }) { Text("Approve") }
                                OutlinedButton(onClick = { vm.act({ apiCall { app.api.adminPlace(p.id, "hide") } }, showMessage, "Hidden") }) { Text("Reject") }
                                TextButton(onClick = { openMaps(context, p.lat, p.lng, p.name) }) { Text("Map") }
                            }
                        }
                    }
                }
                if (q.reported.isNotEmpty()) item { SectionTitle("Reports (${q.reported.size})") }
                items(q.reported, key = { "r" + it.targetType + it.targetId }) { r ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(Modifier.padding(14.dp)) {
                            if (r.thumbUrl != null) {
                                DishImage(app.mediaUrl(r.thumbUrl), null, r.label, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${r.targetType}: ${r.label ?: r.targetId.take(8)}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${r.reports} report(s): ${r.reasons.joinToString()} · now ${r.status ?: "?"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (r.targetType == "user") {
                                        TextButton(onClick = { vm.act({ apiCall { app.api.adminDismissUser(r.targetId) } }, showMessage, "Dismissed") }) { Text("Dismiss") }
                                    } else if (r.targetType == "message" || r.targetType == "story") {
                                        TextButton(onClick = { vm.act({ apiCall { app.api.adminTalk(r.targetType, r.targetId, "remove") } }, showMessage, "Removed") }) { Text("Remove") }
                                        TextButton(onClick = { vm.act({ apiCall { app.api.adminTalk(r.targetType, r.targetId, "dismiss") } }, showMessage, "Dismissed") }) { Text("Dismiss") }
                                    } else {
                                        val type = when (r.targetType) { "post" -> "posts"; "comment" -> "comments"; else -> "dishes" }
                                        if (r.status == "hidden") {
                                            TextButton(onClick = { vm.act({ apiCall { app.api.adminContent(type, r.targetId, "restore") } }, showMessage, "Restored") }) { Text("Restore") }
                                        } else {
                                            TextButton(onClick = { vm.act({ apiCall { app.api.adminContent(type, r.targetId, "hide") } }, showMessage, "Hidden") }) { Text("Hide") }
                                        }
                                        TextButton(onClick = { vm.act({ apiCall { app.api.adminContent(type, r.targetId, "dismiss") } }, showMessage, "Dismissed") }) { Text("Dismiss") }
                                        r.dishId?.let { id -> TextButton(onClick = { onOpenDish(id) }) { Text("View") } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeldThumb(url: String?, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DishImage(url, null, label, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/**
 * Is the ranking working? Votes this week, what the fairness rules caught, how much Top
 * moved, how often people open what each tab shows, and the prediction check.
 */
@Composable
private fun HealthCard(h: AlgorithmHealth) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("health_card")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Algorithm health", style = MaterialTheme.typography.titleMedium)
            val week = h.votesPerDay.takeLast(7)
            val t = h.totals
            HealthLine("Votes in the last 7 days", "${week.sumOf { it.loves }} loves · ${week.sumOf { it.passes }} not for me")
            HealthLine("Dishes with 3+ votes", "${t.dishesWith3Votes} of ${t.liveDishes}")
            HealthLine("Verified-visit photos", "${t.verifiedPhotos} of ${t.userPhotos}")
            HealthLine("Own-place votes ignored", "${t.ownPlaceVotesIgnored}")
            HealthLine("Accounts voting in lockstep", "${t.lockstepAccounts}")
            HealthLine("Copied photos held / removed", "${t.copiesHeld} / ${t.copiesRemoved}")
            HealthLine("Dishes merged", "${t.dishesMerged}")
            h.topChange.forEach { c -> HealthLine("New in ${c.city}'s Top 20 this week", "${c.newInTop} of ${c.size}") }
            h.taps.forEach { tap ->
                val rate = if (tap.seen > 0) "${(100.0 * tap.opened / tap.seen).toInt()}%" else "–"
                HealthLine("Opened from ${tap.tab}", "$rate (${tap.opened} of ${tap.seen})")
            }
            val p = h.prediction
            Spacer(Modifier.height(4.dp))
            Text("Prediction check (last ${p.days} days)", style = MaterialTheme.typography.titleSmall)
            if (p.votes == 0 || p.accuracy == null) {
                Text("Not enough votes yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                HealthLine("Votes checked", "${p.votes}")
                HealthLine("Predicted right", "${(p.accuracy * 100).toInt()}% (always guessing: ${((p.alwaysLoveAccuracy ?: 0.0) * 100).toInt()}%)")
                HealthLine("Skill", p.skill?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "–")
                HealthLine("Skill without trust", p.skillWithoutTrust?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "–")
                Text(
                    "Each recent vote is predicted from only the votes cast before it, as if recommending the dish at that moment. Skill above 0 means the ranking knows more than guessing; 1 would be perfect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
