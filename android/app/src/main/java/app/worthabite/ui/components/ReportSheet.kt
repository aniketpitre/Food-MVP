package app.worthabite.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.worthabite.data.ApiException
import app.worthabite.data.ReportBody
import app.worthabite.data.apiCall
import kotlinx.coroutines.launch

private val reasons = listOf(
    "wrong_place" to "Wrong place or dish",
    "not_food" to "Not food / not a real photo",
    "inappropriate" to "Inappropriate, or a person without consent",
    "spam" to "Spam or fake recommendations",
    "other" to "Something else",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    targetType: String,
    targetId: String?,
    onDismiss: () -> Unit,
    showMessage: (String) -> Unit,
    targetHandle: String? = null,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Report", style = MaterialTheme.typography.titleLarge)
            Text(
                "Reports are private. We review them every day.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            reasons.forEach { (key, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { reason = key }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = reason == key, onClick = { reason = key })
                    Text(label)
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { if (it.length <= 500) note = it },
                label = { Text("Details (optional)") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val r = reason ?: return@Button
                    sending = true
                    scope.launch {
                        try {
                            val res = apiCall {
                                app.api.report(ReportBody(targetType, targetId, targetHandle, r, note.ifBlank { null }))
                            }
                            showMessage(if (res.hidden) "Thanks. It's hidden while we review it." else "Thanks for reporting. We'll review it.")
                            onDismiss()
                        } catch (e: ApiException) {
                            showMessage(e.message)
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = reason != null && !sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (sending) "Sending…" else "Send report") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
