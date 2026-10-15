package app.foodfeed.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.City
import app.foodfeed.data.DeviceLocation
import app.foodfeed.data.SelectedCity
import app.foodfeed.data.apiCall
import app.foodfeed.ui.theme.BrandGradient
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
    val done: Boolean = false,
)

/**
 * First-run setup: which FoodFeed server to use (skipped when the build has one baked in)
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

    fun loadCities() {
        _state.update { it.copy(loadingCities = true, message = null) }
        viewModelScope.launch {
            try {
                val list = apiCall { app.api.cities() }
                _state.update { it.copy(cities = list, loadingCities = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loadingCities = false, message = e.message) }
            }
        }
    }

    fun detect(context: Context) {
        _state.update { it.copy(detecting = true, message = null) }
        viewModelScope.launch {
            val loc = DeviceLocation.current(context)
            app.location.value = loc
            if (loc == null) {
                _state.update { it.copy(detecting = false, message = "Couldn't get your location. Pick your city below.") }
                return@launch
            }
            try {
                val near = apiCall { app.api.nearestCity(loc.lat, loc.lng) }
                val city = near.city
                if (city != null && near.inRange) {
                    choose(SelectedCity(city.id, city.name))
                } else {
                    _state.update {
                        it.copy(
                            detecting = false,
                            message = "FoodFeed is live in ${city?.name ?: "a few cities"} only for now. You can still browse it below.",
                        )
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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.detect(context)
    }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    OnboardingContent(
        state = state,
        onServerInput = vm::setServerInput,
        onConnect = vm::connect,
        onDetect = {
            if (DeviceLocation.hasPermission(context)) vm.detect(context)
            else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        },
        onChoose = { vm.choose(SelectedCity(it.id, it.name)) },
        onChangeServer = vm::backToServer,
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
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(72.dp).background(BrandGradient, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("🍛", fontSize = 34.sp) }
        Text("FoodFeed", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The dishes people in your city actually recommend, whatever the restaurant's rating.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        when (state.step) {
            OnboardingStep.Server -> {
                Text("Connect to your FoodFeed server", style = MaterialTheme.typography.titleMedium)
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
                    supportingText = state.serverError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onConnect, enabled = !state.testing && state.serverInput.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (state.testing) "Connecting…" else "Connect")
                }
            }
            OnboardingStep.City -> {
                Text("Show dishes near you?", style = MaterialTheme.typography.titleMedium)
                Text(
                    "We use your approximate location once to pick your city. It's never stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDetect, enabled = !state.detecting, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.LocationOn, null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (state.detecting) "Finding your city…" else "Use my location")
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Text("Or choose a city", style = MaterialTheme.typography.titleSmall)
                Column {
                    state.cities.forEach { c ->
                        ListItem(
                            headlineContent = { Text(c.name) },
                            supportingContent = { Text(if (c.isActive) listOfNotNull(c.state, "Live").joinToString(" · ") else "Coming soon") },
                            leadingContent = { Icon(Icons.Filled.LocationOn, null) },
                            modifier = if (c.isActive) Modifier.clickable { onChoose(c) } else Modifier,
                        )
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onChangeServer) { Text("Use a different server") }
            }
        }
    }
}
