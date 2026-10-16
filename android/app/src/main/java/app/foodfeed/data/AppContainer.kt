package app.foodfeed.data

import android.content.Context
import app.foodfeed.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class Session(val token: String, val user: User)
data class SelectedCity(val id: Int, val name: String)
data class Coords(val lat: Double, val lng: Double)

enum class Engagement(val api: String) { Recommend("recommend"), Save("save"), Tried("tried"), Pass("pass") }

/** The engagement state of one dish as the app currently believes it. */
data class EngagementState(val stats: DishStats, val me: MyActions, val rating: Rating = Rating())

fun DishCard.engagement() = EngagementState(stats, me, rating)

/** Counts towards "Recommended by N": loved it, or tried it without saying "not for me". */
private fun MyActions.endorses() = recommended || (tried && !passed)

/**
 * Optimistic update for turning one action on or off. "Recommended by N" counts people
 * who recommended *or* tried, so it changes only when that combined flag changes.
 */
fun EngagementState.toggled(kind: Engagement): EngagementState {
    val before = me
    val after = when (kind) {
        // Loving a dish and "not for me" exclude each other; a "not for me" means you tried it.
        Engagement.Recommend -> before.copy(recommended = !before.recommended, passed = if (!before.recommended) false else before.passed)
        Engagement.Save -> before.copy(saved = !before.saved)
        Engagement.Tried -> before.copy(tried = !before.tried)
        Engagement.Pass -> if (before.passed) before.copy(passed = false)
        else before.copy(passed = true, recommended = false, tried = true)
    }
    val wasRec = before.endorses()
    val isRec = after.endorses()
    val recDelta = (if (isRec) 1 else 0) - (if (wasRec) 1 else 0)
    val saveDelta = (if (after.saved) 1 else 0) - (if (before.saved) 1 else 0)
    val triedDelta = (if (after.tried) 1 else 0) - (if (before.tried) 1 else 0)
    return EngagementState(
        stats = stats.copy(
            recommenders = (stats.recommenders + recDelta).coerceAtLeast(0),
            saves = (stats.saves + saveDelta).coerceAtLeast(0),
            tried = (stats.tried + triedDelta).coerceAtLeast(0),
        ),
        me = after,
        rating = rating.adjusted(
            loves = (if (after.recommended) 1 else 0) - (if (before.recommended) 1 else 0),
            passes = (if (after.passed) 1 else 0) - (if (before.passed) 1 else 0),
        ),
    )
}

fun MyActions.has(kind: Engagement) = when (kind) {
    Engagement.Recommend -> recommended
    Engagement.Save -> saved
    Engagement.Tried -> tried
    Engagement.Pass -> passed
}

/** App-wide state and the single entry point to the server. */
class AppContainer(context: Context, private val scope: CoroutineScope) {
    private val prefs = Prefs(context.applicationContext)

    /** True on phones with little memory, where Lite mode turns itself on by default. */
    val isLowEndDevice: Boolean = Device.isLowEnd(context.applicationContext)

    /** "system", "light" or "dark". */
    val themeMode = MutableStateFlow("system")

    /** "auto" (on for low-end phones), "on" or "off". */
    val liteModeSetting = MutableStateFlow("auto")

    /** Whether Lite mode is in effect now: fewer effects, smaller images, simpler motion. */
    val liteMode: Boolean get() = when (liteModeSetting.value) {
        "on" -> true
        "off" -> false
        else -> isLowEndDevice
    }

    /** Unread Activity items, refreshed every minute while signed in. */
    val unreadActivity = MutableStateFlow(0)
    private val network = NetworkFactory { session.value?.token }
    val okHttp get() = network.okHttp

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _city = MutableStateFlow<SelectedCity?>(null)
    val city: StateFlow<SelectedCity?> = _city.asStateFlow()

    /** Approximate location, kept in memory only for distances and Nearby. */
    val location = MutableStateFlow<Coords?>(null)

    private val _overrides = MutableStateFlow<Map<String, EngagementState>>(emptyMap())
    val overrides: StateFlow<Map<String, EngagementState>> = _overrides.asStateFlow()

    /** A newer app version found on GitHub Releases, waiting to be offered. */
    val availableUpdate = MutableStateFlow<AppUpdate?>(null)

    /** Checks for a newer version; returns it (and offers it) or null when up to date. */
    suspend fun checkForUpdate(): AppUpdate? = Updates.check().also { availableUpdate.value = it }

    /** Latest known comment count per post id, so a card updates as soon as you comment. */
    val commentCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Bumped when content changes (a new post, a sign-in) so lists reload. */
    val contentVersion = MutableStateFlow(0)

    var deviceId: String = ""
        private set

    /** An action a guest tried before signing in; it runs right after sign-in. */
    private var pendingAction: (suspend () -> Unit)? = null

    @Volatile private var apiCache: Pair<String, FoodFeedApi>? = null

    val api: FoodFeedApi
        get() {
            val url = _serverUrl.value
            apiCache?.let { (cachedUrl, api) -> if (cachedUrl == url) return api }
            return network.api(url.ifBlank { "http://localhost" }).also { apiCache = url to it }
        }

    fun mediaUrl(path: String?): String? = when {
        path == null -> null
        path.startsWith("http") -> path
        else -> normaliseServerUrl(_serverUrl.value) + path
    }

