package app.foodfeed.ui.profile

import app.foodfeed.data.ProfileUpdate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.foodfeed.BuildConfig
import app.foodfeed.data.ApiException
import app.foodfeed.data.Updates
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.openUrl
import kotlinx.coroutines.launch

/**
 * Settings, grouped like Instagram's: your account, privacy and safety, how the app looks
 * and behaves (theme, Lite mode for slower phones), and support. Sign out and Delete account
 * at the bottom.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onChangeCity: () -> Unit,
    onChangeServer: () -> Unit,
    onAdmin: () -> Unit,
    onSignIn: () -> Unit,
    showMessage: (String) -> Unit,
    onEditProfile: () -> Unit = {},
    onPassword: () -> Unit = {},
    onBlocked: () -> Unit = {},
    onTaste: () -> Unit = {},
    onTwoFactor: () -> Unit = {},
    onSessions: () -> Unit = {},
    onVerifyEmail: () -> Unit = {},
    onChangeEmail: () -> Unit = {},
) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    val city by app.city.collectAsState()
    val server by app.serverUrl.collectAsState()
    val theme by app.themeMode.collectAsState()
    val lite by app.liteModeSetting.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val user = session?.user
            if (user == null) {
                Group("Account")
                Item(Icons.Filled.Lock, "Sign in", "Follow people, comment, recommend and post", onClick = onSignIn)
            } else {
                Group("Account")
                Item(Icons.Filled.Person, "Edit profile", "Photo, name, username, bio", onClick = onEditProfile)
                Item(AppIcons.Heart, "Your taste", "What you love, veg or not, and how spicy", onClick = onTaste)
                if (!user.emailVerified) {
                    Item(AppIcons.Bell, "Verify your email", "${user.email}: confirm it so you can always get back in", onClick = onVerifyEmail)
                }
                Item(AppIcons.Send, "Change email", user.email, onClick = onChangeEmail)
                Item(Icons.Filled.Lock, "Change password", "Your other devices will be signed out", onClick = onPassword)
                Item(Icons.Filled.Person, "Where you're signed in", "See your devices and sign any of them out", onClick = onSessions)
                Item(
                    AppIcons.Shield, "Two-step sign-in",
                    if (user.twoFactor) "On: a code by email each time you sign in" else "Off. Turn on to protect your account",
                    onClick = onTwoFactor,
                )
                Item(
                    AppIcons.Bell, "Notifications",
                    if (app.notificationsAllowed()) "Messages, activity, stories and posts, food near you. Tap to choose" else "Off. Tap to turn on in Android settings",
                ) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
                Group("Privacy and safety")
                Item(
                    AppIcons.Shield, "Comments on your posts",
                    when (user.commentPolicy) {
                        "followers" -> "People you follow"
                        "off" -> "Turned off"
                        else -> "Everyone"
                    },
                    onClick = onEditProfile,
                )
                Item(
                    AppIcons.Send, "Who can share dishes with you",
                    if (user.dmPolicy == "off") "Nobody" else "Your followers and the people you follow",
                ) {
                    val next = if (user.dmPolicy == "off") "everyone" else "off"
                    scope.launch {
                        try {
                            app.updateProfile(ProfileUpdate(dmPolicy = next))
                        } catch (e: ApiException) {
                            showMessage(e.message)
                        }
                    }
                }
                Item(AppIcons.Block, "Blocked accounts", null, onClick = onBlocked)
                if (user.role == "admin") Item(AppIcons.Shield, "Moderation", "Review new places and reports", onClick = onAdmin)
            }

            Group("App")
            Item(Icons.Filled.LocationOn, "City", city?.name ?: "Not chosen", onClick = onChangeCity)
            Item(
                AppIcons.Palette, "Theme",
                when (theme) {
                    "light" -> "Light"
                    "dark" -> "Dark"
                    else -> "Same as phone"
                },
            ) {
                val next = when (theme) {
                    "system" -> "light"
                    "light" -> "dark"
                    else -> "system"
                }
                scope.launch { app.setThemeMode(next) }
            }
            Item(
                AppIcons.Bolt, "Lite mode",
                when (lite) {
                    "on" -> "On: fewer effects, smoother on slower phones"
                    "off" -> "Off: full effects"
                    else -> if (app.isLowEndDevice) "Automatic (on for this phone)" else "Automatic (off for this phone)"
                },
            ) {
                val next = when (lite) {
                    "auto" -> "on"
                    "on" -> "off"
                    else -> "auto"
                }
                scope.launch { app.setLiteMode(next) }
            }
            if (BuildConfig.DEFAULT_SERVER_URL.isBlank()) {
                Item(Icons.Filled.Settings, "Server", server.ifBlank { "Not set" }, onClick = onChangeServer)
            }
            Item(
                Icons.Filled.Info, "Version ${BuildConfig.VERSION_NAME}",
                if (checking) "Checking for updates…" else "Tap to check for updates",
            ) {
                if (!Updates.enabled) {
                    showMessage("This build doesn't update itself.")
                } else if (!checking) {
                    checking = true
                    scope.launch {
                        val found = app.checkForUpdate()
                        checking = false
                        if (found == null) showMessage("You're on the latest version.")
                    }
                }
            }

            Group("Support")
            Item(Icons.Filled.Info, "Privacy policy", null) { openUrl(context, "$server/privacy") }
            Item(Icons.Filled.Info, "Terms of use", null) { openUrl(context, "$server/terms") }

            if (user != null) {
                Group("")
                Item(Icons.AutoMirrored.Filled.ExitToApp, "Sign out", "@${user.handle}") { scope.launch { app.signOut() } }
                Item(Icons.Filled.Delete, "Delete my account", "Removes your profile, photos and actions", danger = true) { confirmDelete = true }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This permanently removes your profile, every photo and comment you posted and all your recommendations, saves and follows. It can't be undone.") },
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
private fun Group(title: String) {
    if (title.isEmpty()) Spacer(Modifier.height(12.dp))
    else KickerText(title, Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String?, danger: Boolean = false, onClick: (() -> Unit)?) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = if (subtitle != null) { { Text(subtitle) } } else null,
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
