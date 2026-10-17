package app.foodfeed.ui.profile

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
import app.foodfeed.data.ApiException
import app.foodfeed.data.CodeBody
import app.foodfeed.data.SessionItem
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.auth.CodeStep
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.QuietButton
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
