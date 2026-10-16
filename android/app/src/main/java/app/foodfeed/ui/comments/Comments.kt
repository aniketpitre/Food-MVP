package app.foodfeed.ui.comments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.foodfeed.data.ApiException
import app.foodfeed.data.AppContainer
import app.foodfeed.data.Comment
import app.foodfeed.data.NewComment
import app.foodfeed.data.apiCall
import app.foodfeed.ui.Format
import app.foodfeed.ui.appViewModel
import app.foodfeed.ui.components.AppIcons
import app.foodfeed.ui.components.Avatar
import app.foodfeed.ui.components.CenteredMessage
import app.foodfeed.ui.components.LoadingBox
import app.foodfeed.ui.components.LocalApp
import app.foodfeed.ui.components.ReportSheet
import app.foodfeed.ui.theme.Ink
import app.foodfeed.ui.theme.Motion
import app.foodfeed.ui.theme.RollingNumber
import app.foodfeed.ui.theme.accent
import app.foodfeed.ui.theme.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val comments: List<Comment> = emptyList(),
    val count: Int = 0,
    val draft: String = "",
    val replyTo: Comment? = null,
    val sending: Boolean = false,
    /** Comments added in this session; they animate in and their threads stay open. */
    val fresh: Set<String> = emptySet(),
)

/** Applies [f] to the comment with [id], wherever it sits (top level or a reply). */
private fun List<Comment>.mapComment(id: String, f: (Comment) -> Comment): List<Comment> = map { c ->
    when {
        c.id == id -> f(c)
        c.replies.any { it.id == id } -> c.copy(replies = c.replies.map { r -> if (r.id == id) f(r) else r })
        else -> c
    }
}

private fun List<Comment>.find(id: String): Comment? =
    firstOrNull { it.id == id } ?: firstNotNullOfOrNull { c -> c.replies.firstOrNull { it.id == id } }

class CommentsViewModel(private val app: AppContainer, private val postId: String) : ViewModel() {
    private val _state = MutableStateFlow(CommentsUiState())
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.update { it.copy(loading = it.comments.isEmpty(), error = null) }
        viewModelScope.launch {
            try {
                val r = apiCall { app.api.comments(postId) }
                _state.update { it.copy(loading = false, comments = r.comments, count = r.count) }
                publish(r.count)
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private fun publish(count: Int) = app.commentCounts.update { it + (postId to count) }

    fun setDraft(text: String) = _state.update { it.copy(draft = text.take(500)) }

    /** Reply like Instagram: the thread is remembered and the draft starts with a mention. */
    fun replyTo(c: Comment) = _state.update {
        val mention = "@${c.author.handle} "
        it.copy(replyTo = c, draft = if (it.draft.isBlank()) mention else it.draft)
    }

    fun cancelReply() = _state.update {
        val mention = "@${it.replyTo?.author?.handle} "
        it.copy(replyTo = null, draft = if (it.draft == mention) "" else it.draft)
    }

    fun send(onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
        if (app.session.value == null) return onNeedSignIn()
        val s = _state.value
        val body = s.draft.trim()
        if (body.isEmpty() || s.sending) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                val c = apiCall { app.api.addComment(postId, NewComment(body, s.replyTo?.id)) }
                _state.update { st ->
                    val list = if (c.parentId == null) st.comments + c
                    else st.comments.map { if (it.id == c.parentId) it.copy(replies = it.replies + c) else it }
                    st.copy(comments = list, count = st.count + 1, draft = "", replyTo = null, sending = false, fresh = st.fresh + c.id)
                }
                publish(_state.value.count)
            } catch (e: ApiException) {
                _state.update { it.copy(sending = false) }
                showMessage(e.message)
            }
        }
    }

    fun toggleLike(c: Comment, onNeedSignIn: () -> Unit, showMessage: (String) -> Unit) {
        if (app.session.value == null) return onNeedSignIn()
        val on = !c.likedByMe
        // Optimistic: the heart fills at once; the server's numbers replace ours when they arrive.
        _state.update { st -> st.copy(comments = st.comments.mapComment(c.id) { it.copy(likedByMe = on, likes = (it.likes + if (on) 1 else -1).coerceAtLeast(0)) }) }
        viewModelScope.launch {
            try {
                val r = apiCall { if (on) app.api.likeComment(c.id) else app.api.unlikeComment(c.id) }
                _state.update { st -> st.copy(comments = st.comments.mapComment(c.id) { it.copy(likedByMe = r.likedByMe, likes = r.likes) }) }
            } catch (e: ApiException) {
                _state.update { st -> st.copy(comments = st.comments.mapComment(c.id) { it.copy(likedByMe = c.likedByMe, likes = c.likes) }) }
                showMessage(e.message)
            }
        }
    }

