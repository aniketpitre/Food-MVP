package app.worthabite.ui.messages

import app.worthabite.data.DishCard
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.Edit
import app.worthabite.data.SearchResult
import app.worthabite.data.ShareMessage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import app.worthabite.data.ImageCompressor
import app.worthabite.data.Reaction
import app.worthabite.data.ReactionBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.worthabite.data.ApiException
import app.worthabite.data.AppContainer
import app.worthabite.data.BlockBody
import app.worthabite.data.ChatSummary
import app.worthabite.data.Conversation
import app.worthabite.data.LastMessage
import app.worthabite.data.Message
import app.worthabite.data.NewMessage
import app.worthabite.data.OpenChat
import app.worthabite.data.Person
import app.worthabite.data.apiCall
import app.worthabite.ui.Format
import app.worthabite.ui.components.AppIcons
import app.worthabite.ui.components.Avatar
import app.worthabite.ui.components.CenteredMessage
import app.worthabite.ui.components.DishImage
import app.worthabite.ui.components.LoadingBox
import app.worthabite.ui.components.LocalApp
import app.worthabite.ui.components.PrimaryButton
import app.worthabite.ui.components.QuietButton
import app.worthabite.ui.components.ReportSheet
import app.worthabite.ui.profile.BackBar
import app.worthabite.ui.profile.Segmented
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Inbox

data class InboxUiState(
    val loading: Boolean = true,
    val folder: String = "inbox",
    val items: List<ChatSummary> = emptyList(),
    val requests: Int = 0,
    val error: String? = null,
    /** Finding someone to share with: your followers and the people you follow. */
    val query: String = "",
    val people: List<Person> = emptyList(),
)

class InboxViewModel(private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(InboxUiState())
    val state: StateFlow<InboxUiState> = _state.asStateFlow()

    fun load() {
        if (app.session.value == null) return _state.update { it.copy(loading = false) }
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.inbox(_state.value.folder) }
                _state.update { it.copy(loading = false, items = r.items, requests = r.requests, error = null) }
                app.refreshUnreadMessages()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun folder(f: String) {
        _state.update { it.copy(folder = f, loading = true, items = emptyList()) }
        load()
    }

    fun delete(id: String) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
        viewModelScope.launch { runCatching { apiCall { app.api.deleteChat(id) } } }
    }

    suspend fun openWith(handle: String): String = apiCall { app.api.openChat(OpenChat(handle)) }.id

    private var searchJob: kotlinx.coroutines.Job? = null

    fun search(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(200)
            val found = runCatching { apiCall { app.api.friends(q.trim().ifEmpty { null }) } }.getOrDefault(emptyList())
            _state.update { it.copy(people = found) }
        }
    }
}

/**
 * Messages, like Instagram's: one row per conversation, newest first, with a dot on the
 * unread ones. Search (or ✎) finds anyone you can share with: your followers and the people
 * you follow. Conversations hold the dishes and posts you share and your reactions to them;
 * there is no typing.
 */
