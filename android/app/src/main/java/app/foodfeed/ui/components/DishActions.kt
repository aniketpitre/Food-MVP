package app.foodfeed.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.DishCard
import app.foodfeed.data.Engagement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Recommend / save / tried / share / maps, shared by every screen that shows a dish. */
class DishActions(
    private val app: AppContainer,
    private val scope: CoroutineScope,
    private val context: Context,
    private val onNeedSignIn: () -> Unit,
    private val showMessage: (String) -> Unit,
) {
    fun toggle(card: DishCard, kind: Engagement) {
        if (app.session.value == null) {
            // Remember what the guest wanted; it completes right after sign-in, no second tap.
            app.setPendingAction { app.toggle(card, kind) }
            onNeedSignIn()
            return
        }
        scope.launch {
            try {
                app.toggle(card, kind)
            } catch (e: ApiException) {
                showMessage(e.message)
            }
        }
    }

    fun share(card: DishCard) {
        scope.launch {
            val link = app.shareLink(card)
            val place = listOfNotNull(card.restaurant.name, card.restaurant.locality).joinToString(", ")
            val text = "Try the ${card.name} at $place 😋\n$link"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Share this dish").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openMaps(lat: Double, lng: Double, name: String) = openMaps(context, lat, lng, name)
}

fun openMaps(context: Context, lat: Double, lng: Double, name: String) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(geo)
    } catch (e: ActivityNotFoundException) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(web) }
    }
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun rememberDishActions(onNeedSignIn: () -> Unit, showMessage: (String) -> Unit): DishActions {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(app, scope, context) { DishActions(app, scope, context, onNeedSignIn, showMessage) }
}