    fun delete(c: Comment, showMessage: (String) -> Unit) {
        val removed = 1 + (_state.value.comments.find(c.id)?.replies?.size ?: 0)
        _state.update { st ->
            st.copy(
                comments = st.comments.filter { it.id != c.id }.map { it.copy(replies = it.replies.filter { r -> r.id != c.id }) },
                count = (st.count - removed).coerceAtLeast(0),
            )
        }
        publish(_state.value.count)
        viewModelScope.launch {
            try {
                apiCall { app.api.deleteComment(c.id) }
                showMessage("Comment deleted.")
            } catch (e: ApiException) {
                showMessage(e.message)
                load()
            }
        }
    }
}

/** Comments on one post, in a bottom sheet over whatever you were looking at. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    postId: String,
    onDismiss: () -> Unit,
    onNeedSignIn: () -> Unit,
    onOpenUser: (String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val vm = appViewModel(key = "comments-$postId") { CommentsViewModel(it, postId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by LocalApp.current.session.collectAsState()
    var reportId by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        CommentsContent(
            state = state,
            me = session?.user?.handle,
            onDraft = vm::setDraft,
            onSend = { vm.send(onNeedSignIn, showMessage) },
            onReply = vm::replyTo,
            onCancelReply = vm::cancelReply,
            onLike = { vm.toggleLike(it, onNeedSignIn, showMessage) },
            onDelete = { vm.delete(it, showMessage) },
            onReport = { if (session == null) onNeedSignIn() else reportId = it.id },
            onRetry = vm::load,
            onOpenUser = { onDismiss(); onOpenUser(it) },
        )
    }
    reportId?.let { id -> ReportSheet("comment", id, { reportId = null }, showMessage) }
}

private val QuickEmoji = listOf("❤️", "🔥", "😋", "🤤", "👏", "🙌", "😍", "💯")

@Composable
fun CommentsContent(
    state: CommentsUiState,
    me: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onReply: (Comment) -> Unit,
    onCancelReply: () -> Unit,
    onLike: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
    onReport: (Comment) -> Unit,
    onRetry: () -> Unit,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Follow your own new comment down the list.
    var lastFresh by remember { mutableStateOf(state.fresh.size) }
    LaunchedEffect(state.fresh.size) {
        if (state.fresh.size > lastFresh) {
            val newest = state.fresh.lastOrNull()
            val top = state.comments.indexOfFirst { it.id == newest || it.replies.any { r -> r.id == newest } }
            if (top >= 0) {
                val before = state.comments.take(top).sumOf { 1 + visibleReplies(it, expanded, state.fresh).size + moreCount(it, expanded, state.fresh).coerceAtMost(1) }
                list.animateScrollToItem(before)
            }
        }
        lastFresh = state.fresh.size
    }

    Column(modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(10.dp))
            if (state.count > 0) {
                Box(
                    Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp),
                ) { RollingNumber(state.count, MaterialTheme.typography.labelLarge, Ink) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> LoadingBox()
                state.error != null && state.comments.isEmpty() ->
                    CenteredMessage("Couldn't load comments", state.error, Icons.Filled.Refresh, "Try again", onRetry)
                state.comments.isEmpty() -> CenteredMessage(
                    "No comments yet",
                    "Start the conversation: what should people order here?",
                    AppIcons.Comment,
                )
                else -> LazyColumn(state = list, contentPadding = PaddingValues(vertical = 6.dp)) {
                    state.comments.forEach { c ->
                        item(key = c.id) {
                            CommentRow(c, false, c.id in state.fresh, onReply, onLike, onDelete, onReport, onOpenUser, Modifier.animateItem())
                        }
                        items(visibleReplies(c, expanded, state.fresh), key = { it.id }) { r ->
                            CommentRow(r, true, r.id in state.fresh, onReply, onLike, onDelete, onReport, onOpenUser, Modifier.animateItem())
                        }
                        val more = moreCount(c, expanded, state.fresh)
                        if (more > 0 || (expanded[c.id] == true && c.replies.isNotEmpty())) {
                            item(key = "more-${c.id}") {
                                val open = expanded[c.id] == true
                                Row(
                                    Modifier
                                        .animateItem()
                                        .clickable(remember { MutableInteractionSource() }, indication = null) { expanded[c.id] = !open }
                                        .padding(start = 68.dp, top = 2.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(24.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (open) "Hide replies" else if (more == 1) "View 1 more reply" else "View $more more replies",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Composer(state, me, onDraft, onSend, onCancelReply)
    }
}

/** Replies shown under a comment: all when the thread is open, else only ones you just wrote. */
private fun visibleReplies(c: Comment, expanded: Map<String, Boolean>, fresh: Set<String>): List<Comment> =
    if (expanded[c.id] == true) c.replies else c.replies.filter { it.id in fresh }

private fun moreCount(c: Comment, expanded: Map<String, Boolean>, fresh: Set<String>): Int =
    c.replies.size - visibleReplies(c, expanded, fresh).size

/** Body text with @mentions picked out in saffron. */
@Composable
private fun withMentions(body: String) = buildAnnotatedString {
    val mention = Regex("@[a-z0-9_]{3,20}")
    var at = 0
    for (m in mention.findAll(body)) {
        append(body.substring(at, m.range.first))
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append(m.value) }
        at = m.range.last + 1
    }
    append(body.substring(at))
}

