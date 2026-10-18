package app.worthabite.data

import android.content.Context
import app.worthabite.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.worthabite.Notifications
import app.worthabite.PushSetup
import coil3.request.allowHardware
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class Session(val token: String, val user: User)
data class SelectedCity(val id: Int, val name: String)
/** A position; [accuracyM] is the phone's own error estimate in metres, when known. */
data class Coords(val lat: Double, val lng: Double, val accuracyM: Float? = null)

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
    val appContext: android.content.Context = context.applicationContext
    private val prefs = Prefs(appContext)

    fun notificationsAllowed() = Notifications.allowed(appContext)

    /** Marks [chatId] as on screen (null when it closes) and clears its notification. */
    fun chatOnScreen(chatId: String?) {
        openChatId.value = chatId
        if (chatId != null) Notifications.clearChat(appContext, chatId)
    }

    /** The chat on screen right now; its new messages need no notification. */
    val openChatId = MutableStateFlow<String?>(null)

    private val updatesLock = Mutex()

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

    /** The stories tray as Home last loaded it; the story viewer pages through the same list. */
    val storyTray = MutableStateFlow<List<StoryReel>>(emptyList())

    /** Chats with something new plus waiting message requests: the badge on the messages button. */
    val unreadMessages = MutableStateFlow(0)
    private val network = NetworkFactory(
        { session.value?.token },
        { normaliseServerUrl(_serverUrl.value) },
        onSessionEnded = { token -> scope.launch { sessionEnded(token) } },
        onNewToken = { old, new -> scope.launch { if (_session.value?.token == old) replaceToken(new) } },
        device = runCatching { deviceName() }.getOrNull(),
    )

    /** Set when the server ended this sign-in, for a message on screen. */
    val signedOutNotice = MutableStateFlow<String?>(null)
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

    /** An Explore tab another screen asked for (e.g. Home's "Explore places"); Explore takes it once. */
    val pendingExploreTab = MutableStateFlow<String?>(null)

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

    /** Bumped whenever you follow or unfollow someone, so lists built from follows reload. */
    val followVersion = MutableStateFlow(0)

    /**
     * Who you follow, as you last set it on this phone, by username. Every Follow button,
     * follower count and list reads this first, so following or unfollowing someone shows
     * everywhere at once, on screens loaded before the change too.
     */
    val following = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * The current look (photo, name, username) of people whose profile changed, by any
     * username they had. Every face and name in the app reads this first, so a new photo or
     * name shows everywhere at once: comments, chats, lists, the feed, old screens included.
     * Filled by your own edits and, for everyone else, by [checkUpdates].
     */
    val people = MutableStateFlow<Map<String, PersonChange>>(emptyMap())

    private fun rememberPerson(change: PersonChange) {
        people.update { m -> m + (change.formerHandles + change.handle).associateWith { change } }
    }
    private val followLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Follows or unfollows [handle]. Calls go out one at a time, in the order they were
     * tapped, so quick taps can't land on the server out of order.
     */
    suspend fun setFollowing(handle: String, on: Boolean): FollowState {
        val before = following.value[handle]
        following.update { it + (handle to on) }
        return followLock.withLock {
            try {
                val r = apiCall { if (on) api.follow(handle) else api.unfollow(handle) }
                following.update { it + (handle to r.isFollowing) }
                followVersion.update { it + 1 }
                r
            } catch (e: Exception) {
                following.update { if (before == null) it - handle else it + (handle to before) }
                throw e
            }
        }
    }

    /** Removes [handle] from your followers. */
    suspend fun removeFollower(handle: String): RemovedFollower = followLock.withLock {
        val r = apiCall { api.removeFollower(handle) }
        followVersion.update { it + 1 }
        r
    }

    /**
     * Blocks [handle]. The server ends follows both ways, so the app forgets them too: their
     * profile, counts and the Following feed show it at once.
     */
    suspend fun block(handle: String) = followLock.withLock {
        apiCall { api.block(BlockBody(handle)) }
        following.update { it + (handle to false) }
        followVersion.update { it + 1 }
    }

    suspend fun unblock(handle: String) = followLock.withLock {
        apiCall { api.unblock(handle) }
        followVersion.update { it + 1 }
    }

    var deviceId: String = ""
        private set

    /** An action a guest tried before signing in; it runs right after sign-in. */
    private var pendingAction: (suspend () -> Unit)? = null

    @Volatile private var apiCache: Pair<String, WorthAbiteApi>? = null

    val api: WorthAbiteApi
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
        if (_session.value != null) {
            scope.launch { refreshMe() }
            scope.launch { PushSetup.sync(this@AppContainer) }
        }
    }

    suspend fun setServerUrl(url: String) {
        val clean = normaliseServerUrl(url)
        if (clean != _serverUrl.value) {
            // A different server has different accounts and cities. Sign out first, while
            // requests still go to the old server: the sign-out carries its token, which
            // must not be sent to the new one.
            signOut()
            _serverUrl.value = clean
            prefs.saveServerUrl(clean)
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
        // Push for food near you goes to phones by city.
        if (_session.value != null) scope.launch { PushSetup.sync(this@AppContainer) }
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
            unreadMessages.value = 0
            return
        }
        runCatching { apiCall { api.unread() } }.onSuccess { unreadActivity.value = it.count }
        refreshUnreadMessages()
    }

    suspend fun refreshUnreadMessages() {
        if (_session.value == null) return
        runCatching { apiCall { api.inboxUnread() } }.onSuccess { unreadMessages.value = it.chats + it.requests }
    }

    /**
     * Checks for news every 30 seconds while the app is on screen, and not at all in the
     * background (the 15-minute worker and push wake-ups cover that), so it costs no battery
     * when you're not using it.
     */
    private suspend fun activityLoop() {
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            .distinctUntilChanged()
            .collectLatest { onScreen ->
                if (!onScreen) return@collectLatest
                var tick = 0
                while (true) {
                    checkUpdates()
                    if (tick++ % 2 == 0) refreshUnread()
                    delay(30_000)
                }
            }
    }

    /**
     * Asks the server what's new since the last check and shows phone notifications for it.
     * Called every 30 seconds while the app runs and by [app.worthabite.UpdatesWorker] in the
     * background. The first check after signing in only sets the starting point.
     */
    suspend fun checkUpdates(): Unit = updatesLock.withLock {
        if (_session.value == null || _serverUrl.value.isBlank()) return@withLock
        val since = prefs.updatesSince()
        // Roughly where you are (about 100 m), only to find new food near you; never stored.
        val here = location.value
        val page = try {
            apiCall {
                api.updates(
                    since,
                    lat = here?.lat?.let { Math.round(it * 1000) / 1000.0 },
                    lng = here?.lng?.let { Math.round(it * 1000) / 1000.0 },
                    city = _city.value?.id,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock // offline or server down: try again next time
        }
        prefs.saveUpdatesSince(page.now)
        page.people.forEach(::rememberPerson)
        if (since == null) return@withLock
        Notifications.show(appContext, page.messages, page.reactions, page.activity, openChatId.value)
        page.reminder?.let { Notifications.showReminder(appContext, it.kind) }
        val nearby = prefs.takeNearbyAllowance(page.nearby.size)
        if (nearby > 0) {
            val items = page.nearby.take(nearby)
            Notifications.showNearby(appContext, items, items.associate { it.dishId to loadThumb(mediaUrl(it.thumbUrl)) })
        }
        if (page.activity.isNotEmpty() || page.messages.isNotEmpty()) refreshUnread()
    }

    /** For tests: today's food-near-you notifications start again from zero. */
    @androidx.annotation.VisibleForTesting
    suspend fun resetNearbyAllowance() = prefs.resetNearbyAllowance()

    /** A small photo for a notification, or null if it can't be had in a few seconds. */
    private suspend fun loadThumb(url: String?): android.graphics.Bitmap? {
        if (url == null) return null
        return kotlinx.coroutines.withTimeoutOrNull(5_000) {
            val request = coil3.request.ImageRequest.Builder(appContext).data(url).size(512)
                .allowHardware(false).build()
            (coil3.SingletonImageLoader.get(appContext).execute(request) as? coil3.request.SuccessResult)
                ?.image?.let { (it as? coil3.BitmapImage)?.bitmap }
        }
    }

    /** Applies a profile change from the server to the signed-in session. */
    suspend fun updatedMe(user: User) {
        val token = _session.value?.token ?: return
        val before = _session.value?.user
        _session.value = Session(token, user)
        // Your new photo, name or username shows everywhere you appear, straight away.
        rememberPerson(PersonChange(user.handle, user.displayName, user.avatarUrl, listOfNotNull(before?.handle?.takeIf { it != user.handle })))
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
        prefs.saveUpdatesSince(null)
        scope.launch { checkUpdates() }
        scope.launch { PushSetup.sync(this@AppContainer) }
        _overrides.value = emptyMap()
        following.value = emptyMap()
        contentVersion.update { it + 1 }
        val action = pendingAction
        pendingAction = null
        if (action != null) runCatching { action() }
    }

    /** The server says this sign-in is over (signed out from another device, new password). */
    private suspend fun sessionEnded(token: String) {
        if (_session.value?.token != token) return
        clearSession()
        signedOutNotice.value = "You were signed out. Please sign in again."
    }

    /** A fresh token for the same account (after changing the password on an old sign-in). */
    suspend fun replaceToken(token: String) {
        val user = _session.value?.user ?: return
        _session.value = Session(token, user)
        prefs.saveSession(token, user)
    }

    suspend fun signOut() {
        if (_session.value != null) {
            // This phone stops getting the account's notifications, and the sign-in ends on
            // the server too, so the token can't be used again (a few seconds at most).
            kotlinx.coroutines.withTimeoutOrNull(4_000) { PushSetup.unregister(this@AppContainer) }
            kotlinx.coroutines.withTimeoutOrNull(4_000) { runCatching { api.logout() } }
        }
        clearSession()
    }

    private suspend fun clearSession() {
        following.value = emptyMap()
        _session.value = null
        unreadActivity.value = 0
        unreadMessages.value = 0
        prefs.saveSession(null, null)
        prefs.saveUpdatesSince(null)
        Notifications.clearAll(appContext)
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

    /** Posts a 24-hour story: a photo plus optional caption and dish or place tag. */
    suspend fun createStory(fields: Map<String, String>, jpeg: ByteArray): StoryCreated {
        val parts = buildList {
            fields.forEach { (k, v) -> add(MultipartBody.Part.createFormData(k, v)) }
            _city.value?.let { add(MultipartBody.Part.createFormData("cityId", it.id.toString())) }
            add(MultipartBody.Part.createFormData("image", "story.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())))
        }
        val created = apiCall { api.createStory(parts) }
        contentVersion.update { it + 1 }
        return created
    }

    suspend fun deleteAccount(password: String) {
        apiCall { api.deleteMe(PasswordBody(password)) }
        signOut()
    }

    // ---- Impressions: a card on screen for a second, batched every 10 seconds ----
    // Each is (dish, feed tab, tapped open?), so the admin health page can show how often
    // people open what each tab shows them.
    private data class Seen(val dishId: String, val tab: String?, val opened: Boolean)
    private val seen = MutableStateFlow<Set<Seen>>(emptySet())

    fun recordImpression(dishId: String, tab: String? = null) {
        seen.update { it + Seen(dishId, tab, false) }
    }

    /** A card someone tapped open from a feed tab. */
    fun recordOpen(dishId: String, tab: String) {
        seen.update { it + Seen(dishId, tab, true) }
    }

    fun flushImpressions() {
        val batch = seen.value
        if (batch.isEmpty() || _serverUrl.value.isBlank()) return
        seen.update { it - batch }
        scope.launch {
            batch.groupBy { it.tab to it.opened }.forEach { (key, group) ->
                group.map { it.dishId }.chunked(50).forEach { chunk ->
                    runCatching { apiCall { api.views(ViewsBody(chunk, deviceId, tab = key.first, opened = key.second)) } }
                }
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
