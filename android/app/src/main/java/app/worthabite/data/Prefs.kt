package app.worthabite.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "worthabite")

/** Everything the app remembers between launches. Never stores the user's location. */
data class StoredState(
    val serverUrl: String,
    val token: String?,
    val user: User?,
    val cityId: Int?,
    val cityName: String?,
    val deviceId: String,
    val themeMode: String = "system",
    val liteMode: String = "auto",
)

class Prefs(private val context: Context) {
    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val user = stringPreferencesKey("user")
        val cityId = intPreferencesKey("city_id")
        val cityName = stringPreferencesKey("city_name")
        val deviceId = stringPreferencesKey("device_id")
        val themeMode = stringPreferencesKey("theme_mode")
        val liteMode = stringPreferencesKey("lite_mode")
        val updatesSince = stringPreferencesKey("updates_since")
        val nearbyDay = stringPreferencesKey("nearby_day")
        val nearbyCount = intPreferencesKey("nearby_count")
        val recentSearches = stringPreferencesKey("recent_searches")
    }

    suspend fun resetNearbyAllowance() = context.dataStore.edit { it.remove(Keys.nearbyDay); it.remove(Keys.nearbyCount) }

    /**
     * Food-near-you notifications are capped at 3 a day so they stay a treat, not noise.
     * Returns how many of [wanted] may be shown now, and counts them.
     */
    suspend fun takeNearbyAllowance(wanted: Int, perDay: Int = 3): Int {
        if (wanted <= 0) return 0
        var granted = 0
        context.dataStore.edit {
            val today = java.time.LocalDate.now().toString()
            val used = if (it[Keys.nearbyDay] == today) it[Keys.nearbyCount] ?: 0 else 0
            granted = minOf(wanted, perDay - used).coerceAtLeast(0)
            it[Keys.nearbyDay] = today
            it[Keys.nearbyCount] = used + granted
        }
        return granted
    }

    /** Server time of the last check for notifications (null: start from now). */
    suspend fun updatesSince(): String? = context.dataStore.data.first()[Keys.updatesSince]

    suspend fun saveUpdatesSince(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(Keys.updatesSince) else it[Keys.updatesSince] = value
    }

    suspend fun load(defaultServerUrl: String): StoredState {
        val p = context.dataStore.data.first()
        var deviceId = p[Keys.deviceId]
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            context.dataStore.edit { it[Keys.deviceId] = deviceId }
        }
        val stored = p[Keys.token]
        val token = stored?.let { TokenCipher.open(it) }
        // A token saved before encryption is sealed now; one that can't be opened any more
        // (the phone's key was reset) means signing in again.
        if (stored != null && token != null && !TokenCipher.isSealed(stored)) {
            context.dataStore.edit { it[Keys.token] = TokenCipher.seal(token) }
        }
        return StoredState(
            serverUrl = p[Keys.serverUrl] ?: defaultServerUrl,
            token = token,
            user = p[Keys.user]?.let { runCatching { AppJson.decodeFromString(User.serializer(), it) }.getOrNull() },
            cityId = p[Keys.cityId],
            cityName = p[Keys.cityName],
            deviceId = deviceId,
            themeMode = p[Keys.themeMode] ?: "system",
            liteMode = p[Keys.liteMode] ?: "auto",
        )
    }

    suspend fun saveThemeMode(mode: String) = context.dataStore.edit { it[Keys.themeMode] = mode }

    /** What you searched for lately, newest first (kept on this phone only). */
    suspend fun recentSearches(): List<String> =
        context.dataStore.data.first()[Keys.recentSearches]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun saveRecentSearches(list: List<String>) = context.dataStore.edit { it[Keys.recentSearches] = list.joinToString("\n") }

    suspend fun saveLiteMode(mode: String) = context.dataStore.edit { it[Keys.liteMode] = mode }

    suspend fun saveServerUrl(url: String) = context.dataStore.edit { it[Keys.serverUrl] = url }

    suspend fun saveSession(token: String?, user: User?) = context.dataStore.edit {
        if (token == null || user == null) {
            it.remove(Keys.token)
            it.remove(Keys.user)
        } else {
            it[Keys.token] = TokenCipher.seal(token)
            it[Keys.user] = AppJson.encodeToString(User.serializer(), user)
        }
    }

    suspend fun saveCity(id: Int?, name: String?) = context.dataStore.edit {
        if (id == null || name == null) {
            it.remove(Keys.cityId)
            it.remove(Keys.cityName)
        } else {
            it[Keys.cityId] = id
            it[Keys.cityName] = name
        }
    }
}