/**
 * One comment. New ones (yours) slide up into place with a bounce and glow saffron for a
 * moment. The heart on the right pops when liked. Long-press for delete or report.
 */
@Composable
private fun CommentRow(
    c: Comment,
    isReply: Boolean,
    isFresh: Boolean,
    onReply: (Comment) -> Unit,
    onLike: (Comment) -> Unit,
    onDelete: (Comment) -> Unit,
    onReport: (Comment) -> Unit,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val appear = remember { Animatable(if (isFresh) 0f else 1f) }
    val glow = remember { Animatable(if (isFresh) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (appear.value < 1f) launch { appear.animateTo(1f, Motion.bouncy()) }
        if (glow.value > 0f) { delay(700); glow.animateTo(0f, tween(900)) }
    }
    val glowColor = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    translationY = (1f - appear.value) * 60f
                }
                .drawBehind { if (glow.value > 0f) drawRect(glowColor.copy(alpha = 0.16f * glow.value)) }
                .pointerInput(c.id) { detectTapGestures(onLongPress = { menu = true }) }
                .padding(start = if (isReply) 68.dp else 20.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Avatar(
                c.author.handle, c.author.displayName, if (isReply) 28.dp else 36.dp,
                Modifier.clip(CircleShape).clickable { onOpenUser(c.author.handle) },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.author.handle, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(8.dp))
                    Text(Format.timeAgo(c.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(withMentions(c.body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SmallAction("Reply") { onReply(c) }
                    if (c.canDelete) SmallAction("Delete") { onDelete(c) }
                }
            }
            LikeHeart(c) { onLike(c) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Reply") }, onClick = { menu = false; onReply(c) })
            if (c.canDelete) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(c) })
            DropdownMenuItem(
                text = { Text("Report") },
                leadingIcon = { Icon(AppIcons.Flag, null) },
                onClick = { menu = false; onReport(c) },
            )
        }
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
    )
}

/** The small heart: pops and tilts when you like, the count rolls. */
@Composable
private fun LikeHeart(c: Comment, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pop = remember { Animatable(1f) }
    var was by remember { mutableStateOf(c.likedByMe) }
    LaunchedEffect(c.likedByMe) {
        if (c.likedByMe && !was) { pop.snapTo(0.4f); pop.animateTo(1f, Motion.bouncy()) }
        was = c.likedByMe
    }
    val tint by animateColorAsState(
        if (c.likedByMe) accent() else MaterialTheme.colorScheme.onSurfaceVariant, Motion.enter(Motion.SHORT), label = "like",
    )
    Column(
        Modifier
            .padding(start = 6.dp)
            .pressScale(interaction, 0.8f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (c.likedByMe) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            if (c.likedByMe) "Unlike comment" else "Like comment",
            tint = tint,
            modifier = Modifier.size(16.dp).graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                rotationZ = (1f - pop.value) * -30f
            },
        )
        if (c.likes > 0) {
            RollingNumber(c.likes, MaterialTheme.typography.labelSmall, MaterialTheme.colorScheme.onSurfaceVariant, format = Format::compact)
        }
    }
}

/** Quick emoji, a "Replying to" banner that slides in, and the pill field with a send button that pops in. */
@Composable
private fun Composer(
    state: CommentsUiState,
    me: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            items(QuickEmoji) { e ->
                val interaction = remember { MutableInteractionSource() }
                Text(
                    e,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .pressScale(interaction, 0.7f)
                        .clickable(interaction, indication = null, role = Role.Button) { onDraft(state.draft + e) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = state.replyTo != null,
            enter = expandVertically(Motion.enter()) + fadeIn(Motion.enter()),
            exit = shrinkVertically(Motion.exit()) + fadeOut(Motion.exit()),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Replying to @${state.replyTo?.author?.handle.orEmpty()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Close, "Cancel reply",
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onCancelReply).padding(5.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(me ?: "you", null, 36.dp)
            Spacer(Modifier.width(10.dp))
            val canSend = state.draft.isNotBlank() && !state.sending
            Box(
                Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (state.draft.isEmpty()) {
                    Text(
                        if (me == null) "Sign in to comment…" else "Add a comment…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = state.draft,
                    onValueChange = onDraft,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val show by animateFloatAsState(if (canSend) 1f else 0f, Motion.bouncy(), label = "send")
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(44.dp)
                    .graphicsLayer {
                        val s = 0.5f + 0.5f * show
                        scaleX = s
                        scaleY = s
                        alpha = 0.35f + 0.65f * show.coerceIn(0f, 1f)
                        rotationZ = (1f - show) * -45f
                    }
                    .pressScale(interaction, 0.85f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(interaction, indication = null, role = Role.Button, enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Send, "Post comment", tint = Ink, modifier = Modifier.size(20.dp))
            }
        }
    }
}