@Composable
fun InboxScreen(vm: InboxViewModel, onBack: () -> Unit, onOpenChat: (String) -> Unit, onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, session) {
        // Fresh every time you come back, and every 15 s while it's open.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.load()
                delay(15_000)
            }
        }
    }
    var confirmDelete by remember { mutableStateOf<ChatSummary?>(null) }
    val scope = rememberCoroutineScope()
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    val startChat: (String) -> Unit = { handle ->
        scope.launch {
            try {
                val id = vm.openWith(handle)
                // Navigation must happen on the main thread, whatever thread the call came back on.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onOpenChat(id) }
            } catch (e: ApiException) {
                showMessage(e.message)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Messages", onBack) {
            if (session != null) {
                IconButton(
                    onClick = { searching = true; vm.search(state.query); runCatching { searchFocus.requestFocus() } },
                    modifier = Modifier.testTag("new_chat"),
                ) { Icon(Icons.Filled.Edit, "New message") }
            }
        }
        if (session == null) {
            CenteredMessage("Messages", "Sign in to share dishes with your friends.", null, "Sign in", onNeedSignIn)
            return@Column
        }
        androidx.compose.material3.OutlinedTextField(
            value = state.query,
            onValueChange = { searching = true; vm.search(it.take(30)) },
            placeholder = { Text("Search your followers and following") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (searching) IconButton(onClick = { searching = false; vm.search("") }) { Icon(Icons.Filled.Close, "Close search") }
            },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).focusRequester(searchFocus)
                .onFocusChanged { if (it.isFocused && !searching) { searching = true; vm.search(state.query) } }
                .testTag("inbox_search"),
        )
        if (searching) {
            PeopleToShareWith(state.people, imageUrl = { app.mediaUrl(it) }, onPick = startChat)
        } else {
            InboxList(
                state = state,
                imageUrl = { app.mediaUrl(it) },
                onOpen = { onOpenChat(it.id) },
                onLongPress = { confirmDelete = it },
            )
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this conversation?") },
            text = { Text("It's removed for you only. @${c.with.handle} still has it.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(c.id); confirmDelete = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InboxList(state: InboxUiState, imageUrl: (String?) -> String?, onOpen: (ChatSummary) -> Unit, onLongPress: (ChatSummary) -> Unit) {
    when {
        state.loading -> LoadingBox()
        state.items.isEmpty() -> CenteredMessage(
            "No messages yet",
            "Share dishes with your followers and the people you follow: tap the paper plane on any dish, or ✎ to start a conversation.",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.items, key = { it.id }) { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpen(c) }, onLongClick = { onLongPress(c) })
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("chat_${c.with.handle}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.with.handle, c.with.displayName, 54.dp, photo = imageUrl(c.with.avatarUrl))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            c.with.displayName ?: c.with.handle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Medium,
                        )
                        Text(
                            preview(c.lastMessage) + "  ·  " + Format.timeAgo(c.lastMessage.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal,
                            color = if (c.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (c.unread) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                }
            }
        }
    }
}

/** People you can share with, as found by the inbox search. */
@Composable
private fun PeopleToShareWith(people: List<Person>, imageUrl: (String?) -> String?, onPick: (String) -> Unit) {
    if (people.isEmpty()) {
        CenteredMessage(
            "Nobody to share with yet",
            "You can share dishes with your followers and the people you follow. Follow people to get started.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(people, key = { it.handle }) { p ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(p.handle) }.padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("person_${p.handle}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.handle, p.displayName, 48.dp, photo = imageUrl(p.avatarUrl))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(p.displayName ?: p.handle, style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            "@${p.handle}",
                            when {
                                p.isFollowing && p.followsYou -> "Friends"
                                p.followsYou -> "Follows you"
                                p.isFollowing -> "Following"
                                else -> null
                            },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One line about the last message, as the inbox shows it. */
fun preview(m: LastMessage): String {
    val text = when (m.kind) {
        "dish" -> if (m.mine) "You sent a dish" else "Sent a dish"
        "place" -> if (m.mine) "You sent a place" else "Sent a place"
        "post" -> if (m.mine) "You sent a post" else "Sent a post"
        "photo" -> if (m.mine) "You sent a photo" else "Sent a photo"
        "story_like" -> if (m.mine) "You liked their story" else "Liked your story ❤️"
        "story_reply" -> if (m.mine) "You replied to their story" else "Replied to your story: ${m.body.orEmpty()}"
        else -> m.body.orEmpty()
    }
    return if (m.mine && m.kind == "text") "You: $text" else text
}

// ---------------------------------------------------------------- Chat

data class ChatUiState(
    val loading: Boolean = true,
    val chat: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

/** Quick reactions offered on a long press, like Instagram's. Double-tap sends the first. */
val QuickReactions = listOf("❤️", "😂", "😮", "😢", "🔥", "👍")

class ChatViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            try {
                val c = apiCall { app.api.conversation(id) }
                _state.update { it.copy(loading = false, chat = c, messages = c.messages, error = null) }
                markRead()
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /**
     * Reloads the latest page when anything changed (new messages, reactions, unsends,
     * "seen"). The server answers a tiny "unchanged" otherwise, and nothing on screen redraws.
     */
    suspend fun poll() {
        if (_state.value.sending) return
        val last = _state.value.chat?.state
        runCatching { apiCall { app.api.conversation(id, state = last) } }.onSuccess { c ->
            if (c.unchanged) return@onSuccess
            val known = _state.value.messages.map { it.id }.toSet()
            val fresh = c.messages.filterNot { it.id in known }
            _state.update { it.copy(chat = c.copy(messages = emptyList()), messages = c.messages) }
            if (fresh.any { !it.mine }) markRead()
        }
    }

    private suspend fun markRead() {
        runCatching { apiCall { app.api.readChat(id) } }
        app.refreshUnreadMessages()
    }

    /** Sets my reaction to [emoji], or removes it when null or the same as before. */
    fun react(m: Message, emoji: String?) {
        val current = m.reactions.firstOrNull { it.mine }?.emoji
        val next = if (emoji == current) null else emoji
        _state.update { s ->
            s.copy(messages = s.messages.map { x ->
                if (x.id != m.id) x
                else x.copy(reactions = x.reactions.filterNot { it.mine } + listOfNotNull(next?.let { Reaction(it, true) }))
            })
        }
        viewModelScope.launch {
            runCatching { apiCall { if (next == null) app.api.unreact(m.id) else app.api.react(m.id, ReactionBody(next)) } }
        }
    }

    /** Shares a dish into this conversation (there is no typing, only sharing). */
    fun shareDish(dishId: String, onError: (String) -> Unit) {
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val sent = apiCall { app.api.sendMessage(id, NewMessage(dishId = dishId)) }
                _state.update { s -> s.copy(sending = false, messages = s.messages.filterNot { it.id == sent.id } + sent) }
            } catch (e: ApiException) {
                _state.update { it.copy(sending = false) }
                onError(e.message)
            }
        }
    }

    fun unsend(m: Message) {
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == m.id }) }
        viewModelScope.launch { runCatching { apiCall { app.api.unsend(m.id) } } }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { apiCall { app.api.deleteChat(id) } }
            app.refreshUnreadMessages()
            onDone()
        }
    }

    fun block(onDone: (String) -> Unit) {
        val handle = _state.value.chat?.with?.handle ?: return
        viewModelScope.launch {
            try {
                apiCall { app.api.block(BlockBody(handle)) }
                onDone("Blocked @$handle. They can't message you.")
            } catch (e: ApiException) {
                onDone(e.message)
            }
        }
    }
}

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    chatId: String,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(lifecycle) {
        // New shares and reactions show up within a few seconds while this is on screen,
        // and it raises no notifications meanwhile.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            app.chatOnScreen(chatId)
            try {
                while (true) {
                    delay(3_000)
                    vm.poll()
                }
            } finally {
                app.chatOnScreen(null)
            }
        }
    }
    var reporting by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    ChatContent(
        state = state,
        imageUrl = { app.mediaUrl(it) },
        onBack = onBack,
        onOpenUser = onOpenUser,
        onOpenDish = onOpenDish,
        onOpenRestaurant = onOpenRestaurant,
        onReact = vm::react,
        onUnsend = vm::unsend,
        onDelete = { vm.delete(onBack) },
        onBlock = { vm.block { msg -> showMessage(msg); onBack() } },
        onReport = { reporting = it },
        onShareDish = { picking = true },
    )
    if (picking) {
        DishPickerSheet(onDismiss = { picking = false }) { dishId ->
            picking = false
            vm.shareDish(dishId, showMessage)
        }
    }
    reporting?.let { id ->
        ReportSheet(targetType = "message", targetId = id, targetHandle = null, onDismiss = { reporting = null }, showMessage = showMessage)
    }
}

