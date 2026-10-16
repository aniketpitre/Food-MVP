package app.foodfeed

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import app.foodfeed.ui.theme.LocalLiteMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.foodfeed.ui.FoodFeedRoot
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.LocalMediaUrl
import app.foodfeed.ui.theme.FoodFeedTheme

class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLink = routeFrom(intent)
        val container = (application as FoodFeedApp).container
        setContent {
            val mode by container.themeMode.collectAsState()
            val lite by container.liteModeSetting.collectAsState()
            val dark = when (mode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            FoodFeedTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalApp provides container,
                    LocalMediaUrl provides container::mediaUrl,
                    LocalLiteMode provides (lite == "on" || (lite == "auto" && container.isLowEndDevice)),
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        FoodFeedRoot(deepLink) { deepLink = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)?.let { deepLink = it }
    }

    override fun onStop() {
        super.onStop()
        (application as FoodFeedApp).container.flushImpressions()
    }

    /**
     * The screen a link opens: foodfeed://dish/<uuid> from a share link, and from
     * notifications foodfeed://chat/<uuid>, foodfeed://user/<handle> or foodfeed://activity.
     */
    private fun routeFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "foodfeed") return null
        val id = uri.lastPathSegment
        val uuid = Regex("^[0-9a-fA-F-]{36}$")
        return when (uri.host) {
            "dish" -> id?.takeIf { uuid.matches(it) }?.let { "dish/$it" }
            "chat" -> id?.takeIf { uuid.matches(it) }?.let { "chat/$it" }
            "user" -> id?.takeIf { Regex("^[a-z0-9_]{3,20}$").matches(it) }?.let { "user/$it" }
            "activity" -> "activity"
            else -> null
        }
    }
}
