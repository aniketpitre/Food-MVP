package app.foodfeed.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.foodfeed.data.ApiException
import app.foodfeed.data.CodeBody
import app.foodfeed.data.PasswordBody
import app.foodfeed.data.apiCall
import app.foodfeed.ui.auth.CodeStep
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.theme.VegGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings › Two-step sign-in. Turning it on emails a code to the account's address (which
 * proves the address is yours); after that, every sign-in needs a fresh code from email.
 * Turning it off asks for the password.
 */
@Composable
fun TwoFactorScreen(onBack: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsState()
    val user = session?.user ?: return
    var challenge by remember { mutableStateOf<Pair<String, String>?>(null) } // id, email hint
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableStateOf(0) }
    var askPassword by remember { mutableStateOf(false) }

    fun start() {
        busy = true
        error = null
        scope.launch {
            try {
                val c = apiCall { app.api.startTwoFactor() }
                challenge = c.challengeId to c.emailHint
                code = ""
                launch { for (s in 30 downTo 0) { resendIn = s; delay(1000) } }
            } catch (e: ApiException) {
                error = e.message
            }
            busy = false
        }
    }

    fun confirm() {
        val (id, _) = challenge ?: return
        if (busy || code.length != 6) return
        busy = true
        scope.launch {
            try {
                app.updatedMe(apiCall { app.api.confirmTwoFactor(CodeBody(id, code)) })
                challenge = null
                showMessage("Two-step sign-in is on. Your email is verified.")
            } catch (e: ApiException) {
                error = e.message
                code = ""
            }
            busy = false
        }
    }

    challenge?.let { (_, hint) ->
        androidx.activity.compose.BackHandler { challenge = null }
        CodeStep(
            title = "Verify your email",
            body = "We sent a 6-digit code to $hint. Enter it to turn on two-step sign-in.",
            code = code,
            busy = busy,
            error = error,
            resendIn = resendIn,
            onCode = { v ->
                code = v.filter { it.isDigit() }.take(6)
                error = null
                if (code.length == 6) confirm()
            },
            onVerify = ::confirm,
            onResend = { if (resendIn == 0) start() },
            onBack = { challenge = null },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Two-step sign-in", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (user.twoFactor) "🛡️" else "🔐", fontSize = 48.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Two-step sign-in", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text(
                    if (user.twoFactor) "ON" else "OFF",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (user.twoFactor) VegGreen else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .background(
                            (if (user.twoFactor) VegGreen else MaterialTheme.colorScheme.error).copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("twofactor_state"),
                )
            }
            Text(
                if (user.twoFactor) {
                    "Each time you sign in, we email a 6-digit code to ${user.email}. Nobody can get into your account with just your password."
                } else {
                    "Add a second lock to your account: each time you sign in, we email a 6-digit code to ${user.email}. Someone who learns your password still can't get in."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (user.emailVerified) "✅" else "✉️")
                Spacer(Modifier.width(8.dp))
                Text(
                    if (user.emailVerified) "Email verified" else "Email not verified yet: turning this on verifies it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            if (user.twoFactor) {
                QuietButton("Turn off", { askPassword = true }, Modifier.fillMaxWidth().testTag("twofactor_off"))
            } else {
                PrimaryButton(if (busy) "Sending a code…" else "Turn on", ::start, Modifier.fillMaxWidth().testTag("twofactor_on"), enabled = !busy)
            }
        }
    }

    if (askPassword) {
        var password by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { Text("Turn off two-step sign-in?") },
            text = {
                Column {
                    Text("Enter your password. Your account will be protected by your password only.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        password, { password = it; wrong = null },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = wrong != null,
                        supportingText = wrong?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth().testTag("twofactor_password"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                app.updatedMe(apiCall { app.api.disableTwoFactor(PasswordBody(password)) })
                                askPassword = false
                                showMessage("Two-step sign-in is off.")
                            } catch (e: ApiException) {
                                wrong = e.message
                            }
                        }
                    },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.testTag("twofactor_off_confirm"),
                ) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { Text("Keep it on") } },
        )
    }
}
