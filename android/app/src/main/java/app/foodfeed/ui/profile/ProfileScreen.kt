package app.foodfeed.ui.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.BlockBody
import app.foodfeed.data.ProfilePost
import app.foodfeed.data.UserProfile
import app.foodfeed.data.apiCall
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.Pill
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.theme.StoryRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ProfileUiState(val loading: Boolean = true, val profile: UserProfile? = null, val error: String? = null)

/** handle == null means "my own profile". */
class ProfileViewModel(private val app: AppContainer, private val handle: String?) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.session, app.contentVersion) { s, _ -> s }.collect { load() }
        }
    }

    fun load() {
        val target = handle ?: app.session.value?.user?.handle
        if (target == null) {
            _state.value = ProfileUiState(loading = false)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = _state.value.profile == null, error = null)
            try {
                _state.value = ProfileUiState(false, apiCall { app.api.user(target) })
            } catch (e: ApiException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }
}

@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    isOwnTab: Boolean,
    onBack: (() -> Unit)?,
    onOpenDish: (String) -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var reporting by remember { mutableStateOf(false) }

    if (isOwnTab && session == null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
            CenteredMessage(
                "Your food profile",
                "Sign in to post dishes, see your recommendations and get credit for the places you discover.",
                AppIcons.Restaurant, "Sign in", onSignIn,
            )
        }
        return
    }
    when {
        state.loading -> LoadingBox()
        state.profile == null -> CenteredMessage("Couldn't load profile", state.error, Icons.Filled.Refresh, "Try again", vm::load)
        else -> {
            val profile = state.profile!!
            ProfileContent(
                profile = profile,
                thumbOf = { app.mediaUrl(it) },
                onBack = onBack,
                onOpenDish = onOpenDish,
                onSettings = if (profile.isMe) onSettings else null,
                onBlockToggle = {
                    if (session == null) onSignIn() else scope.launch {
                        try {
                            apiCall {
                                if (profile.blockedByMe) app.api.unblock(profile.handle) else app.api.block(BlockBody(profile.handle))
                            }
                            showMessage(if (profile.blockedByMe) "Unblocked @${profile.handle}" else "Blocked @${profile.handle}")
                            vm.load()
                        } catch (e: ApiException) {
                            showMessage(e.message)
                        }
                    }
                },
                onReport = { if (session == null) onSignIn() else reporting = true },
            )
            if (reporting) {
                ReportSheet("user", null, { reporting = false }, showMessage, targetHandle = profile.handle)
            }
        }
    }
}

@Composable
fun ProfileContent(
    profile: UserProfile,
    thumbOf: (String) -> String?,
    onBack: (() -> Unit)?,
    onOpenDish: (String) -> Unit,
    onSettings: (() -> Unit)?,
    onBlockToggle: () -> Unit,
    onReport: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(
                        "@${profile.handle}", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp),
                    )
                    if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.border(3.dp, StoryRing, CircleShape).padding(5.dp)) {
                        Avatar(profile.handle, profile.displayName, 76.dp)
                    }
                    Spacer(Modifier.width(20.dp))
                    Stat(profile.postCount, "dishes", Modifier.weight(1f))
                    Stat(profile.recommendations, "recommended", Modifier.weight(1f))
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    profile.displayName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    if (profile.role == "admin") Text("FoodFeed team", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (profile.postCount >= 10) Text("Local expert", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (!profile.isMe) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (profile.blockedByMe) Button(onClick = onBlockToggle, Modifier.weight(1f)) { Text("Unblock") }
                        else OutlinedButton(onClick = onBlockToggle, Modifier.weight(1f)) { Text("Block") }
                        OutlinedButton(onClick = onReport, Modifier.weight(1f)) { Text("Report") }
                    }
                }
            }
        }
        if (profile.posts.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Text(
                    if (profile.isMe) "Dishes you post show up here." else "No dishes yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(profile.posts, key = { it.id }) { p -> PostTile(p, thumbOf(p.thumbUrl)) { onOpenDish(p.dishId) } }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PostTile(p: ProfilePost, thumb: String?, onClick: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clickable(onClick = onClick)) {
        DishImage(thumb, p.placeholderColor, p.dishName, Modifier.fillMaxSize())
        val label = when {
            p.restaurantStatus == "pending" -> "In review"
            p.status == "hidden" -> "Hidden"
            else -> null
        }
        if (label != null) Pill(label, Color.Black.copy(alpha = 0.65f), Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        Text(
            p.dishName, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
        )
    }
}
