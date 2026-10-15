package app.foodfeed.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.LoginBody
import app.foodfeed.data.RegisterBody
import app.foodfeed.data.apiCall
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.Wordmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val register: Boolean = false,
    val email: String = "",
    val password: String = "",
    val handle: String = "",
    val displayName: String = "",
    val acceptTerms: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    val canSubmit: Boolean
        get() = !busy && email.contains('@') && password.length >= (if (register) 8 else 1) &&
            (!register || (Regex("^[a-z0-9_]{3,20}$").matches(handle) && acceptTerms))
}

class AuthViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun setMode(register: Boolean) = _state.update { it.copy(register = register, error = null) }
    fun setEmail(v: String) = _state.update { it.copy(email = v.trim()) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setHandle(v: String) = _state.update { it.copy(handle = v.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20)) }
    fun setDisplayName(v: String) = _state.update { it.copy(displayName = v.take(40)) }
    fun setAcceptTerms(v: Boolean) = _state.update { it.copy(acceptTerms = v) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = apiCall {
                    if (s.register) app.api.register(RegisterBody(s.email, s.password, s.handle, s.displayName.ifBlank { null }, true))
                    else app.api.login(LoginBody(s.email, s.password))
                }
                app.signedIn(result)
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }
}

@Composable
fun AuthScreen(vm: AuthViewModel, onDone: () -> Unit, onClose: () -> Unit, onOpenTerms: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.done) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onDone() }
    }
    AuthContent(
        state, vm::setMode, vm::setEmail, vm::setPassword, vm::setHandle, vm::setDisplayName,
        vm::setAcceptTerms, vm::submit, onClose, onOpenTerms,
    )
}

@Composable
fun AuthContent(
    state: AuthUiState,
    onMode: (Boolean) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onHandle: (String) -> Unit,
    onDisplayName: (String) -> Unit,
    onAcceptTerms: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
        }
        Wordmark()
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.register) "Join FoodFeed" else "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Recommend dishes, save them for later and post the ones you love.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!state.register, { onMode(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sign in") }
            SegmentedButton(state.register, { onMode(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Create account") }
        }
        OutlinedTextField(
            state.email, onEmail, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            state.password, onPassword, label = { Text(if (state.register) "Password (8+ characters)" else "Password") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        if (state.register) {
            OutlinedTextField(
                state.handle, onHandle, label = { Text("Handle") }, prefix = { Text("@") }, singleLine = true,
                supportingText = { Text("3–20 lowercase letters, numbers or _") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                state.displayName, onDisplayName, label = { Text("Name (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.clickable { onAcceptTerms(!state.acceptTerms) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.acceptTerms, onAcceptTerms)
                Text("I'm 18 or older and accept the ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Terms", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onOpenTerms),
                )
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        PrimaryButton(
            when {
                state.busy -> "Please wait…"
                state.register -> "Create account"
                else -> "Sign in"
            },
            onSubmit,
            Modifier.fillMaxWidth(),
            enabled = state.canSubmit,
        )
        TextButton(onClick = { onMode(!state.register) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (state.register) "Already have an account? Sign in" else "New here? Create an account")
        }
        Spacer(Modifier.height(24.dp))
    }
}
