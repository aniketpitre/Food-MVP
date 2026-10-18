package app.worthabite.ui.profile

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.worthabite.data.ApiException
import app.worthabite.data.ImageCompressor
import app.worthabite.data.PasswordChange
import app.worthabite.data.Person
import app.worthabite.data.ProfileUpdate
import app.worthabite.data.apiCall
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.Avatar
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.PersonRow
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.components.QuietButton
import app.worthabite.ui.theme.Ink
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.pressScale
import kotlinx.coroutines.launch

/** A plain top bar: back arrow and a title. */
@Composable
fun BackBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

private val usernameRule = Regex("^[a-z0-9_]{3,20}$")

/**
 * Edit profile: photo (pick from gallery, resized on the phone, location data dropped),
 * name, username, bio and who can comment on your posts.
 */
@Composable
fun EditProfileScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    val me = session?.user ?: return
    var name by remember { mutableStateOf(me.displayName.orEmpty()) }
    var username by remember { mutableStateOf(me.handle) }
    var bio by remember { mutableStateOf(me.bio.orEmpty()) }
    var policy by remember { mutableStateOf(me.commentPolicy) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            uploading = true
            scope.launch {
                try {
                    app.uploadAvatar(ImageCompressor.compress(context, uri))
                } catch (e: ApiException) {
                    showMessage(e.message)
                } catch (e: Exception) {
                    showMessage("Couldn't use that photo.")
                } finally {
                    uploading = false
                }
            }
        }
    }

    val cleanUsername = username.trim().lowercase()
    val usernameError = if (cleanUsername.isNotEmpty() && !usernameRule.matches(cleanUsername)) "3–20 letters, numbers or _" else null
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Edit profile", onBack)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Avatar(me.handle, me.displayName, 104.dp, photo = me.avatarUrl)
                    if (uploading) CircularProgressIndicator(Modifier.size(104.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (me.avatarUrl == null) "Add photo" else "Change photo", color = MaterialTheme.colorScheme.primary)
                    }
                    if (me.avatarUrl != null) {
                        TextButton(onClick = { scope.launch { runCatching { app.removeAvatar() }.onFailure { showMessage(it.message ?: "Couldn't remove.") } } }) {
                            Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedTextField(
                name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                username, { username = it.take(20) }, label = { Text("Username") }, prefix = { Text("@") }, singleLine = true,
                isError = usernameError != null,
                supportingText = { Text(usernameError ?: "People find and mention you with this.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                bio, { bio = it.take(160) }, label = { Text("Bio") }, minLines = 2, maxLines = 4,
                supportingText = { Text("${bio.length}/160") },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            KickerText("Who can comment on your posts")
            Segmented(
                listOf("everyone" to "Everyone", "followers" to "People I follow", "off" to "No one"),
                policy,
            ) { policy = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton(
                if (saving) "Saving…" else "Save",
                {
                    saving = true
                    error = null
                    scope.launch {
                        try {
                            app.updateProfile(
                                ProfileUpdate(
                                    displayName = name.trim(),
                                    username = cleanUsername.takeIf { it != me.handle },
                                    bio = bio.trim(),
                                    commentPolicy = policy,
                                ),
                            )
                            showMessage("Profile updated.")
                            onBack()
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            saving = false
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = !saving && usernameError == null && cleanUsername.isNotEmpty(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A pill row of choices with a ember thumb that slides to the chosen one. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val slot = maxWidth / options.size
        val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val x by animateDpAsState(slot * index, Motion.glide(), label = "seg")
        Box(Modifier.offset(x = x).width(slot).fillMaxSize().padding(4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.secondary))
        Row(Modifier.fillMaxSize()) {
            options.forEach { (key, label) ->
                val on = key == selected
                val fg by animateColorAsState(if (on) Ink else MaterialTheme.colorScheme.onSurface, Motion.enter(Motion.SHORT), label = "seg-fg")
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(key) },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1) }
            }
        }
    }
}

/** Followers and Following as two swipeable tabs, each a list with follow buttons. */
@Composable
fun FollowListScreen(
    handle: String,
    startWithFollowers: Boolean,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val pager = rememberPagerState(initialPage = if (startWithFollowers) 0 else 1) { 2 }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BackBar("@$handle", onBack)
        Segmented(listOf("0" to "Followers", "1" to "Following"), pager.currentPage.toString()) {
            scope.launch { pager.animateScrollToPage(it.toInt()) }
        }
        val mine = LocalApp.current.session.collectAsState().value?.user?.handle == handle
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            PeopleList(
                load = { api -> if (page == 0) api.followers(handle) else api.following(handle) },
                empty = if (page == 0) "No followers yet." else "Not following anyone yet.",
                onOpenUser = onOpenUser,
                onNeedSignIn = onNeedSignIn,
                showMessage = showMessage,
                // Your own followers can be removed, like Instagram.
                removable = mine && page == 0,
            )
        }
    }
}

/** Loads a list of people and lets you follow or unfollow each one in place. */
@Composable
fun PeopleList(
    load: suspend (app.worthabite.data.WorthAbiteApi) -> List<Person>,
    empty: String,
    onOpenUser: (String) -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
    removable: Boolean = false,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val people = remember { mutableStateListOf<Person>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        try {
            val list = apiCall { load(app.api) }
            people.clear()
            people.addAll(list)
            error = null
        } catch (e: ApiException) {
            error = e.message
        } finally {
            loading = false
        }
    }
    when {
        loading && people.isEmpty() -> LoadingBox()
        error != null && people.isEmpty() -> CenteredMessage("Couldn't load", error, Icons.Filled.Refresh, "Try again", { attempt++ })
        people.isEmpty() -> CenteredMessage(empty)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(people, key = { it.handle }) { p ->
                PersonRow(
                    p,
                    onOpen = { onOpenUser(p.handle) },
                    onFollow = {
                        if (app.session.value == null) return@PersonRow onNeedSignIn()
                        val on = !p.isFollowing
                        fun set(v: Boolean) {
                            val i = people.indexOfFirst { it.handle == p.handle }
                            if (i >= 0) people[i] = people[i].copy(isFollowing = v)
                        }
                        set(on)
                        scope.launch {
                            runCatching { app.setFollowing(p.handle, on) }
                                .onFailure {
                                    set(!on)
                                    showMessage(it.message ?: "Couldn't update.")
                                }
                        }
                    },
                    trailing = if (removable && !p.isMe) {
                        {
                            OutlinedButton(
                                onClick = {
                                    val i = people.indexOfFirst { it.handle == p.handle }
                                    if (i >= 0) people.removeAt(i)
                                    scope.launch {
                                        runCatching { app.removeFollower(p.handle) }
                                            .onSuccess { showMessage("Removed @${p.handle} from your followers.") }
                                            .onFailure {
                                                people.add(i.coerceIn(0, people.size), p)
                                                showMessage(it.message ?: "Couldn't remove.")
                                            }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp).testTag("remove_follower"),
                            ) { Text("Remove") }
                        }
                    } else null,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

/** Blocked accounts, each with an Unblock button. */
@Composable
fun BlockedScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val people = remember { mutableStateListOf<Person>() }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching { apiCall { app.api.blocked() } }.onSuccess { people.addAll(it) }.onFailure { showMessage(it.message ?: "Couldn't load.") }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        BackBar("Blocked accounts", onBack)
        when {
            loading -> LoadingBox()
            people.isEmpty() -> CenteredMessage("Nobody blocked", "People you block can't see your posts or comment on them, and you won't see theirs.", AppIcons.Block)
            else -> LazyColumn {
                items(people, key = { it.handle }) { p ->
                    PersonRow(p, onOpen = {}, onFollow = null, modifier = Modifier.animateItem(), trailing = {
                        QuietButton("Unblock", {
                            scope.launch {
                                runCatching { apiCall { app.api.unblock(p.handle) } }
                                    .onSuccess { people.remove(p); showMessage("Unblocked @${p.handle}.") }
                                    .onFailure { showMessage(it.message ?: "Couldn't unblock.") }
                            }
                        })
                    })
                }
            }
        }
    }
}

@Composable
fun ChangePasswordScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Change password", onBack)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                current, { current = it }, label = { Text("Current password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                next, { next = it }, label = { Text("New password (8+ characters)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton(
                if (busy) "Saving…" else "Change password",
                {
                    busy = true
                    scope.launch {
                        try {
                            val r = apiCall { app.api.changePassword(PasswordChange(current, next)) }
                            r.token?.let { app.replaceToken(it) }
                            showMessage("Password changed. Your other devices were signed out.")
                            onBack()
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = !busy && current.isNotEmpty() && next.length >= 8,
            )
        }
    }
}