/**
 * The dishes you and one friend have shared, oldest at the top. No typing: share a dish from
 * the bar at the bottom, double-tap a dish for a heart, or long-press it for another
 * reaction (or to unsend / report it).
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onUnsend: (Message) -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit = {},
    onReact: (Message, String?) -> Unit = { _, _ -> },
    onShareDish: () -> Unit = {},
) {
    val chat = state.chat
    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            if (chat != null) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onOpenUser(chat.with.handle) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(chat.with.handle, chat.with.displayName, 36.dp, photo = imageUrl(chat.with.avatarUrl))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(chat.with.displayName ?: chat.with.handle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("@${chat.with.handle}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Delete conversation") }, { menu = false; onDelete() })
                        DropdownMenuItem({ Text("Block @${chat.with.handle}") }, { menu = false; onBlock() })
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        when {
            state.loading -> LoadingBox(Modifier.weight(1f))
            chat == null -> Box(Modifier.weight(1f)) { CenteredMessage("Couldn't open this", state.error) }
            else -> {
                val lastMine = state.messages.lastOrNull { it.mine }
                val seen = lastMine != null && chat.theirLastReadAt != null && state.messages.lastOrNull() == lastMine &&
                    (Format.epochMillis(chat.theirLastReadAt) ?: 0) >= (Format.epochMillis(lastMine.createdAt) ?: Long.MAX_VALUE)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (seen) item(key = "seen") {
                        Text(
                            "Seen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(end = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                    items(state.messages.asReversed(), key = { it.id }, contentType = { it.kind }) { m ->
                        Bubble(
                            m, imageUrl, onOpenDish, onOpenRestaurant,
                            onOpenPhoto = {},
                            onReactionTap = { onReact(m, m.reactions.firstOrNull { it.mine }?.emoji) },
                            onDoubleTap = { onReact(m, QuickReactions.first()) },
                            onLongPress = { actionsFor = m },
                            modifier = Modifier.animateItem().combinedClickable(
                                onClick = {},
                                onDoubleClick = { onReact(m, QuickReactions.first()) },
                                onLongClick = { actionsFor = m },
                            ),
                        )
                    }
                    if (state.messages.isEmpty()) item(key = "hello") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(chat.with.handle, chat.with.displayName, 84.dp, photo = imageUrl(chat.with.avatarUrl))
                            Spacer(Modifier.height(10.dp))
                            Text(chat.with.displayName ?: chat.with.handle, style = MaterialTheme.typography.titleLarge)
                            Text("Nothing shared yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (chat.canMessage) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Share dishes, react to theirs. No typing here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(end = 10.dp).testTag("share_hint"),
                        )
                        PrimaryButton(
                            if (state.sending) "Sharing…" else "Share a dish",
                            onShareDish,
                            Modifier.testTag("share_dish"),
                            enabled = !state.sending,
                            leading = AppIcons.Send,
                        )
                    }
                } else {
                    Text(
                        "You can share dishes with your followers and the people you follow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp).testTag("share_hint"),
                    )
                }
            }
        }
    }
    actionsFor?.let { m ->
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { actionsFor = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                // Reactions row, like Instagram's long-press bar.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val mineNow = m.reactions.firstOrNull { it.mine }?.emoji
                    QuickReactions.forEach { e ->
                        Text(
                            e,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (e == mineNow) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { onReact(m, e); actionsFor = null }
                                .padding(8.dp),
                        )
                    }
                }
                if (m.mine) SheetAction("Unsend", danger = true) { onUnsend(m); actionsFor = null }
                else SheetAction("Report", danger = true) { onReport(m.id); actionsFor = null }
            }
        }
    }
}

@Composable
private fun SheetAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/** A short line about a message, for reply quotes and previews. */
fun quoteOf(kind: String, body: String?): String = when (kind) {
    "photo" -> if (body.isNullOrBlank()) "📷 Photo" else "📷 $body"
    "dish" -> "🍽️ Dish" + (body?.let { ": $it" } ?: "")
    "place" -> "📍 Place" + (body?.let { ": $it" } ?: "")
    "post" -> "🖼️ Post" + (body?.let { ": $it" } ?: "")
    "story_like" -> "❤️ Story"
    "story_reply" -> "Story reply: ${body.orEmpty()}"
    else -> body.orEmpty()
}

