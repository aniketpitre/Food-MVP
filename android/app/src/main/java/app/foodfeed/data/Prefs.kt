package app.foodfeed.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "foodfeed")

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
    }

    suspend fun load(defaultServerUrl: String): StoredState {
        val p = context.dataStore.data.first()
        var deviceId = p[Keys.deviceId]
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            context.dataStore.edit { it[Keys.deviceId] = deviceId }
        }
        return StoredState(
            serverUrl = p[Keys.serverUrl] ?: defaultServerUrl,
            token = p[Keys.token],
            user = p[Keys.user]?.let { runCatching { AppJson.decodeFromString(User.serializer(), it) }.getOrNull() },
            cityId = p[Keys.cityId],
            cityName = p[Keys.cityName],
            deviceId = deviceId,
            themeMode = p[Keys.themeMode] ?: "system",
            liteMode = p[Keys.liteMode] ?: "auto",
        )
    }

    suspend fun saveThemeMode(mode: String) = context.dataStore.edit { it[Keys.themeMode] = mode }

    suspend fun saveLiteMode(mode: String) = context.dataStore.edit { it[Keys.liteMode] = mode }

    suspend fun saveServerUrl(url: String) = context.dataStore.edit { it[Keys.serverUrl] = url }

    suspend fun saveSession(token: String?, user: User?) = context.dataStore.edit {
        if (token == null || user == null) {
            it.remove(Keys.token)
            it.remove(Keys.user)
        } else {
            it[Keys.token] = token
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
