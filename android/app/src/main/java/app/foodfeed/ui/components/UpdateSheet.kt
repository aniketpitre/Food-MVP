package app.foodfeed.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.foodfeed.data.AppUpdate
import app.foodfeed.data.Updates
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.Turmeric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * "A new version is here": what's new, then one button that downloads the update inside
 * the app (with progress) and opens Android's installer on it. The first time, Android asks
 * to allow WorthAbite to install apps; the sheet sends people there and explains why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(update: AppUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    fun installNow(file: File) {
        if (!Updates.canInstall(context)) {
            note = "Allow WorthAbite to install updates on the next screen, then come back and tap Install."
            Updates.openInstallPermission(context)
        } else {
            runCatching { Updates.install(context, file) }.onFailure { note = "Couldn't open the installer." }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Sticker("New version", Turmeric, icon = AppIcons.Sparkle, tilt = -4f)
            Spacer(Modifier.height(14.dp))
            Text("WorthAbite ${update.version} is here", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            if (update.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    update.notes.forEach { line ->
                        Row(verticalAlignment = Alignment.Top) {
                            Spacer(
                                Modifier.padding(top = 8.dp).size(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "It installs over this version: you stay signed in and keep your saved dishes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(progress != null && apk == null, enter = expandVertically(Motion.enter()) + fadeIn()) {
                Column(Modifier.padding(top = 16.dp)) {
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Downloading… ${((progress ?: 0f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            note?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(18.dp))
            val mb = if (update.sizeBytes > 0) " (${"%.1f".format(update.sizeBytes / 1_048_576.0)} MB)" else ""
            PrimaryButton(
                text = when {
                    apk != null -> "Install"
                    progress != null -> "Downloading…"
                    else -> "Update now$mb"
                },
                onClick = {
                    val ready = apk
                    if (ready != null) {
                        installNow(ready)
                    } else if (progress == null) {
                        note = null
                        progress = 0f
                        scope.launch {
                            try {
                                val file = Updates.download(context, update) { progress = it }
                                apk = file
                                installNow(file)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                progress = null
                                note = "Download failed. Check your connection and try again."
                            }
                        }
                    }
                },
                enabled = apk != null || progress == null,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
