package app.worthabite.ui.onboarding

import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextFieldDefaults
import app.worthabite.data.LocationResult
import app.worthabite.ui.components.QuietButton
import kotlinx.coroutines.Job
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.KickerText
import app.worthabite.ui.components.Sticker
import app.worthabite.ui.theme.Paprika
import app.worthabite.ui.theme.Ink
import app.worthabite.ui.theme.Lagoon
import app.worthabite.ui.theme.Mint
import app.worthabite.ui.theme.Motion
import app.worthabite.ui.theme.Saffron
import app.worthabite.ui.theme.Turmeric
import app.worthabite.ui.theme.pressScale
import kotlinx.coroutines.delay
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.BuildConfig
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.City
import app.worthabite.data.DeviceLocation
import app.worthabite.data.SelectedCity
import app.worthabite.data.apiCall
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.components.Wordmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context

enum class OnboardingStep { Server, City }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.City,
    val serverInput: String = "",
    val testing: Boolean = false,
    val serverError: String? = null,
    val cities: List<City> = emptyList(),
    val loadingCities: Boolean = false,
    val detecting: Boolean = false,
    val message: String? = null,
    /** Location is switched off on the phone: offer a button to the settings. */
    val locationOff: Boolean = false,
    val cityQuery: String = "",
    val done: Boolean = false,
)

/**
 * First-run setup: which WorthAbite server to use (skipped when the build has one baked in)
 * and which city to show. Also reused from Settings to change either.
 */
