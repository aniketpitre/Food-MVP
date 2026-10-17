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
import android.graphics.Bitmap
import androidx.work.ForegroundInfo
import app.foodfeed.data.UpdateActivity
import app.foodfeed.data.UpdateNearby
import app.foodfeed.ui.Format
import app.foodfeed.data.UpdateMessage
import app.foodfeed.data.UpdateReaction
import app.foodfeed.data.UserRef
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Phone notifications for messages, reactions, Activity, stories and posts from people you
 * follow, and new food near you. The app asks the server what's new (GET /updates) every
 * 30 seconds while it's on screen, every 15 minutes in the background (WorkManager), and
 * at once when the server pushes a wake-up (see Push.kt); it shows what it finds here.
 * Each kind has its own Android channel, so people can turn any of them off.
 */
object Notifications {
    private const val MESSAGES = "messages"
    private const val ACTIVITY = "activity"
    private const val FOLLOWING = "following"
    private const val NEARBY = "nearby"
    private const val SECURITY = "security"
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
                    description = "New followers, comments, replies, mentions and likes"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(FOLLOWING, "Stories and posts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New stories and dishes from people you follow"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(NEARBY, "Food near you", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "New dishes and places near you (3 a day at most)"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(SECURITY, "Account security", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A daily reminder while two-step sign-in is off"
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
        "story" -> "posted a new story. Take a look 👀"
        "post" -> "posted ${a.dishName ?: "a new dish"}" + (a.placeName?.let { " at $it" } ?: "") + " 🍽️ Worth a bite?"
        "story_like" -> "liked your story ❤️"
        else -> "has something new for you."
    }

    /** Where tapping an Activity notification goes. */
    fun activityLink(a: UpdateActivity): String = when {
        a.kind == "story" -> "foodfeed://stories/${a.actor.handle}"
        a.kind == "follow" || a.kind == "story_like" || a.dishId == null -> "foodfeed://user/${a.actor.handle}"
        else -> "foodfeed://dish/${a.dishId}"
    }

    /** A headline that makes you hungry, the same one for the same dish. */
    fun nearbyTitle(n: UpdateNearby): String {
        val lines = if (n.kind == "place") {
            listOf("📍 New spot near you", "🆕 Just opened on WorthAbite", "👀 A new place to try")
        } else {
            listOf("🔥 Fresh near you", "😋 Just landed nearby", "🤤 New bite near you", "🍽️ Worth a bite?")
        }
        return lines[Math.floorMod(n.dishId.hashCode(), lines.size)]
    }

    fun nearbyText(n: UpdateNearby): String {
        val away = Format.distance(n.distanceM)?.let { if (it == "Nearby") "right around the corner" else "$it away" }
        val dish = listOfNotNull(n.emoji, n.dishName).joinToString(" ")
        return if (n.kind == "place") {
            "${n.placeName}" + (away?.let { ", $it" } ?: n.locality?.let { " in $it" } ?: "") + ". People say try the $dish."
        } else {
            "$dish at ${n.placeName}" + (away?.let { ", $it" } ?: n.locality?.let { " in $it" } ?: "") + ". Go before everyone else does!"
        }
    }

    /** New food near you, with its photo when we have one. */
    @Synchronized
    @SuppressLint("MissingPermission") // allowed() checks it
    fun showNearby(context: Context, items: List<UpdateNearby>, photos: Map<String, Bitmap?>) {
        if (!allowed(context) || items.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        try {
            items.forEach { n ->
                val photo = photos[n.dishId]
                val text = nearbyText(n)
                val b = NotificationCompat.Builder(context, NEARBY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(0xFFF04E23.toInt())
                    .setContentTitle(nearbyTitle(n))
                    .setContentText(text)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setGroup(NEARBY)
                    .setAutoCancel(true)
                    .setContentIntent(open(context, "foodfeed://dish/${n.dishId}", n.dishId.hashCode()))
                if (photo != null) {
                    b.setLargeIcon(photo).setStyle(
                        NotificationCompat.BigPictureStyle().bigPicture(photo).bigLargeIcon(null as Bitmap?).setSummaryText(text),
                    )
                } else {
                    b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
                nm.notify("nearby:${n.dishId}", 4, b.build())
            }
        } catch (_: SecurityException) {
        }
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
                val link = activityLink(a)
                val channel = if (a.kind == "story" || a.kind == "post") FOLLOWING else ACTIVITY
                nm.notify(
                    "activity:${a.id}",
                    3,
                    NotificationCompat.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(0xFFF04E23.toInt())
                        .setContentTitle(name(a.actor))
                        .setContentText(activityText(a))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(activityText(a)))
                        .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                        .setGroup(channel)
                        .setAutoCancel(true)
                        .setContentIntent(open(context, link, a.id.toInt()))
                        .build(),
                )
            }
        } catch (_: SecurityException) {
            // Permission was taken away between the check and the post.
        }
    }

    /** The daily nudge to turn on two-step sign-in. Tapping it opens the setting. */
    @Synchronized
    @SuppressLint("MissingPermission") // allowed() checks it
    fun showReminder(context: Context, kind: String) {
        if (kind != "two_factor" || !allowed(context)) return
        val text = "Turn on two-step sign-in: we'll email you a code each time you sign in, so your password alone can't open your account. It takes 30 seconds."
        try {
            NotificationManagerCompat.from(context).notify(
                "reminder:two_factor",
                5,
                NotificationCompat.Builder(context, SECURITY)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(0xFFF04E23.toInt())
                    .setContentTitle("🔐 Lock your WorthAbite account")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(open(context, "foodfeed://security", 5))
                    .build(),
            )
        } catch (_: SecurityException) {
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
    /** Older Android runs a push-triggered check as a brief, silent foreground job. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        77,
        NotificationCompat.Builder(applicationContext, "activity")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Checking what's new")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build(),
    )

    override suspend fun doWork(): Result {
        val container = (applicationContext as WorthAbiteApp).container
        container.ready.first { it }
        container.checkUpdates()
        return Result.success()
    }
}
