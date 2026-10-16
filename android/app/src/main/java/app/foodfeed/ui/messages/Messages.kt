package app.foodfeed.ui.messages

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
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.BlockBody
import app.foodfeed.data.ChatSummary
import app.foodfeed.data.Conversation
import app.foodfeed.data.LastMessage
import app.foodfeed.data.Message
import app.foodfeed.data.NewMessage
import app.foodfeed.data.OpenChat
import app.foodfeed.data.Person
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.DishImage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.PrimaryButton
import app.foodfeed.ui.components.QuietButton
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.profile.BackBar
import app.foodfeed.ui.profile.Segmented
import kotlinx.coroutines.delay
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
}

/**
 * Direct messages, Instagram style: your chats, newest first, with a dot on the unread ones,
 * and a Requests tab for people you don't follow. Search at the top starts a new chat.
 */
@Composable
fun InboxScreen(vm: InboxViewModel, onBack: () -> Unit, onOpenChat: (String) -> Unit, onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, session) {
        // Fresh every time you come back to the inbox, and every 15 s while it's open.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.load()
                delay(15_000)
            }
        }
    }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<Person>() }
    LaunchedEffect(query) {
        found.clear()
        if (query.trim().length >= 2) {
            delay(250)
            runCatching { apiCall { app.api.people(query.trim()) } }.onSuccess { list -> found.addAll(list.filterNot { it.isMe }) }
        }
    }
    var confirmDelete by remember { mutableStateOf<ChatSummary?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackBar("Messages", onBack)
        if (session == null) {
            CenteredMessage("Your messages", "Sign in to send dishes to friends and plan where to eat together.", null, "Sign in", onNeedSignIn)
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(30) },
            placeholder = { Text("Search people to message") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("inbox_search"),
        )
        if (query.trim().length >= 2) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(found, key = { it.handle }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch {
                                try {
                                    onOpenChat(vm.openWith(p.handle))
                                    query = ""
                                } catch (e: ApiException) {
                                    showMessage(e.message)
                                }
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(p.handle, p.displayName, 44.dp, photo = app.mediaUrl(p.avatarUrl))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.displayName ?: p.handle, style = MaterialTheme.typography.titleSmall)
                            Text("@${p.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            return@Column
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Segmented(
                listOf("inbox" to "Chats", "request" to if (state.requests > 0) "Requests (${state.requests})" else "Requests"),
                state.folder,
                vm::folder,
            )
        }
        InboxList(
            state = state,
            imageUrl = { app.mediaUrl(it) },
            onOpen = { onOpenChat(it.id) },
            onLongPress = { confirmDelete = it },
        )
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("It's removed for you only. @${c.with.handle} still has their copy.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(c.id); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
            if (state.folder == "request") "No message requests" else "No messages yet",
            if (state.folder == "request") "Messages from people you don't follow wait here until you accept them."
            else "Send a dish to a friend from any dish page, or search for someone above.",
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
                    if (c.unread) Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** One line about the last message, as the inbox shows it. */
fun preview(m: LastMessage): String {
    val text = when (m.kind) {
        "dish" -> if (m.mine) "You sent a dish" else "Sent a dish"
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

    /** Asks only for messages newer than the last one we have. */
    suspend fun poll() {
        val last = _state.value.messages.lastOrNull()?.createdAt
        runCatching { apiCall { app.api.conversation(id, after = last) } }.onSuccess { c ->
            val known = _state.value.messages.map { it.id }.toSet()
            val fresh = c.messages.filterNot { it.id in known }
            _state.update { it.copy(chat = c.copy(messages = emptyList()), messages = it.messages + fresh) }
            if (fresh.any { !it.mine }) markRead()
        }
    }

    private suspend fun markRead() {
        runCatching { apiCall { app.api.readChat(id) } }
        app.refreshUnreadMessages()
    }

    fun send(text: String, onError: (String) -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val m = apiCall { app.api.sendMessage(id, NewMessage(body = body)) }
                _state.update { it.copy(sending = false, messages = it.messages + m, chat = it.chat?.copy(folder = "inbox")) }
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

    fun accept() {
        _state.update { it.copy(chat = it.chat?.copy(folder = "inbox")) }
        viewModelScope.launch { runCatching { apiCall { app.api.acceptChat(id) } } }
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
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(lifecycle) {
        // New messages show up within a few seconds while the chat is on screen.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(3_000)
                vm.poll()
            }
        }
    }
    var reporting by remember { mutableStateOf<String?>(null) }
    ChatContent(
        state = state,
        imageUrl = { app.mediaUrl(it) },
        onBack = onBack,
        onOpenUser = onOpenUser,
        onOpenDish = onOpenDish,
        onSend = { vm.send(it, showMessage) },
        onUnsend = vm::unsend,
        onAccept = vm::accept,
        onDelete = { vm.delete(onBack) },
        onBlock = { vm.block { msg -> showMessage(msg); onBack() } },
        onReport = { reporting = it },
    )
    reporting?.let { id ->
        ReportSheet(targetType = "message", targetId = id, targetHandle = null, onDismiss = { reporting = null }, showMessage = showMessage)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onSend: (String) -> Unit,
    onUnsend: (Message) -> Unit,
    onAccept: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit,
) {
    val chat = state.chat
    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(0) }

    Column(Modifier.fillMaxSize().imePadding()) {
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
                        DropdownMenuItem({ Text("Delete chat") }, { menu = false; onDelete() })
                        DropdownMenuItem({ Text("Block @${chat.with.handle}") }, { menu = false; onBlock() })
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        when {
            state.loading -> LoadingBox(Modifier.weight(1f))
            chat == null -> Box(Modifier.weight(1f)) { CenteredMessage("Couldn't open this chat", state.error) }
            else -> {
                val lastMine = state.messages.lastOrNull { it.mine }
                val seen = lastMine != null && chat.theirLastReadAt != null && state.messages.lastOrNull() == lastMine &&
                    (Format.epochMillis(chat.theirLastReadAt) ?: 0) >= (Format.epochMillis(lastMine.createdAt) ?: Long.MAX_VALUE)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (seen) item(key = "seen") {
                        Text(
                            "Seen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(end = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        )
                    }
                    items(state.messages.asReversed(), key = { it.id }) { m ->
                        Bubble(
                            m, imageUrl, onOpenDish,
                            Modifier.animateItem().combinedClickable(onClick = {}, onLongClick = { actionsFor = m }),
                        )
                    }
                    if (state.messages.isEmpty()) item(key = "hello") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(chat.with.handle, chat.with.displayName, 84.dp, photo = imageUrl(chat.with.avatarUrl))
                            Spacer(Modifier.height(10.dp))
                            Text(chat.with.displayName ?: chat.with.handle, style = MaterialTheme.typography.titleLarge)
                            Text("Say hi, or send a dish you think they'll love.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (chat.folder == "request") {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("@${chat.with.handle} wants to send you messages. They won't know you've seen this until you reply or accept.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietButton("Block", onBlock, Modifier.weight(1f))
                            QuietButton("Delete", onDelete, Modifier.weight(1f))
                            PrimaryButton("Accept", onAccept, Modifier.weight(1f))
                        }
                    }
                } else if (!chat.canMessage) {
                    Text(
                        "You can't message @${chat.with.handle}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(1000) },
                            placeholder = { Text("Message…") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier.weight(1f).animateContentSize().testTag("chat_input"),
                        )
                        IconButton(
                            onClick = { onSend(text); text = "" },
                            enabled = text.isNotBlank() && !state.sending,
                            modifier = Modifier.padding(start = 6.dp).size(48.dp).clip(CircleShape)
                                .background(if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) { Icon(AppIcons.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }
        }
    }
    actionsFor?.let { m ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(if (m.mine) "Unsend this message?" else "Report this message?") },
            text = { Text(if (m.mine) "It will be removed for both of you." else "We'll review it. You can also block this person.") },
            confirmButton = {
                TextButton(onClick = {
                    if (m.mine) onUnsend(m) else onReport(m.id)
                    actionsFor = null
                }) { Text(if (m.mine) "Unsend" else "Report", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { actionsFor = null }) { Text("Cancel") } },
        )
    }
}

/** One message: a speech bubble, a dish card, or a story reply/like with the story's thumbnail. */
@Composable
private fun Bubble(m: Message, imageUrl: (String?) -> String?, onOpenDish: (String) -> Unit, modifier: Modifier = Modifier) {
    val mine = m.mine
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 290.dp)) {
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
            when {
                m.kind == "dish" && dish != null -> Column(
                    Modifier
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onOpenDish(dish.id) },
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
                m.kind == "dish" -> Text("This dish is no longer on FoodFeed", style = MaterialTheme.typography.labelMedium)
                m.kind == "story_like" -> Text("❤️", style = MaterialTheme.typography.headlineMedium)
                else -> Text(
                    m.body.orEmpty(),
                    color = fg,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp, 22.dp, if (mine) 6.dp else 22.dp, if (mine) 22.dp else 6.dp))
                        .background(bg)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Send a dish

/**
 * "Send to…" for a dish, like Instagram's share sheet: your recent chats and the people you
 * follow, a search box, an optional note, and Share elsewhere for other apps.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SendToSheet(dishId: String, onDismiss: () -> Unit, onShareElsewhere: () -> Unit, onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
    val app = LocalApp.current
    val session by app.session.collectAsState()
    val scope = rememberCoroutineScope()
    val people = remember { mutableStateListOf<Person>() }
    val picked = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    LaunchedEffect(session, query) {
        val me = session?.user ?: return@LaunchedEffect
        people.clear()
        if (query.trim().length >= 2) {
            delay(250)
            runCatching { apiCall { app.api.people(query.trim()) } }.onSuccess { l -> people.addAll(l.filterNot { it.isMe }) }
        } else {
            val recent = runCatching { apiCall { app.api.inbox() }.items.map { Person(it.with.handle, it.with.displayName, it.with.avatarUrl) } }.getOrDefault(emptyList())
            val follows = runCatching { apiCall { app.api.following(me.handle) } }.getOrDefault(emptyList())
            people.addAll((recent + follows).distinctBy { it.handle }.take(30))
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Send to", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            if (session == null) {
                Text("Sign in to send dishes to friends in FoodFeed.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Sign in", { onDismiss(); onNeedSignIn() }, Modifier.weight(1f))
                    QuietButton("Share elsewhere", { onDismiss(); onShareElsewhere() }, Modifier.weight(1f))
                }
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(30) },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
                items(people, key = { it.handle }) { p ->
                    val on = p.handle in picked
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) picked.remove(p.handle) else picked.add(p.handle) }.padding(vertical = 8.dp),
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
                if (people.isEmpty()) item {
                    Text(
                        if (query.isBlank()) "Follow people to send them dishes, or search above." else "Nobody found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            if (picked.isNotEmpty()) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    placeholder = { Text("Write a message… (\"Let's go here?\")") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                QuietButton("Share elsewhere", { onDismiss(); onShareElsewhere() }, Modifier.weight(1f))
                PrimaryButton(
                    if (picked.size > 1) "Send separately" else "Send",
                    {
                        sending = true
                        scope.launch {
                            try {
                                val r = apiCall {
                                    app.api.shareMessage(app.foodfeed.data.ShareMessage(picked.toList(), note.trim().ifEmpty { null }, dishId))
                                }
                                showMessage(
                                    if (r.failed.isEmpty()) "Sent." else "Sent to ${r.sent.size}. Couldn't send to ${r.failed.joinToString { "@$it" }}.",
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
