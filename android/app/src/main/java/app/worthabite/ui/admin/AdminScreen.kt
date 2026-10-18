package app.worthabite.ui.admin

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

data class AdminUiState(val loading: Boolean = true, val queue: AdminQueue? = null, val error: String? = null)

class AdminViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            try {
                _state.value = AdminUiState(false, apiCall { app.api.adminQueue() })
            } catch (e: ApiException) {
                _state.value = AdminUiState(false, _state.value.queue, e.message)
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
            q.pendingPlaces.isEmpty() && q.reported.isEmpty() -> CenteredMessage("All clear", "No new places or reports to review.", AppIcons.Shield)
            else -> LazyColumn(Modifier.fillMaxSize()) {
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