/**
 * A shared card opens on a tap, and still takes the bubble's double-tap (heart) and
 * long-press (reactions and actions), which its own tap handling would otherwise swallow.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.cardTaps(onDoubleTap: () -> Unit, onLongPress: () -> Unit, onTap: () -> Unit): Modifier =
    combinedClickable(onClick = onTap, onDoubleClick = onDoubleTap, onLongClick = onLongPress)

/**
 * One message: a speech bubble, a dish / place / post card, a photo, or a story reply/like,
 * with the message it answers quoted above it and its reactions underneath.
 */
@Composable
private fun Bubble(
    m: Message,
    imageUrl: (String?) -> String?,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    onOpenPhoto: (String) -> Unit,
    onReactionTap: () -> Unit,
    modifier: Modifier = Modifier,
    onDoubleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val mine = m.mine
    val bg = if (mine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val cardShape = RoundedCornerShape(20.dp)
    Row(modifier.fillMaxWidth().testTag("message_${m.kind}"), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 290.dp)) {
            m.replyTo?.let { r ->
                Text(
                    when {
                        mine && r.mine -> "You replied to yourself"
                        mine -> "You replied"
                        r.mine -> "Replied to you"
                        else -> "Replied"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    quoteOf(r.kind, r.body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            if (m.kind == "story_reply" || m.kind == "story_like") {
                Text(
                    when {
                        m.kind == "story_like" && mine -> "You liked their story"
                        m.kind == "story_like" -> "Liked your story"
                        mine -> "You replied to their story"
                        else -> "Replied to your story"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
                val story = m.story
                if (story?.thumbUrl != null && !story.expired) {
                    DishImage(imageUrl(story.thumbUrl), null, "Story", Modifier.size(96.dp, 150.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    Text(
                        "Story no longer available",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            val dish = m.dish
            val place = m.place
            val post = m.post
            val photo = m.photo
            var showBody = m.kind != "text" && m.kind != "story_reply" && !m.body.isNullOrBlank()
            when {
                m.kind == "dish" && dish != null -> Column(
                    Modifier.widthIn(max = 240.dp).clip(cardShape).background(MaterialTheme.colorScheme.surfaceContainer).cardTaps(onDoubleTap, onLongPress) { onOpenDish(dish.id) },
                ) {
                    DishImage(imageUrl(dish.thumbUrl), null, dish.name, Modifier.fillMaxWidth().height(150.dp))
                    Column(Modifier.padding(12.dp)) {
                        Text(dish.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(dish.restaurantName, Format.price(dish.priceInr)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                m.kind == "place" && place != null -> Column(
                    Modifier.widthIn(max = 240.dp).clip(cardShape).background(MaterialTheme.colorScheme.surfaceContainer).cardTaps(onDoubleTap, onLongPress) { onOpenRestaurant(place.id) },
                ) {
                    if (place.thumbUrl != null) DishImage(imageUrl(place.thumbUrl), null, place.name, Modifier.fillMaxWidth().height(120.dp))
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Pin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(place.cuisine?.replaceFirstChar { it.uppercase() }, place.locality).joinToString(" · ").ifEmpty { "Place" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                m.kind == "post" && post != null -> Column(
                    Modifier.width(220.dp).clip(cardShape).background(MaterialTheme.colorScheme.surfaceContainer)
                        .cardTaps(onDoubleTap, onLongPress) { post.dishId?.let(onOpenDish) },
                ) {
                    post.author?.let {
                        Text("@$it", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    DishImage(imageUrl(post.thumbUrl), null, post.dishName ?: "Post", Modifier.fillMaxWidth().aspectRatio(4f / 5f))
                    Column(Modifier.padding(12.dp)) {
                        post.dishName?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        post.caption?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                m.kind == "photo" && photo != null -> DishImage(
                    imageUrl(photo.thumbUrl ?: photo.imageUrl), null, "Photo",
                    Modifier.size(200.dp, 250.dp).clip(cardShape).cardTaps(onDoubleTap, onLongPress) { onOpenPhoto(photo.imageUrl) },
                )
                m.kind in setOf("dish", "place", "post", "photo") -> Text(
                    "This is no longer on WorthAbite", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp),
                )
                m.kind == "story_like" -> Text("❤️", style = MaterialTheme.typography.headlineMedium)
                else -> { showBody = true }
            }
            if (showBody) {
                if (m.kind != "text" && m.kind != "story_reply") Spacer(Modifier.height(4.dp))
                Text(
                    m.body.orEmpty(),
                    color = fg,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp, 22.dp, if (mine) 6.dp else 22.dp, if (mine) 22.dp else 6.dp))
                        .background(bg)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
            if (m.reactions.isNotEmpty()) {
                Row(
                    Modifier
                        .padding(top = 2.dp, start = 6.dp, end = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = onReactionTap)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("reactions"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    m.reactions.map { it.emoji }.distinct().forEach { Text(it, style = MaterialTheme.typography.labelLarge) }
                    if (m.reactions.size > 1) Text(" ${m.reactions.size}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Share a dish

/**
 * "Send to…" for a dish or a post: your friends (people you follow who follow you back),
 * a search box to find one, and Share elsewhere for other apps. No message to type.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SendToSheet(
    dishId: String? = null,
    onDismiss: () -> Unit,
    onShareElsewhere: () -> Unit,
    onNeedSignIn: () -> Unit,
    showMessage: (String) -> Unit,
    postId: String? = null,
) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<Person>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val picked = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // Fresh from the server each time (a new follower shows at once), and the search asks the
    // server too, so someone beyond the first 200 connections is still found.
    LaunchedEffect(session, query, attempt) {
        if (session == null) return@LaunchedEffect
        if (query.isNotBlank()) delay(250)
        try {
            friends = apiCall { app.api.friends(query.trim().removePrefix("@").ifBlank { null }) }
            loadError = null
        } catch (e: ApiException) {
            loadError = e.message
        }
    }
    val shown = friends.orEmpty()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Send to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            if (session == null) {
                Text("Sign in to share dishes with friends in WorthAbite.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Sign in", { onDismiss(); onNeedSignIn() }, Modifier.weight(1f))
                    QuietButton("Share elsewhere", { onDismiss(); onShareElsewhere() }, Modifier.weight(1f))
                }
                return@Column
            }
            if (friends.orEmpty().size > 6 || query.isNotBlank()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(30) },
                    placeholder = { Text("Search followers and following") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                when {
                    loadError != null && friends == null -> item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(loadError!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.material3.TextButton(onClick = { attempt++ }) { Text("Try again") }
                        }
                    }
                    friends == null -> item { LoadingBox(Modifier.fillMaxWidth().height(120.dp)) }
                    friends!!.isEmpty() && query.isNotBlank() -> item {
                        Text(
                            "Nobody by that name among your followers and the people you follow.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    friends!!.isEmpty() -> item {
                        Text(
                            "Nobody to share with yet. You can share dishes with your followers and the people you follow (unless they turned sharing off in their settings).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    else -> items(shown, key = { it.handle }) { p ->
                        val on = p.handle in picked
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) picked.remove(p.handle) else picked.add(p.handle) }
                                .padding(vertical = 8.dp).testTag("friend_${p.handle}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p.handle, p.displayName, 44.dp, photo = app.mediaUrl(p.avatarUrl))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(p.displayName ?: p.handle, style = MaterialTheme.typography.titleSmall)
                                Text("@${p.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.RadioButton(selected = on, onClick = { if (on) picked.remove(p.handle) else picked.add(p.handle) })
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                QuietButton("Share elsewhere", { onDismiss(); onShareElsewhere() }, Modifier.weight(1f))
                PrimaryButton(
                    if (picked.size > 1) "Share with ${picked.size}" else "Share",
                    {
                        sending = true
                        scope.launch {
                            try {
                                val r = apiCall { app.api.shareMessage(ShareMessage(picked.toList(), dishId = if (postId == null) dishId else null, postId = postId)) }
                                showMessage(
                                    if (r.failed.isEmpty()) "Shared." else "Shared with ${r.sent.size}. Couldn't share with ${r.failed.joinToString { "@$it" }}.",
                                )
                                onDismiss()
                            } catch (e: ApiException) {
                                showMessage(e.message)
                            }
                            sending = false
                        }
                    },
                    Modifier.weight(1f),
                    enabled = picked.isNotEmpty() && !sending,
                )
            }
        }
    }
}

/**
 * Picks a dish to share in a conversation: your saved dishes (or the city's top dishes) to
 * start with, or search for any dish.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DishPickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = LocalApp.current
    var query by remember { mutableStateOf("") }
    var dishes by remember { mutableStateOf<List<DishCard>?>(null) }
    var title by remember { mutableStateOf("Your saved dishes") }
    LaunchedEffect(query) {
        val city = app.city.value
        val loc = app.location.value
        if (query.trim().length >= 2 && city != null) {
            delay(250)
            title = "Dishes"
            dishes = runCatching { apiCall { app.api.search(query.trim(), city.id, loc?.lat, loc?.lng) }.dishes }.getOrDefault(emptyList())
        } else {
            val saved = runCatching { apiCall { app.api.saved(loc?.lat, loc?.lng) }.items }.getOrDefault(emptyList())
            if (saved.isNotEmpty() || city == null) {
                title = "Your saved dishes"
                dishes = saved
            } else {
                title = "Top dishes in ${city.name}"
                dishes = runCatching { apiCall { app.api.feed(city.id, null, "top", 20, 0, "picker", loc?.lat, loc?.lng) }.items }
                    .getOrDefault(emptyList())
            }
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Share a dish", style = MaterialTheme.typography.titleLarge)
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                placeholder = { Text("Search any dish") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("pick_search"),
            )
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                val list = dishes
                when {
                    list == null -> item { LoadingBox(Modifier.fillMaxWidth().height(120.dp)) }
                    list.isEmpty() -> item {
                        Text(
                            "No dishes found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                    else -> items(list, key = { it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(d.id) }
                                .padding(vertical = 8.dp).testTag("pick_dish"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DishImage(app.mediaUrl(d.cover?.thumbUrl), d.cover?.placeholderColor, d.name, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(d.restaurant.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(AppIcons.Send, "Share ${d.name}", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}
