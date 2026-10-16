package app.foodfeed.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.KickerText
import app.foodfeed.ui.components.Sticker
import app.foodfeed.ui.theme.Chili
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.RollingNumber
import app.foodfeed.ui.theme.Saffron
import app.foodfeed.ui.theme.Turmeric
import app.foodfeed.ui.theme.dishHero
import app.foodfeed.ui.theme.pressScale
import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.Tag
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.components.QuietButton
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
                "Sign in to post dishes and get credit for the places you discover.",
                null, "Sign in", onSignIn,
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.padding(bottom = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    KickerText(
                        "@${profile.handle}",
                        modifier = Modifier.weight(1f).padding(start = if (onBack == null) 8.dp else 0.dp),
                    )
                    if (onSettings != null) IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // A spice-coloured ring that spins once into place around the avatar.
                    val spin = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { spin.animateTo(1f, tween(1100, easing = Motion.EmphasizedDecelerate)) }
                    Box(
                        Modifier
                            .size(104.dp)
                            .graphicsLayer { rotationZ = -200f * (1f - spin.value) }
                            .drawBehind {
                                drawCircle(
                                    Brush.sweepGradient(listOf(Saffron, Turmeric, Chili, Saffron)),
                                    style = Stroke(width = 4.dp.toPx()),
                                    radius = size.minDimension / 2 - 2.dp.toPx(),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(
                            profile.handle, profile.displayName, 88.dp,
                            Modifier.graphicsLayer { rotationZ = 200f * (1f - spin.value) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(profile.displayName ?: "@${profile.handle}", style = MaterialTheme.typography.headlineMedium)
                    if (profile.postCount >= 10) {
                        Spacer(Modifier.height(8.dp))
                        Sticker("Local expert", Turmeric, icon = AppIcons.Sparkle, tilt = -3f)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat(profile.postCount, if (profile.postCount == 1) "dish posted" else "dishes posted", Modifier.weight(1f))
                        Stat(profile.recommendations, "recommended", Modifier.weight(1f))
                    }
                }
                if (!profile.isMe) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton(if (profile.blockedByMe) "Unblock" else "Block", onBlockToggle, Modifier.weight(1f))
                        QuietButton("Report", onReport, Modifier.weight(1f))
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
        itemsIndexed(profile.posts, key = { _, p -> p.id }) { i, p -> PostTile(p, thumbOf(p.thumbUrl), i) { onOpenDish(p.dishId) } }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(20.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RollingNumber(value, MaterialTheme.typography.headlineMedium, MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A rounded tile that pops in a beat after the one before it. */
@Composable
private fun PostTile(p: ProfilePost, thumb: String?, index: Int, onClick: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(50L * index.coerceAtMost(12)); appear.animateTo(1f, Motion.bouncy()) }
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .aspectRatio(0.8f)
            .graphicsLayer {
                val a = appear.value
                alpha = a.coerceIn(0f, 1f)
                scaleX = 0.7f + 0.3f * a
                scaleY = 0.7f + 0.3f * a
            }
            .pressScale(interaction, 0.94f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        DishImage(thumb, p.placeholderColor, p.dishName, Modifier.fillMaxSize().dishHero(p.dishId, 18.dp))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f)),
            ),
        )
        val label = when {
            p.restaurantStatus == "pending" -> "In review"
            p.status == "hidden" -> "Hidden"
            else -> null
        }
        if (label != null) Tag(label, onPhoto = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        Text(
            p.dishName, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}
