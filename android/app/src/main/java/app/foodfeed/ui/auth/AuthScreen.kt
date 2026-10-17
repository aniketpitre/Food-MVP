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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
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
import androidx.compose.runtime.setValue
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
import app.foodfeed.data.AuthResult
import app.foodfeed.data.ChallengeBody
import app.foodfeed.data.CodeBody
import app.foodfeed.data.CodeChallenge
import app.foodfeed.data.EmailBody
import app.foodfeed.data.ForgotPasswordBody
import app.foodfeed.data.HandleBody
import app.foodfeed.data.LoginBody
import app.foodfeed.data.ResetPasswordBody
import app.foodfeed.data.RegisterBody
import app.foodfeed.data.apiCall
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.Wordmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which part of signing in is on screen. */
enum class AuthPage { Form, ForgotPassword, ResetPassword, ForgotUsername, ForgotEmail, VerifyEmail }

data class AuthUiState(
    val register: Boolean = false,
    /** Signing in: an email or a username. Signing up: the email. */
    val email: String = "",
    val password: String = "",
    val handle: String = "",
    val displayName: String = "",
    val acceptTerms: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    /** Two-step sign-in: the code we're waiting for, and where it went. */
    val challengeId: String? = null,
    val emailHint: String = "",
    val code: String = "",
    val resendIn: Int = 0,
    val page: AuthPage = AuthPage.Form,
    /** Whether the username typed is free (null while unknown), and why not. */
    val handleFree: Boolean? = null,
    val handleNote: String? = null,
    /** Recovery: what to look up, the new password, and a line to show. */
    val recover: String = "",
    val newPassword: String = "",
    val info: String? = null,
) {
    val canSubmit: Boolean
        get() = !busy && (if (register) email.contains('@') else email.isNotBlank()) &&
            password.length >= (if (register) 8 else 1) &&
            (!register || (Regex("^[a-z0-9_]{3,20}$").matches(handle) && acceptTerms && handleFree != false))
}

/** A hint about a new password while typing it, or null when it looks fine. */
fun passwordHint(password: String, handle: String = "", email: String = ""): String? = when {
    password.isEmpty() -> null
    password.length < 8 -> "At least 8 characters (${8 - password.length} more)"
    password.all { it.isDigit() } -> "Numbers only are easy to guess: add some letters"
    password.toSet().size == 1 -> "Too easy to guess"
    handle.length >= 3 && password.lowercase().contains(handle.lowercase()) -> "Don't use your username in your password"
    email.substringBefore('@').let { it.length >= 3 && password.lowercase().contains(it.lowercase()) } -> "Don't use your email in your password"
    else -> null
}

class AuthViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    private var handleCheck: kotlinx.coroutines.Job? = null

    fun setMode(register: Boolean) = _state.update { it.copy(register = register, error = null) }
    fun setEmail(v: String) = _state.update { it.copy(email = v.trim()) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setDisplayName(v: String) = _state.update { it.copy(displayName = v.take(40)) }
    fun setAcceptTerms(v: Boolean) = _state.update { it.copy(acceptTerms = v) }
    fun setRecover(v: String) = _state.update { it.copy(recover = v.trim(), error = null) }
    fun setNewPassword(v: String) = _state.update { it.copy(newPassword = v, error = null) }

    /** Typing a username checks (after a short pause) whether it's free. */
    fun setHandle(v: String) {
        val h = v.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20)
        _state.update { it.copy(handle = h, handleFree = null, handleNote = null) }
        handleCheck?.cancel()
        if (!Regex("^[a-z0-9_]{3,20}$").matches(h)) return
        handleCheck = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            runCatching { apiCall { app.api.handleAvailable(h) } }.onSuccess { r ->
                if (_state.value.handle == h) _state.update { it.copy(handleFree = r.available, handleNote = r.reason) }
            }
        }
    }

    fun setCode(v: String) {
        val digits = v.filter { it.isDigit() }.take(6)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == 6) {
            when (_state.value.page) {
                AuthPage.VerifyEmail -> verifyEmail()
                AuthPage.ResetPassword -> Unit // waits for the new password too
                else -> verify()
            }
        }
    }

    fun open(page: AuthPage) = _state.update {
        it.copy(
            page = page, error = null, info = null, code = "", newPassword = "",
            recover = when (page) {
                // Prefilled with what you typed (or just looked up).
                AuthPage.ForgotPassword -> it.recover.ifBlank { it.email }
                // An email in one, a username in the other: start empty.
                AuthPage.ForgotUsername, AuthPage.ForgotEmail -> ""
                else -> it.recover
            },
        )
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.register) {
                    val r = apiCall { app.api.register(RegisterBody(s.email, s.password, s.handle, s.displayName.ifBlank { null }, true)) }
                    app.signedIn(r)
                    // Confirm the email now (or later, from Settings).
                    val v = r.verify
                    if (v != null) {
                        _state.update { it.copy(busy = false, page = AuthPage.VerifyEmail, challengeId = v.challengeId, emailHint = v.emailHint, code = "") }
                        countdown()
                    } else {
                        _state.update { it.copy(busy = false, done = true) }
                    }
                    return@launch
                }
                val r = apiCall { app.api.login(LoginBody(login = s.email, password = s.password, email = s.email)) }
                if (r.twoFactor && r.challengeId != null) {
                    _state.update { it.copy(busy = false, challengeId = r.challengeId, emailHint = r.emailHint.orEmpty(), code = "") }
                    countdown()
                } else {
                    app.signedIn(AuthResult(r.token!!, r.user!!))
                    _state.update { it.copy(busy = false, done = true) }
                }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun verify() {
        val s = _state.value
        val id = s.challengeId ?: return
        if (s.busy || s.code.length != 6) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                app.signedIn(apiCall { app.api.verifyLogin(CodeBody(id, s.code)) })
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message, code = "") }
            }
        }
    }

    private fun verifyEmail() {
        val s = _state.value
        val id = s.challengeId ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                app.updatedMe(apiCall { app.api.verifyEmailConfirm(CodeBody(id, s.code)) })
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message, code = "") }
            }
        }
    }

    fun resend() {
        val s = _state.value
        val id = s.challengeId ?: return
        if (s.resendIn > 0) return
        viewModelScope.launch {
            try {
                val c = when (s.page) {
                    AuthPage.VerifyEmail -> apiCall { app.api.verifyEmailStart() }
                    AuthPage.ResetPassword -> apiCall { app.api.forgotPassword(ForgotPasswordBody(s.recover)) }.let { CodeChallenge(it.challengeId, "") }
                    else -> apiCall { app.api.resendLogin(ChallengeBody(id)) }
                }
                _state.update { it.copy(challengeId = c.challengeId, emailHint = c.emailHint.ifBlank { it.emailHint }, code = "", error = "We sent a new code.") }
                countdown()
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Back from the code to email and password (or, after signing up, "later"). */
    fun cancelCode() = _state.update {
        if (it.page == AuthPage.VerifyEmail) it.copy(done = true) else it.copy(challengeId = null, code = "", error = null)
    }

    // ---- Forgot password, username or email ----

    fun sendReset() {
        val s = _state.value
        if (s.busy || s.recover.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.forgotPassword(ForgotPasswordBody(s.recover)) }
                _state.update { it.copy(busy = false, page = AuthPage.ResetPassword, challengeId = r.challengeId, info = r.message, code = "", newPassword = "") }
                countdown()
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun resetPassword() {
        val s = _state.value
        val id = s.challengeId ?: return
        if (s.busy || s.code.length != 6) return
        passwordHint(s.newPassword)?.let { hint -> _state.update { it.copy(error = hint) }; return }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                app.signedIn(apiCall { app.api.resetPassword(ResetPasswordBody(id, s.code, s.newPassword)) })
                _state.update { it.copy(busy = false, done = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun sendUsername() {
        val s = _state.value
        if (s.busy || !s.recover.contains('@')) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.forgotUsername(EmailBody(s.recover)) }
                _state.update { it.copy(busy = false, info = r.message) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    fun findEmail() {
        val s = _state.value
        if (s.busy || s.recover.isBlank()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.forgotEmail(HandleBody(s.recover.removePrefix("@"))) }
                _state.update { it.copy(busy = false, info = "This account uses ${r.emailHint}. Sign in with it or with the username.", email = s.recover.removePrefix("@")) }
            } catch (e: ApiException) {
                _state.update { it.copy(busy = false, error = e.message) }
            }
        }
    }

    private fun countdown() {
        viewModelScope.launch {
            for (left in 30 downTo 0) {
                _state.update { it.copy(resendIn = left) }
                kotlinx.coroutines.delay(1000)
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
    when (state.page) {
        AuthPage.VerifyEmail -> {
            androidx.activity.compose.BackHandler(onBack = vm::cancelCode)
            CodeStep(
                title = "Confirm your email",
                body = "We sent a 6-digit code to ${state.emailHint}. It helps you get back in if you forget your password.",
                code = state.code, busy = state.busy, error = state.error, resendIn = state.resendIn,
                onCode = vm::setCode, onVerify = { vm.setCode(state.code) }, onResend = vm::resend, onBack = vm::cancelCode,
                onLater = vm::cancelCode,
            )
            return
        }
        AuthPage.ForgotPassword, AuthPage.ResetPassword, AuthPage.ForgotUsername, AuthPage.ForgotEmail -> {
            androidx.activity.compose.BackHandler { vm.open(AuthPage.Form) }
            RecoveryContent(state, vm)
            return
        }
        AuthPage.Form -> Unit
    }
    if (state.challengeId != null) {
        androidx.activity.compose.BackHandler(onBack = vm::cancelCode)
        CodeStep(
            title = "Check your email",
            body = "We sent a 6-digit code to ${state.emailHint}. Enter it to sign in.",
            code = state.code,
            busy = state.busy,
            error = state.error,
            resendIn = state.resendIn,
            onCode = vm::setCode,
            onVerify = vm::verify,
            onResend = vm::resend,
            onBack = vm::cancelCode,
        )
        return
    }
    AuthContent(
        state, vm::setMode, vm::setEmail, vm::setPassword, vm::setHandle, vm::setDisplayName,
        vm::setAcceptTerms, vm::submit, onClose, onOpenTerms,
        onForgotPassword = { vm.open(AuthPage.ForgotPassword) },
        onForgotUsername = { vm.open(AuthPage.ForgotUsername) },
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
    onForgotPassword: () -> Unit = {},
    onForgotUsername: () -> Unit = {},
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
            if (state.register) "Join the table" else "Welcome back",
            style = MaterialTheme.typography.displaySmall,
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
            state.email, onEmail, label = { Text(if (state.register) "Email" else "Email or username") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("auth_login"),
        )
        var show by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        OutlinedTextField(
            state.password, onPassword, label = { Text(if (state.register) "Password (8+ characters)" else "Password") },
            singleLine = true, visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
            supportingText = if (state.register) passwordHint(state.password, state.handle, state.email)?.let { { Text(it) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("auth_password"),
        )
        if (state.register) {
            OutlinedTextField(
                state.handle, onHandle, label = { Text("Username") }, prefix = { Text("@") }, singleLine = true,
                isError = state.handleFree == false,
                supportingText = {
                    Text(
                        when {
                            state.handleFree == true -> "✓ @${state.handle} is free"
                            state.handleNote != null -> state.handleNote
                            else -> "3–20 lowercase letters, numbers or _"
                        },
                        color = if (state.handleFree == true) app.foodfeed.ui.theme.VegGreen else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("auth_handle"),
            )
            OutlinedTextField(
                state.displayName, onDisplayName, label = { Text("Name (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.clickable { onAcceptTerms(!state.acceptTerms) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.acceptTerms, onAcceptTerms)
                Text("I accept the ", style = MaterialTheme.typography.bodyMedium)
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
            Modifier.fillMaxWidth().testTag("auth_submit"),
            enabled = state.canSubmit,
        )
        if (!state.register) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onForgotPassword, modifier = Modifier.testTag("forgot_password")) { Text("Forgot password?") }
                TextButton(onClick = onForgotUsername, modifier = Modifier.testTag("forgot_username")) { Text("Forgot username or email?") }
            }
        }
        TextButton(onClick = { onMode(!state.register) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (state.register) "Already have an account? Sign in" else "New here? Create an account")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Forgot password (code + new password), forgot username (emailed), forgot email (a hint). */
@Composable
private fun RecoveryContent(state: AuthUiState, vm: AuthViewModel) {
    // Once there's an answer, the keyboard goes away so the next steps are in view.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(state.info) { if (state.info != null) focus.clearFocus() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = { vm.open(AuthPage.Form) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        when (state.page) {
            AuthPage.ForgotPassword -> {
                Text("🔑", fontSize = 44.sp)
                Text("Forgot your password?", style = MaterialTheme.typography.displaySmall)
                Text("Enter your email or username. We'll email you a code to set a new password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    state.recover, vm::setRecover, label = { Text("Email or username") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("recover_input"),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryButton(if (state.busy) "Sending…" else "Send me a code", vm::sendReset, Modifier.fillMaxWidth().testTag("recover_send"), enabled = !state.busy && state.recover.isNotBlank())
            }
            AuthPage.ResetPassword -> {
                Text("📬", fontSize = 44.sp)
                Text("Set a new password", style = MaterialTheme.typography.displaySmall)
                Text(state.info ?: "If an account matches, we emailed it a 6-digit code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    state.code, vm::setCode, label = { Text("6-digit code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().testTag("reset_code"),
                )
                OutlinedTextField(
                    state.newPassword, vm::setNewPassword, label = { Text("New password (8+ characters)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = passwordHint(state.newPassword)?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("reset_password"),
                )
                state.error?.let { Text(it, color = if (it.startsWith("We sent")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                PrimaryButton(
                    if (state.busy) "Saving…" else "Save and sign in", vm::resetPassword, Modifier.fillMaxWidth().testTag("reset_submit"),
                    enabled = !state.busy && state.code.length == 6 && state.newPassword.length >= 8,
                )
                TextButton(onClick = vm::resend, enabled = state.resendIn == 0, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (state.resendIn > 0) "Send a new code in ${state.resendIn}s" else "Send a new code")
                }
                Text("Setting a new password signs you out on every other device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AuthPage.ForgotUsername, AuthPage.ForgotEmail -> {
                val byEmail = state.page == AuthPage.ForgotUsername
                Text("🧭", fontSize = 44.sp)
                Text("Find your account", style = MaterialTheme.typography.displaySmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(byEmail, { vm.open(AuthPage.ForgotUsername) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("I forgot my username") }
                    SegmentedButton(!byEmail, { vm.open(AuthPage.ForgotEmail) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("I forgot my email") }
                }
                Text(
                    if (byEmail) "Enter your email and we'll send your username there." else "Enter your username and we'll show which email it uses.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    state.recover, vm::setRecover, label = { Text(if (byEmail) "Email" else "Username") }, singleLine = true,
                    prefix = { if (!byEmail) Text("@") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (byEmail) KeyboardType.Email else KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth().testTag("recover_input"),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.info?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("recover_info")) }
                PrimaryButton(
                    if (state.busy) "Please wait…" else if (byEmail) "Email me my username" else "Find my email",
                    if (byEmail) vm::sendUsername else vm::findEmail,
                    Modifier.fillMaxWidth().testTag("recover_send"),
                    enabled = !state.busy && (if (byEmail) state.recover.contains('@') else state.recover.isNotBlank()),
                )
                if (state.info != null) {
                    TextButton(onClick = { vm.open(AuthPage.Form) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back to sign in") }
                    TextButton(onClick = { vm.open(AuthPage.ForgotPassword) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Forgot the password too? Reset it") }
                }
            }
            else -> Unit
        }
    }
}

/**
 * Enter the 6-digit code from the email: one big box that fills in as you type (or paste),
 * checked as soon as the sixth digit is in. Used at sign-in and to turn two-step on.
 */
@Composable
fun CodeStep(
    title: String,
    body: String,
    code: String,
    busy: Boolean,
    error: String?,
    resendIn: Int,
    onCode: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: (() -> Unit)?,
    onBack: () -> Unit,
    onLater: (() -> Unit)? = null,
) {
    val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }
        Text("🔐", fontSize = 44.sp)
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            code,
            onCode,
            singleLine = true,
            enabled = !busy,
            placeholder = { Text("••••••", style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 12.sp)) },
            textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 12.sp, fontWeight = FontWeight.Bold),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("code_input"),
        )
        error?.let { Text(it, color = if (it.startsWith("We sent")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        PrimaryButton(if (busy) "Checking…" else "Verify", onVerify, Modifier.fillMaxWidth().testTag("code_verify"), enabled = !busy && code.length == 6)
        if (onResend != null) {
            TextButton(onClick = onResend, enabled = resendIn == 0, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("code_resend")) {
                Text(if (resendIn > 0) "Send a new code in ${resendIn}s" else "Send a new code")
            }
        }
        if (onLater != null) {
            TextButton(onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("code_later")) { Text("I'll do it later") }
        }
        Text(
            "Didn't get it? Check spam, or wait a minute. The code works for 10 minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
