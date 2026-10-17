package app.foodfeed

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.foodfeed.data.AppContainer
import app.foodfeed.data.PushTokenBody
import app.foodfeed.data.apiCall
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Instant notifications. When the server has Firebase set up, it sends this phone a tiny
 * wake-up (no content) whenever something new is waiting; the app then asks the server
 * what's new, as it does every 15 minutes anyway, and shows it. Firebase starts with the
 * public values our server hands out, kept here so a wake-up can start the app cold.
 */
object PushSetup {
    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** Starts Firebase from the values saved last time, so wake-ups work from a cold start. */
    fun startFromCache(context: Context): Boolean {
        val p = prefs(context)
        val appId = p.getString("appId", null) ?: return false
        return start(context, appId, p.getString("apiKey", "")!!, p.getString("projectId", "")!!, p.getString("senderId", "")!!)
    }

    private fun start(context: Context, appId: String, apiKey: String, projectId: String, senderId: String): Boolean =
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder().setApplicationId(appId).setApiKey(apiKey).setProjectId(projectId).setGcmSenderId(senderId).build(),
                )
            }
            true
        }.getOrDefault(false)

    /** Asks our server whether push is on; if so, starts Firebase and leaves our token there. */
    suspend fun sync(container: AppContainer) {
        val context = container.appContext
        if (container.session.value == null) return
        val config = runCatching { apiCall { container.api.pushConfig() } }.getOrNull() ?: return
        if (!config.enabled || config.appId == null) {
            prefs(context).edit().clear().apply()
            return
        }
        prefs(context).edit()
            .putString("appId", config.appId).putString("apiKey", config.apiKey).putString("projectId", config.projectId)
            .putString("senderId", config.senderId).apply()
        if (!start(context, config.appId, config.apiKey.orEmpty(), config.projectId.orEmpty(), config.senderId.orEmpty())) return
        val token = runCatching {
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            FirebaseMessaging.getInstance().token.await()
        }.getOrNull() ?: return
        register(container, token)
    }

    suspend fun register(container: AppContainer, token: String) {
        if (container.session.value == null) return
        runCatching { apiCall { container.api.savePushToken(PushTokenBody(token, container.city.value?.id)) } }
    }

    /** On sign-out: this phone stops getting the person's wake-ups. */
    suspend fun unregister(container: AppContainer) {
        if (FirebaseApp.getApps(container.appContext).isEmpty()) return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching { apiCall { container.api.deletePushToken(PushTokenBody(token, null)) } }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }
}

/** Receives the wake-ups and checks for what's new right away (WorkManager, expedited). */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "push-updates",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdatesWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build(),
        )
    }

    override fun onNewToken(token: String) {
        val app = applicationContext as WorthAbiteApp
        app.appScope.launch {
            app.container.ready.first { it }
            PushSetup.register(app.container, token)
        }
    }
}
