package app.foodfeed.ui.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import app.foodfeed.BuildConfig
import app.foodfeed.data.ApiException
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.openUrl
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeCity: () -> Unit,
    onChangeServer: () -> Unit,
    onAdmin: () -> Unit,
    onSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    val city by app.city.collectAsState()
    val server by app.serverUrl.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
        androidx.compose.foundation.layout.Row {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
        }
        Item(Icons.Filled.LocationOn, "City", city?.name ?: "Not chosen", onChangeCity)
        if (BuildConfig.DEFAULT_SERVER_URL.isBlank()) {
            Item(Icons.Filled.Settings, "Server", server.ifBlank { "Not set" }, onChangeServer)
        }
        HorizontalDivider()
        if (session == null) {
            Item(Icons.Filled.Lock, "Sign in", "Recommend, save and post dishes", onSignIn)
        } else {
            val user = session!!.user
            Item(Icons.Filled.Lock, "Signed in as @${user.handle}", user.email, null)
            if (user.role == "admin") Item(AppIcons.Shield, "Moderation", "Review new places and reports", onAdmin)
            Item(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", null) { scope.launch { app.signOut() } }
            Item(Icons.Filled.Delete, "Delete my account", "Removes your profile, photos and actions") { confirmDelete = true }
        }
        HorizontalDivider()
        Item(Icons.Filled.Info, "Privacy policy", null) { openUrl(context, "$server/privacy") }
        Item(Icons.Filled.Info, "Terms of use", null) { openUrl(context, "$server/terms") }
        Item(Icons.Filled.Info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", null)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This permanently removes your profile, every photo you posted and all your recommendations and saves. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            app.deleteAccount()
                            showMessage("Your account was deleted.")
                            onBack()
                        } catch (e: ApiException) {
                            showMessage(e.message)
                        }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String?, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        leadingContent = { Icon(icon, null) },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
