package app.worthabite.ui.components

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "Open now?" from opening hours in the OpenStreetMap style the map data and the app's hours
 * picker use: "24/7", "Mo-Su 08:00-22:00", "Mo-Sa 09:00-21:00; Su off", "Mo-Fr 11:00-15:00,19:00-23:30".
 * Hours past midnight ("18:00-02:00") run into the next day. Rules it can't read are skipped; with
 * none it can read, the answer is null (unknown) rather than a guess.
 */
object OpenHours {
    data class State(val open: Boolean, val label: String)

    private val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val dayRange = Regex("^(Mo|Tu|We|Th|Fr|Sa|Su)(?:-(Mo|Tu|We|Th|Fr|Sa|Su))?$")
    private val span = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$")

    /** For each weekday (0 = Monday), its open spans in minutes from midnight; null if unreadable. */
    fun parse(raw: String): Map<Int, List<IntRange>>? {
        val text = raw.trim()
        if (text == "24/7") return (0..6).associateWith { listOf(0..1440) }
        val week = mutableMapOf<Int, List<IntRange>>()
        var any = false
        for (rule in text.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = rule.split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) continue
            val ruleDays = parts[0].split(',').flatMap { token ->
                val m = dayRange.matchEntire(token) ?: return@flatMap emptyList()
                val a = days.indexOf(m.groupValues[1])
                val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(days::indexOf) ?: a
                if (a <= b) (a..b).toList() else (a..6).toList() + (0..b).toList()
            }
            if (ruleDays.isEmpty()) continue
            val spans = if (parts[1].trim() == "off") emptyList() else parts[1].split(',').mapNotNull { s ->
                val m = span.matchEntire(s.trim()) ?: return@mapNotNull null
                val (h1, m1, h2, m2) = m.destructured
                val from = h1.toInt() * 60 + m1.toInt()
                var to = h2.toInt() * 60 + m2.toInt()
                if (to <= from) to += 1440 // past midnight
                from..to
            }
            if (parts[1].trim() != "off" && spans.isEmpty()) continue
            ruleDays.forEach { week[it] = spans }
            any = true
        }
        return if (any) week else null
    }

    fun stateAt(raw: String, now: LocalDateTime = LocalDateTime.now()): State? {
        val week = parse(raw) ?: return null
        val today = now.dayOfWeek.value - 1
        val minute = now.hour * 60 + now.minute
        val yesterday = (today + 6) % 7
        // Open today, or still open from last night's late hours.
        val openSpan = week[today]?.firstOrNull { minute in it }
            ?: week[yesterday]?.firstOrNull { it.last > 1440 && minute + 1440 in it }?.let { (it.first - 1440)..(it.last - 1440) }
        if (openSpan != null) {
            return if (openSpan.first <= 0 && openSpan.last >= 1440) State(true, "Open 24 hours")
            else State(true, "Open now · until ${clock(openSpan.last % 1440)}")
        }
        val laterToday = week[today]?.filter { it.first > minute }?.minOfOrNull { it.first }
        if (laterToday != null) return State(false, "Closed now · opens ${clock(laterToday)}")
        for (ahead in 1..7) {
            val d = (today + ahead) % 7
            val first = week[d]?.minOfOrNull { it.first } ?: continue
            val dayName = if (ahead == 1) "tomorrow" else DayOfWeek.of(d + 1).name.lowercase().replaceFirstChar { it.uppercase() }
            return State(false, "Closed now · opens $dayName ${clock(first)}")
        }
        return State(false, "Closed")
    }

    private fun clock(minutes: Int): String = LocalTime.of((minutes / 60) % 24, minutes % 60).let {
        val h = it.hour % 12
        "${if (h == 0) 12 else h}${if (it.minute != 0) ":%02d".format(it.minute) else ""} ${if (it.hour < 12) "am" else "pm"}"
    }

    /** The string the server expects, from the simple hours picker. */
    fun build(days: String, open: String, close: String, allDay: Boolean): String = when {
        allDay && days == "Mo-Su" -> "24/7"
        allDay -> "$days 00:00-24:00" + if (days == "Mo-Sa") "; Su off" else if (days == "Mo-Fr") "; Sa,Su off" else ""
        else -> "$days $open-$close" + if (days == "Mo-Sa") "; Su off" else if (days == "Mo-Fr") "; Sa,Su off" else ""
    }

    val timePattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
}
