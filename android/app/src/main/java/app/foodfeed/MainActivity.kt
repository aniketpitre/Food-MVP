package app.foodfeed

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.foodfeed.ui.FoodFeedRoot
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.theme.FoodFeedTheme

class MainActivity : ComponentActivity() {
    private var deepLinkDishId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLinkDishId = dishIdFrom(intent)
        val container = (application as FoodFeedApp).container
        setContent {
            FoodFeedTheme {
                CompositionLocalProvider(LocalApp provides container) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        FoodFeedRoot(deepLinkDishId) { deepLinkDishId = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dishIdFrom(intent)?.let { deepLinkDishId = it }
    }

    override fun onStop() {
        super.onStop()
        (application as FoodFeedApp).container.flushImpressions()
    }

    /** foodfeed://dish/<uuid> from a share link. */
    private fun dishIdFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "foodfeed" || uri.host != "dish") return null
        return uri.lastPathSegment?.takeIf { Regex("^[0-9a-fA-F-]{36}$").matches(it) }
    }
}
