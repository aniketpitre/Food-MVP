package app.foodfeed.ui

import androidx.compose.ui.graphics.Color
import java.util.Locale

object Format {
    fun distance(meters: Int?): String? = when {
        meters == null -> null
        meters < 100 -> "Nearby"
        meters < 1000 -> "${(meters + 5) / 10 * 10} m"
        meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        else -> "${meters / 1000} km"
    }

    fun price(inr: Int?): String? = inr?.let { "₹$it" }

    /** Instagram-style age: "now", "5m", "3h", "2d", "4w", then a date. */
    fun timeAgo(iso: String, now: Long = System.currentTimeMillis()): String {
        val then = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return ""
        val s = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            s < 52 * 7 * 86_400 -> "${s / (7 * 86_400)}w"
            else -> java.time.Instant.ofEpochMilli(then).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }
    }

    /** "Recommended by 38 people", or an honest "New" label instead of "by 0". */
    fun recommended(n: Int): String = when (n) {
        0 -> "New · be the first to recommend"
        1 -> "Recommended by 1 person"
        else -> "Recommended by ${compact(n)} people"
    }

    fun compact(n: Int): String = when {
        n < 1000 -> n.toString()
        n < 10_000 -> String.format(Locale.US, "%.1fk", n / 1000.0).replace(".0k", "k")
        else -> "${n / 1000}k"
    }

    fun placeLine(name: String, locality: String?, distanceM: Int?): String =
        listOfNotNull(name, locality, distance(distanceM)).joinToString(" · ")

    fun initials(handle: String, displayName: String?): String {
        val source = displayName?.takeIf { it.isNotBlank() } ?: handle
        return source.split(' ', '_').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    }

    fun parseColor(hex: String?, fallback: Color): Color = try {
        if (hex == null || !hex.startsWith("#") || hex.length != 7) fallback
        else Color(0xFF000000 or hex.substring(1).toLong(16))
    } catch (e: NumberFormatException) {
        fallback
    }

    /** A stable pleasant colour per handle for avatar backgrounds. */
    fun avatarColor(handle: String): Color {
        val palette = listOf(0xFFEF4F1A, 0xFF7B3FE4, 0xFF1E8E3E, 0xFFD62976, 0xFF0B7FAB, 0xFFB7791F, 0xFF5B5BD6)
        return Color(palette[Math.floorMod(handle.hashCode(), palette.size)])
    }
}
