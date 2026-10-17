package app.foodfeed

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.foodfeed.data.UpdateActivity
import app.foodfeed.data.UpdateMessage
import app.foodfeed.data.UpdateReaction
import app.foodfeed.data.UserRef
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Phone notifications for messages, reactions and Activity. There is no push service: the
 * app asks the server what's new (GET /updates) every 30 seconds while it runs and every
 * 15 minutes in the background (WorkManager), and shows what it finds here.
 */
object Notifications {
    private const val MESSAGES = "messages"
    private const val ACTIVITY = "activity"
    private const val WORK = "foodfeed-updates"

    /** Lines shown per chat until it's opened, so a burst of messages reads like a thread. */
    private val threads = mutableMapOf<String, MutableList<Pair<String, String>>>()

    fun setUp(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New messages, message requests and reactions"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(ACTIVITY, "Activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Follows, comments, replies, mentions and likes"
                },
            )
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdatesWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun open(context: Context, link: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun name(u: UserRef) = u.displayName?.takeIf { it.isNotBlank() } ?: u.handle

    fun messagePreview(kind: String, body: String?): String = when (kind) {
        "photo" -> if (body.isNullOrBlank()) "📷 Sent a photo" else "📷 $body"
        "dish" -> "🍽️ Shared a dish" + (body?.let { ": $it" } ?: "")
        "place" -> "📍 Shared a place" + (body?.let { ": $it" } ?: "")
        "post" -> "🖼️ Shared a post" + (body?.let { ": $it" } ?: "")
        "story_reply" -> "Replied to your story: ${body.orEmpty()}"
        "story_like" -> "Liked your story ❤️"
        else -> body.orEmpty()
    }

    fun activityText(a: UpdateActivity): String = when (a.kind) {
        "follow" -> "started following you."
        "comment" -> "commented: ${a.commentBody.orEmpty()}"
        "reply" -> "replied to your comment: ${a.commentBody.orEmpty()}"
        "mention" -> "mentioned you: ${a.commentBody.orEmpty()}"
        "comment_like" -> "liked your comment: ${a.commentBody.orEmpty()}"
        "recommend" -> "loved the ${a.dishName ?: "dish"} you posted."
        "agree" -> "agreed with your pick: ${a.dishName ?: "a dish"}."
        else -> "has something new for you."
    }

    /** Shows what's new; [openChatId] is the chat on screen, which needs no notification. */
    @Synchronized
    @SuppressLint("MissingPermission") // allowed() checks it
    fun show(
        context: Context,
        messages: List<UpdateMessage>,
        reactions: List<UpdateReaction>,
        activity: List<UpdateActivity>,
        openChatId: String?,
    ) {
        if (!allowed(context)) return
        val nm = NotificationManagerCompat.from(context)
        try {
            messages.filter { it.conversationId != openChatId }.groupBy { it.conversationId }.forEach { (chat, list) ->
                val from = list.last().from
                val lines = threads.getOrPut(chat) { mutableListOf() }
                list.forEach { lines += name(it.from) to messagePreview(it.kind, it.body) }
                while (lines.size > 8) lines.removeAt(0)
                val sender = Person.Builder().setName(name(from)).setKey(from.handle).build()
                val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                lines.forEach { (_, text) -> style.addMessage(text, System.currentTimeMillis(), sender) }
                val request = list.any { it.folder == "request" }
                if (request) style.conversationTitle = "Message request"
                nm.notify(
                    "chat:$chat",
                    1,
                    NotificationCompat.Builder(context, MESSAGES)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(0xFFF04E23.toInt())
                        .setStyle(style)
                        .setContentTitle(name(from))
                        .setContentText(lines.last().second)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(open(context, "foodfeed://chat/$chat", chat.hashCode()))
                        .build(),
                )
            }
            reactions.filter { it.conversationId != openChatId }.forEach { r ->
                val about = messagePreview(r.kind, r.body).take(60)
                nm.notify(
                    "reaction:${r.messageId}",
                    2,
                    NotificationCompat.Builder(context, MESSAGES)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(0xFFF04E23.toInt())
                        .setContentTitle(name(r.from))
                        .setContentText("Reacted ${r.emoji} to your message" + if (about.isNotBlank()) ": $about" else "")
                        .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                        .setAutoCancel(true)
                        .setContentIntent(open(context, "foodfeed://chat/${r.conversationId}", r.conversationId.hashCode()))
                        .build(),
                )
            }
            activity.forEach { a ->
                val link = if (a.kind == "follow" || a.dishId == null) "foodfeed://user/${a.actor.handle}" else "foodfeed://dish/${a.dishId}"
                nm.notify(
                    "activity:${a.id}",
                    3,
                    NotificationCompat.Builder(context, ACTIVITY)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(0xFFF04E23.toInt())
                        .setContentTitle(name(a.actor))
                        .setContentText(activityText(a))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(activityText(a)))
                        .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                        .setGroup(ACTIVITY)
                        .setAutoCancel(true)
                        .setContentIntent(open(context, link, a.id.toInt()))
                        .build(),
                )
            }
        } catch (_: SecurityException) {
            // Permission was taken away between the check and the post.
        }
    }

    /** Clears a chat's notification once it's opened. */
    @Synchronized
    fun clearChat(context: Context, chatId: String) {
        threads.remove(chatId)
        NotificationManagerCompat.from(context).cancel("chat:$chatId", 1)
    }

    @Synchronized
    fun clearAll(context: Context) {
        threads.clear()
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/** Runs in the background every 15 minutes: asks the server what's new and notifies. */
class UpdatesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WorthAbiteApp).container
        container.ready.first { it }
        container.checkUpdates()
        return Result.success()
    }
}