    suspend fun init() {
        val stored = prefs.load(BuildConfig.DEFAULT_SERVER_URL)
        deviceId = stored.deviceId
        _serverUrl.value = stored.serverUrl
        if (stored.token != null && stored.user != null) _session.value = Session(stored.token, stored.user)
        if (stored.cityId != null && stored.cityName != null) _city.value = SelectedCity(stored.cityId, stored.cityName)
        themeMode.value = stored.themeMode
        liteModeSetting.value = stored.liteMode
        _ready.value = true
        scope.launch { impressionLoop() }
        scope.launch { activityLoop() }
        if (_session.value != null) scope.launch { refreshMe() }
    }

    suspend fun setServerUrl(url: String) {
        val clean = normaliseServerUrl(url)
        if (clean != _serverUrl.value) {
            _serverUrl.value = clean
            prefs.saveServerUrl(clean)
            // A different server has different accounts and cities.
            signOut()
            setCity(null)
        }
    }

    suspend fun testServer(url: String): Result<Unit> = runCatching {
        apiCall { network.api(normaliseServerUrl(url)).health() }
        Unit
    }

    suspend fun setCity(city: SelectedCity?) {
        _city.value = city
        prefs.saveCity(city?.id, city?.name)
        contentVersion.update { it + 1 }
    }

    suspend fun setThemeMode(mode: String) {
        themeMode.value = mode
        prefs.saveThemeMode(mode)
    }

    suspend fun setLiteMode(mode: String) {
        liteModeSetting.value = mode
        prefs.saveLiteMode(mode)
    }

    suspend fun refreshUnread() {
        if (_session.value == null) {
            unreadActivity.value = 0
            return
        }
        runCatching { apiCall { api.unread() } }.onSuccess { unreadActivity.value = it.count }
    }

    private suspend fun activityLoop() {
        while (true) {
            refreshUnread()
            delay(60_000)
        }
    }

    /** Applies a profile change from the server to the signed-in session. */
    suspend fun updatedMe(user: User) {
        val token = _session.value?.token ?: return
        _session.value = Session(token, user)
        prefs.saveSession(token, user)
        contentVersion.update { it + 1 }
    }

    suspend fun updateProfile(change: ProfileUpdate) = updatedMe(apiCall { api.updateProfile(change) })

    suspend fun uploadAvatar(jpeg: ByteArray) = updatedMe(
        apiCall {
            api.uploadAvatar(MultipartBody.Part.createFormData("image", "avatar.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())))
        },
    )

    suspend fun removeAvatar() = updatedMe(apiCall { api.deleteAvatar() })

    suspend fun signedIn(result: AuthResult) {
        _session.value = Session(result.token, result.user)
        prefs.saveSession(result.token, result.user)
        _overrides.value = emptyMap()
        contentVersion.update { it + 1 }
        val action = pendingAction
        pendingAction = null
        if (action != null) runCatching { action() }
    }

    suspend fun signOut() {
        _session.value = null
        unreadActivity.value = 0
        prefs.saveSession(null, null)
        _overrides.value = emptyMap()
        pendingAction = null
        contentVersion.update { it + 1 }
    }

    fun setPendingAction(action: (suspend () -> Unit)?) {
        pendingAction = action
    }

    private suspend fun refreshMe() {
        try {
            val me = apiCall { api.me() }
            val token = _session.value?.token ?: return
            _session.value = Session(token, me)
            prefs.saveSession(token, me)
        } catch (e: ApiException) {
            if (e.status == 401) signOut()
        }
    }

    fun effective(card: DishCard): EngagementState =
        _overrides.value[card.id] ?: card.engagement()

    /** Optimistically flips one action, then reconciles with the server's counts. */
    suspend fun toggle(card: DishCard, kind: Engagement) {
        val current = effective(card)
        val next = current.toggled(kind)
        _overrides.update { it + (card.id to next) }
        try {
            val res = apiCall {
                if (next.me.has(kind)) api.engage(card.id, kind.api) else api.disengage(card.id, kind.api)
            }
            _overrides.update { it + (card.id to EngagementState(res.stats, res.me, res.rating ?: next.rating)) }
        } catch (e: ApiException) {
            _overrides.update { it + (card.id to current) }
            if (e.status == 401) signOut()
            throw e
        }
    }

    /** Records a share (counted only when signed in) and returns the link to send. */
    suspend fun shareLink(card: DishCard): String =
        try {
            apiCall { api.share(card.id) }.url
        } catch (e: ApiException) {
            "${normaliseServerUrl(_serverUrl.value)}/d/${card.id}"
        }

    suspend fun createPost(fields: Map<String, String>, jpeg: ByteArray): CreatedPost {
        val parts = buildList {
            fields.forEach { (k, v) -> add(MultipartBody.Part.createFormData(k, v)) }
            add(MultipartBody.Part.createFormData("image", "dish.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())))
        }
        val created = apiCall { api.createPost(parts) }
        contentVersion.update { it + 1 }
        return created
    }

    suspend fun deleteAccount() {
        apiCall { api.deleteMe() }
        signOut()
    }

    // ---- Impressions: a card on screen for a second, batched every 10 seconds ----
    private val seen = MutableStateFlow<Set<String>>(emptySet())

    fun recordImpression(dishId: String) {
        seen.update { it + dishId }
    }

    fun flushImpressions() {
        val batch = seen.value
        if (batch.isEmpty() || _serverUrl.value.isBlank()) return
        seen.update { it - batch }
        scope.launch {
            batch.chunked(50).forEach { chunk ->
                runCatching { apiCall { api.views(ViewsBody(chunk, deviceId)) } }
            }
        }
    }

    private suspend fun impressionLoop() {
        while (true) {
            delay(10_000)
            flushImpressions()
        }
    }
}
