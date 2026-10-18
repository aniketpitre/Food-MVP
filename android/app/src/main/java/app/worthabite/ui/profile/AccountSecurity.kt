package app.worthabite.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.worthabite.data.ApiException
import app.worthabite.data.CodeBody
import app.worthabite.data.EmailChangeCodes
import app.worthabite.data.EmailChangeConfirm
import app.worthabite.data.EmailChangeStart
import app.worthabite.ui.components.PrimaryButton
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.worthabite.data.SessionItem
import app.worthabite.data.apiCall
import app.worthabite.ui.Format
import app.worthabite.ui.auth.CodeStep
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.QuietButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings › Where you're signed in: every device with this account, when each was last
 * used, and a button to sign any of them out (or all but this one).
 */
@Composable
fun SessionsScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SessionItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        runCatching { apiCall { app.api.sessions() } }
            .onSuccess { items = it.items; error = null }
            .onFailure { error = it.message }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        BackBar("Where you're signed in", onBack)
        val list = items
        when {
            list == null && error != null -> CenteredMessage("Couldn't load", error, null, "Try again", { scope.launch { load() } })
            list == null -> Unit
            else -> LazyColumn(Modifier.fillMaxSize().testTag("sessions_list"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { s ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📱", fontSize = 26.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.device ?: "A phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (s.current) "This phone" else "Last active ${Format.timeAgo(s.lastSeenAt).ifBlank { "recently" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (!s.current) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                apiCall { app.api.endSession(s.id) }
                                                items = items?.filterNot { it.id == s.id }
                                                showMessage("Signed out of ${s.device ?: "that phone"}.")
                                            } catch (e: ApiException) {
                                                showMessage(e.message)
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("end_session_${s.id}"),
                                ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
                if (list.count { !it.current } > 0) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        QuietButton(
                            "Sign out of all other devices",
                            {
                                scope.launch {
                                    try {
                                        apiCall { app.api.endOtherSessions() }
                                        load()
                                        showMessage("Signed out everywhere else.")
                                    } catch (e: ApiException) {
                                        showMessage(e.message)
                                    }
                                }
                            },
                            Modifier.fillMaxWidth().testTag("end_other_sessions"),
                        )
                    }
                }
                item {
                    Text(
                        "Don't recognise a device? Sign it out, then change your password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Settings › Verify your email: a code to the account's address. */
@Composable
fun VerifyEmailScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    var challenge by remember { mutableStateOf<Pair<String, String>?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableStateOf(0) }
    fun start() {
        scope.launch {
            busy = true
            try {
                val c = apiCall { app.api.verifyEmailStart() }
                challenge = c.challengeId to c.emailHint
                code = ""
                error = null
                launch { for (s in 30 downTo 0) { resendIn = s; delay(1000) } }
            } catch (e: ApiException) {
                error = e.message
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (session?.user?.emailVerified == false) start() }
    if (session?.user?.emailVerified == true) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    CodeStep(
        title = "Verify your email",
        body = challenge?.let { "We sent a 6-digit code to ${it.second}." } ?: (error ?: "Sending a code…"),
        code = code,
        busy = busy,
        error = if (challenge != null) error else null,
        resendIn = resendIn,
        onCode = { v ->
            code = v.filter { it.isDigit() }.take(6)
            error = null
            val id = challenge?.first
            if (code.length == 6 && id != null && !busy) {
                scope.launch {
                    busy = true
                    try {
                        app.updatedMe(apiCall { app.api.verifyEmailConfirm(CodeBody(id, code)) })
                        showMessage("Your email is verified.")
                    } catch (e: ApiException) {
                        error = e.message
                        code = ""
                    }
                    busy = false
                }
            }
        },
        onVerify = {},
        onResend = { if (resendIn == 0) start() },
        onBack = onBack,
    )
}

/**
 * Settings › Change email. Your password first; then a code to your current email (it's
 * your account) and one to the new email (it's your address). Both must be right before
 * anything changes; then your other devices are signed out.
 */
@Composable
fun ChangeEmailScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    var newEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var codes by remember { mutableStateOf<EmailChangeCodes?>(null) }
    var oldCode by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableStateOf(0) }

    fun sendCodes() {
        busy = true
        error = null
        scope.launch {
            try {
                codes = apiCall { app.api.startEmailChange(EmailChangeStart(newEmail.trim(), password)) }
                oldCode = ""
                newCode = ""
                launch { for (s in 30 downTo 0) { resendIn = s; delay(1000) } }
            } catch (e: ApiException) {
                error = e.message
            }
            busy = false
        }
    }

    fun confirm() {
        val c = codes ?: return
        if (busy || oldCode.length != 6 || newCode.length != 6) return
        busy = true
        error = null
        scope.launch {
            try {
                val r = apiCall { app.api.confirmEmailChange(EmailChangeConfirm(c.oldChallengeId, oldCode, c.newChallengeId, newCode)) }
                r.token?.let { app.replaceToken(it) }
                app.updatedMe(r.user)
                // Navigation must happen on the main thread, whatever thread the reply came on.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    showMessage("Your email is now ${r.user.email}. Other devices were signed out.")
                    onBack()
                }
            } catch (e: ApiException) {
                error = e.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackBar("Change email", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val c = codes
            if (c == null) {
                Text("✉️", fontSize = 40.sp)
                Text("Now: ${session?.user?.email.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "We'll send a code to your current email and one to the new email. Both are needed, so nobody can move your account without access to both.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    newEmail, { newEmail = it.trim(); error = null }, label = { Text("New email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("change_email_new"),
                )
                OutlinedTextField(
                    password, { password = it; error = null }, label = { Text("Your password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("change_email_password"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryButton(
                    if (busy) "Sending codes…" else "Send codes", ::sendCodes,
                    Modifier.fillMaxWidth().testTag("change_email_send"),
                    enabled = !busy && newEmail.contains('@') && password.isNotEmpty(),
                )
            } else {
                Text("📬📬", fontSize = 40.sp)
                Text("Enter both codes", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    oldCode, { oldCode = it.filter(Char::isDigit).take(6); error = null },
                    label = { Text("Code sent to ${c.oldHint} (current)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("change_email_old_code"),
                )
                OutlinedTextField(
                    newCode, { newCode = it.filter(Char::isDigit).take(6); error = null },
                    label = { Text("Code sent to ${c.newHint} (new)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("change_email_new_code"),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PrimaryButton(
                    if (busy) "Checking…" else "Change my email", ::confirm,
                    Modifier.fillMaxWidth().testTag("change_email_confirm"),
                    enabled = !busy && oldCode.length == 6 && newCode.length == 6,
                )
                TextButton(onClick = { if (resendIn == 0) sendCodes() }, enabled = resendIn == 0, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (resendIn > 0) "Send new codes in ${resendIn}s" else "Send new codes")
                }
                Text(
                    "Codes work for 10 minutes. Changing your email signs you out on your other devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
