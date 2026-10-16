package app.foodfeed.ui.messages

import app.foodfeed.data.ShareMessage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import app.foodfeed.data.ImageCompressor
import app.foodfeed.data.Reaction
import app.foodfeed.data.ReactionBody
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

    /** Reloads the latest page, so new messages and new reactions both show up. */
    suspend fun poll() {
        if (_state.value.sending) return
        runCatching { apiCall { app.api.conversation(id) } }.onSuccess { c ->
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

    private fun post(onError: (String) -> Unit, call: suspend () -> Message) {
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val m = call()
                _state.update { it.copy(sending = false, messages = it.messages + m, chat = it.chat?.copy(folder = "inbox")) }
            } catch (e: ApiException) {
                _state.update { it.copy(sending = false) }
                onError(e.message)
            }
        }
    }

    fun send(text: String, replyTo: Message?, onError: (String) -> Unit) {
        val body = text.trim()
        if (body.isEmpty()) return
        post(onError) { apiCall { app.api.sendMessage(id, NewMessage(body = body, replyTo = replyTo?.id)) } }
    }

    /** Sends a dish, place or post card into the chat. */
    fun sendCard(card: NewMessage, onError: (String) -> Unit) = post(onError) { apiCall { app.api.sendMessage(id, card) } }

    fun sendPhoto(context: android.content.Context, uri: android.net.Uri, caption: String?, onError: (String) -> Unit) =
        post(onError) {
            val jpeg = try {
                ImageCompressor.compress(context, uri)
            } catch (e: Exception) {
                throw ApiException("bad_image", "Couldn't read that photo.")
            }
            apiCall {
                app.api.sendPhoto(
                    id,
                    buildList {
                        caption?.trim()?.takeIf { it.isNotEmpty() }?.let { add(MultipartBody.Part.createFormData("body", it)) }
                        add(MultipartBody.Part.createFormData("image", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())))
                    },
                )
            }
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
    chatId: String,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val app = LocalApp.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(lifecycle) {
        // New messages and reactions show up within a few seconds while the chat is on screen,
        // and this chat raises no notifications meanwhile.
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
    var attaching by remember { mutableStateOf(false) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.sendPhoto(context, uri, null, showMessage)
    }
    ChatContent(
        state = state,
        imageUrl = { app.mediaUrl(it) },
        onBack = onBack,
        onOpenUser = onOpenUser,
        onOpenDish = onOpenDish,
        onOpenRestaurant = onOpenRestaurant,
        onSend = { text, replyTo -> vm.send(text, replyTo, showMessage) },
        onReact = vm::react,
        onPickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onAttach = { attaching = true },
        onUnsend = vm::unsend,
        onAccept = vm::accept,
        onDelete = { vm.delete(onBack) },
        onBlock = { vm.block { msg -> showMessage(msg); onBack() } },
        onReport = { reporting = it },
        onCopy = { text ->
            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
            showMessage("Copied")
        },
    )
    reporting?.let { id ->
        ReportSheet(targetType = "message", targetId = id, targetHandle = null, onDismiss = { reporting = null }, showMessage = showMessage)
    }
    if (attaching) {
        AttachSheet(
            onDismiss = { attaching = false },
            onPick = { card -> attaching = false; vm.sendCard(card, showMessage) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    imageUrl: (String?) -> String?,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenDish: (String) -> Unit,
    onSend: (String, Message?) -> Unit,
    onUnsend: (Message) -> Unit,
    onAccept: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit,
    onOpenRestaurant: (String) -> Unit = {},
    onReact: (Message, String?) -> Unit = { _, _ -> },
    onPickPhoto: () -> Unit = {},
    onAttach: () -> Unit = {},
    onCopy: (String) -> Unit = {},
) {
    val chat = state.chat
    var menu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var replyingTo by remember { mutableStateOf<Message?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
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
                            m, imageUrl, onOpenDish, onOpenRestaurant,
                            onOpenPhoto = { viewing = it },
                            onReactionTap = { onReact(m, m.reactions.firstOrNull { it.mine }?.emoji) },
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
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        replyingTo?.let { r ->
                            Row(
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (r.mine) "Replying to yourself" else "Replying to ${chat.with.displayName ?: chat.with.handle}",
                                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        quoteOf(r.kind, r.body), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { replyingTo = null }) { Icon(Icons.Filled.Close, "Cancel reply") }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                IconButton(
                                    onClick = { attachMenu = true },
                                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).testTag("chat_attach"),
                                ) { Icon(Icons.Filled.Add, "Send a photo, dish or place", tint = MaterialTheme.colorScheme.onPrimary) }
                                DropdownMenu(attachMenu, { attachMenu = false }) {
                                    DropdownMenuItem(
                                        { Text("Photo from gallery") }, { attachMenu = false; onPickPhoto() },
                                        leadingIcon = { Icon(AppIcons.Photo, null) },
                                    )
                                    DropdownMenuItem(
                                        { Text("Dish or place") }, { attachMenu = false; onAttach() },
                                        leadingIcon = { Icon(AppIcons.Restaurant, null) },
                                    )
                                }
                            }
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
                                modifier = Modifier.weight(1f).padding(start = 8.dp).animateContentSize().testTag("chat_input"),
                            )
                            IconButton(
                                onClick = { onSend(text, replyingTo); text = ""; replyingTo = null },
                                enabled = text.isNotBlank() && !state.sending,
                                modifier = Modifier.padding(start = 6.dp).size(48.dp).clip(CircleShape)
                                    .background(if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                            ) { Icon(AppIcons.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary) }
                        }
                    }
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
                SheetAction("Reply") { replyingTo = m; actionsFor = null }
                if (!m.body.isNullOrBlank()) SheetAction("Copy") { onCopy(m.body); actionsFor = null }
                if (m.mine) SheetAction("Unsend", danger = true) { onUnsend(m); actionsFor = null }
                else SheetAction("Report", danger = true) { onReport(m.id); actionsFor = null }
            }
        }
    }
    viewing?.let { url ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { viewing = null },
                contentAlignment = Alignment.Center,
            ) {
                coil3.compose.AsyncImage(
                    model = imageUrl(url), contentDescription = "Photo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxWidth(),
                )
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
) {
    val mine = m.mine
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
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
                    Modifier.widthIn(max = 240.dp).clip(cardShape).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onOpenDish(dish.id) },
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
                    Modifier.widthIn(max = 240.dp).clip(cardShape).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onOpenRestaurant(place.id) },
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
                        .clickable { post.dishId?.let(onOpenDish) },
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
                    Modifier.size(200.dp, 250.dp).clip(cardShape).clickable { onOpenPhoto(photo.imageUrl) },
                )
                m.kind in setOf("dish", "place", "post", "photo") -> Text(
                    "This is no longer on FoodFeed", style = MaterialTheme.typography.labelMedium,
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

// ---------------------------------------------------------------- Attach a dish or place

/** Search dishes and places in your city and send one into the chat as a card. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onDismiss: () -> Unit, onPick: (NewMessage) -> Unit) {
    val app = LocalApp.current
    val city by app.city.collectAsState()
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<app.foodfeed.data.SearchResult?>(null) }
    LaunchedEffect(query, city) {
        val c = city ?: return@LaunchedEffect
        delay(250)
        val loc = app.location.value
        val q = query.trim()
        result = if (q.length >= 2) {
            runCatching { apiCall { app.api.search(q, c.id, loc?.lat, loc?.lng) } }.getOrNull()
        } else {
            runCatching {
                val feed = apiCall { app.api.feed(c.id, null, "top", 12, 0, "attach", loc?.lat, loc?.lng) }
                app.foodfeed.data.SearchResult(dishes = feed.items)
            }.getOrNull()
        }
    }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text("Send a dish or place", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(40) },
                placeholder = { Text("Search dishes and places in ${city?.name ?: "your city"}") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().height(380.dp)) {
                val r = result
                if (r == null) item { LoadingBox(Modifier.fillMaxWidth().height(120.dp)) }
                else {
                    if (r.restaurants.isNotEmpty()) item { SectionLabel("Places") }
                    items(r.restaurants, key = { "p" + it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(NewMessage(restaurantId = p.id)) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) { Icon(AppIcons.Pin, null, tint = MaterialTheme.colorScheme.primary) }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                p.locality?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    if (r.dishes.isNotEmpty()) item { SectionLabel(if (query.trim().length >= 2) "Dishes" else "Top dishes") }
                    items(r.dishes, key = { "d" + it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(NewMessage(dishId = d.id)) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DishImage(app.mediaUrl(d.cover?.thumbUrl), d.cover?.placeholderColor, d.name, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(d.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(d.restaurant.name, Format.price(d.priceInr)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (r.dishes.isEmpty() && r.restaurants.isEmpty()) item {
                        Text("Nothing found.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------- Send a dish

/**
 * "Send to…" for a post, dish or place, like Instagram's share sheet: your recent chats and
 * the people you follow, a search box, an optional note, and Share elsewhere for other apps.
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
    restaurantId: String? = null,
) {
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
                Text("Sign in to send posts, dishes and places to friends in FoodFeed.", style = MaterialTheme.typography.bodyMedium)
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
                        if (query.isBlank()) "Follow people to send them things, or search above." else "Nobody found.",
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
                                    app.api.shareMessage(ShareMessage(picked.toList(), note.trim().ifEmpty { null }, dishId, restaurantId, postId))
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