class OnboardingViewModel(private val app: AppContainer, startAt: OnboardingStep?) : ViewModel() {
    private val _state = MutableStateFlow(
        OnboardingUiState(
            step = startAt ?: if (app.serverUrl.value.isBlank()) OnboardingStep.Server else OnboardingStep.City,
            serverInput = app.serverUrl.value,
        ),
    )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        if (_state.value.step == OnboardingStep.City) loadCities()
    }

    fun setServerInput(v: String) = _state.update { it.copy(serverInput = v.trim(), serverError = null) }

    fun connect() {
        val url = _state.value.serverInput
        if (url.isBlank()) return
        _state.update { it.copy(testing = true, serverError = null) }
        viewModelScope.launch {
            val ok = app.testServer(url)
            if (ok.isSuccess) {
                app.setServerUrl(url)
                _state.update { it.copy(testing = false, step = OnboardingStep.City) }
                loadCities()
            } else {
                val msg = (ok.exceptionOrNull() as? ApiException)?.message ?: "Couldn't connect."
                _state.update { it.copy(testing = false, serverError = "$msg Check the address and that the server is running.") }
            }
        }
    }

    private var search: Job? = null

    fun loadCities(query: String = _state.value.cityQuery) {
        _state.update { it.copy(loadingCities = true) }
        search?.cancel()
        search = viewModelScope.launch {
            if (query.isNotBlank()) delay(250) // wait for the typing to pause
            try {
                val list = apiCall { app.api.cities(query.ifBlank { null }, if (query.isBlank()) 12 else 30) }
                _state.update { it.copy(cities = list, loadingCities = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loadingCities = false, message = e.message) }
            }
        }
    }

    fun setCityQuery(q: String) {
        _state.update { it.copy(cityQuery = q) }
        loadCities(q)
    }

    fun permissionDenied() = _state.update {
        it.copy(message = "No problem. Pick your city below, or allow location later in Android settings.", locationOff = false)
    }

    fun detect(context: Context) {
        _state.update { it.copy(detecting = true, message = null, locationOff = false) }
        viewModelScope.launch {
            val found = DeviceLocation.locate(context)
            val loc = (found as? LocationResult.Found)?.coords
            app.location.value = loc
            if (loc == null) {
                _state.update {
                    it.copy(
                        detecting = false,
                        locationOff = found == LocationResult.Off,
                        message = (DeviceLocation.explain(found) ?: "Couldn't get your location.") + " Or pick your city below.",
                    )
                }
                return@launch
            }
            try {
                val near = apiCall { app.api.nearestCity(loc.lat, loc.lng) }
                val city = near.city
                if (city != null) {
                    // Outside the city proper (a village nearby) still means this city.
                    choose(SelectedCity(city.id, city.name))
                } else {
                    _state.update {
                        it.copy(detecting = false, message = "WorthAbite covers cities in India for now. Pick one below to browse.")
                    }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(detecting = false, message = e.message) }
            }
        }
    }

    fun choose(city: SelectedCity) {
        viewModelScope.launch {
            app.setCity(city)
            _state.update { it.copy(detecting = false, done = true) }
        }
    }

    fun backToServer() = _state.update { it.copy(step = OnboardingStep.Server) }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.detect(context) else vm.permissionDenied()
    }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    OnboardingContent(
        state = state,
        onServerInput = vm::setServerInput,
        onConnect = vm::connect,
        onDetect = {
            if (DeviceLocation.hasPermission(context)) vm.detect(context)
            else permission.launch(DeviceLocation.PERMISSIONS)
        },
        onChoose = { vm.choose(SelectedCity(it.id, it.name)) },
        onChangeServer = vm::backToServer,
        onCityQuery = vm::setCityQuery,
        onOpenLocationSettings = { DeviceLocation.openSettings(context) },
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    onServerInput: (String) -> Unit,
    onConnect: () -> Unit,
    onDetect: () -> Unit,
    onChoose: (City) -> Unit,
    onChangeServer: () -> Unit,
    onCityQuery: (String) -> Unit = {},
    onOpenLocationSettings: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        WelcomeHero()
    Column(
        Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {

        when (state.step) {
            OnboardingStep.Server -> {
                Text("Connect to your WorthAbite server", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This pilot app talks to a self-hosted server. Enter the address you were given, e.g. http://192.168.1.20:8080",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.serverInput,
                    onValueChange = onServerInput,
                    label = { Text("Server address") },
                    singleLine = true,
                    isError = state.serverError != null,
                    supportingText = if (state.serverError != null) { { Text(state.serverError) } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    if (state.testing) "Connecting…" else "Connect", onConnect, Modifier.fillMaxWidth(),
                    enabled = !state.testing && state.serverInput.isNotBlank(),
                )
            }
            OnboardingStep.City -> {
                Text("Show dishes near you?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "We use your approximate location once to pick your city. It's never stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton(
                    if (state.detecting) "Finding your city…" else "Use my location", onDetect, Modifier.fillMaxWidth(),
                    enabled = !state.detecting, leading = Icons.Filled.LocationOn,
                )
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (state.locationOff) {
                    QuietButton("Turn on location", onOpenLocationSettings, Modifier.fillMaxWidth(), leading = Icons.Filled.LocationOn)
                }
                KickerText("Or choose a city", Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    value = state.cityQuery,
                    onValueChange = onCityQuery,
                    placeholder = { Text("Search any city in India", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.cities.forEachIndexed { i, c -> CityCard(c, i) { onChoose(c) } }
                    if (state.cities.isEmpty() && state.cityQuery.isNotBlank() && !state.loadingCities) {
                        Text("No city called \"${state.cityQuery}\" yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Builds with a server baked in behave like a normal app: no server choice at all.
                if (BuildConfig.DEFAULT_SERVER_URL.isBlank()) {
                    TextButton(onClick = onChangeServer) { Text("Use a different server") }
                }
            }
        }
    }
    }
}

/**
 * The welcome block: a charcoal panel, like the logo's tile, where the wordmark's letters drop in one by one with a
 * bounce, the promise rises in, and three stickers slap on at angles.
 */
@Composable
private fun WelcomeHero() {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1500, easing = LinearEasing)) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
            .background(Ink)
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 30.dp),
    ) {
        // The pan lands first, then the name drops in letter by letter.
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(app.worthabite.R.drawable.logo_mark),
            contentDescription = "WorthAbite logo",
            modifier = Modifier.height(72.dp).graphicsLayer {
                val p = Motion.window(t.value, 0f, 0.3f) { Motion.backOut(it, 2.4f) }
                scaleX = 0.6f + 0.4f * p
                scaleY = 0.6f + 0.4f * p
                alpha = p.coerceIn(0f, 1f)
                rotationZ = (1f - p) * -20f
            },
        )
        Spacer(Modifier.height(8.dp))
        Row {
            "WorthAbite".forEachIndexed { i, ch ->
                Text(
                    ch.toString(),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontFamily = app.worthabite.ui.theme.WordmarkFamily,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = 0.sp,
                    ),
                    color = if (i < 5) Color.White else app.worthabite.ui.theme.Saffron,
                    modifier = Modifier.graphicsLayer {
                        val p = Motion.window(t.value, 0.04f * i, 0.3f) { Motion.backOut(it, 2.4f) }
                        translationY = (1f - p) * -140f
                        alpha = p.coerceIn(0f, 1f)
                        rotationZ = (1f - p) * (if (i % 2 == 0) -25f else 25f)
                    },
                )
            }
        }
        Text(
            "Is it worth a bite?",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.graphicsLayer {
                val p = Motion.window(t.value, 0.35f, 0.3f)
                alpha = p
                translationY = (1f - p) * 30f
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Ask the people who tried it. Dishes ranked by real people's verdicts, whatever the restaurant's rating.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.graphicsLayer {
                val p = Motion.window(t.value, 0.42f, 0.3f)
                alpha = p
                translationY = (1f - p) * 30f
            },
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Sticker("Crowd favourite", Paprika, content = Color.White, icon = AppIcons.Heart, tilt = -6f, appear = { Motion.window(t.value, 0.55f, 0.25f) { it } })
            Sticker("Hidden gem", Lagoon, content = Color.White, icon = AppIcons.Gem, tilt = 5f, appear = { Motion.window(t.value, 0.65f, 0.25f) { it } })
            Sticker("₹90", Turmeric, tilt = -3f, appear = { Motion.window(t.value, 0.75f, 0.25f) { it } })
        }
    }
}

/** A city as a rounded card that presses down; a mint chip counts its dishes. */
@Composable
private fun CityCard(c: City, index: Int, onClick: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(70L * index); appear.animateTo(1f, Motion.enter(Motion.LONG)) }
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = appear.value
                translationX = (1f - appear.value) * 60f
            }
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleLarge)
            c.state?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val busy = c.dishCount > 0
        Text(
            when {
                c.dishCount == 1 -> "1 dish"
                busy -> "${c.dishCount} dishes"
                else -> "Be the first"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (busy) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(if (busy) Mint else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
