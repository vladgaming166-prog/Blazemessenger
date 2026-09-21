package ro.blazemessenger.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.blazemessenger.app.R
import ro.blazemessenger.app.call.ActiveCallActivity
import ro.blazemessenger.app.call.CallCoordinator
import ro.blazemessenger.app.call.CallForegroundService
import ro.blazemessenger.app.core.MessagePolicy
import ro.blazemessenger.app.data.chat.ChatRepository
import ro.blazemessenger.app.data.chat.preview
import ro.blazemessenger.app.data.media.MediaRepository
import ro.blazemessenger.app.data.toAppError
import ro.blazemessenger.app.data.user.UserRepository
import ro.blazemessenger.app.domain.model.AppError
import ro.blazemessenger.app.domain.model.Chat
import ro.blazemessenger.app.domain.model.ChatMessage
import ro.blazemessenger.app.domain.model.MemberState
import ro.blazemessenger.app.domain.model.Presence
import ro.blazemessenger.app.domain.model.UserProfile
import ro.blazemessenger.app.notify.TransferService
import ro.blazemessenger.app.ui.common.asText
import ro.blazemessenger.app.ui.common.formatWhen
import ro.blazemessenger.app.ui.common.viewFile

data class ConversationUi(
    val chat: Chat? = null,
    val messages: List<ChatMessage> = emptyList(),
    val peer: UserProfile? = null,
    val presence: Presence? = null,
    val typing: List<String> = emptyList(),
    val members: List<MemberState> = emptyList(),
    val blocked: Boolean = false,
    val loadingOlder: Boolean = false,
    val canLoadOlder: Boolean = true,
    val error: AppError? = null,
    val recording: Boolean = false,
)

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val chats: ChatRepository,
    private val users: UserRepository,
    private val media: MediaRepository,
    private val auth: FirebaseAuth,
    private val coordinator: CallCoordinator,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val chatId: String = savedStateHandle.get<String>("chatId").orEmpty()
    val uid: String = auth.currentUser?.uid.orEmpty()
    private val _state = MutableStateFlow(ConversationUi())
    val state = _state.asStateFlow()
    var composer by mutableStateOf("")
    var reply by mutableStateOf<ChatMessage?>(null)
    var editing by mutableStateOf<ChatMessage?>(null)
    var search by mutableStateOf("")
    private var typingJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null

    init {
        viewModelScope.launch {
            chats.observeChat(chatId).collect { chat ->
                _state.update { it.copy(chat = chat) }
                val other = chat?.memberIds?.firstOrNull { id -> id != uid }
                if (chat?.isGroup == false && other != null) {
                    launch {
                        users.observeProfile(other).collect { profile ->
                            _state.update { it.copy(peer = profile) }
                        }
                    }
                    launch {
                        users.observePresence(other).collect { presence ->
                            _state.update { it.copy(presence = presence) }
                        }
                    }
                    launch {
                        val blocked = runCatching { users.isBlockedEitherWay(uid, other) }.getOrDefault(false)
                        _state.update { it.copy(blocked = blocked) }
                    }
                }
            }
        }
        viewModelScope.launch {
            chats.observeLatest(chatId).collect { latest ->
                _state.update { current ->
                    val older = current.messages.filter { message ->
                        latest.none { it.id == message.id } && message.createdAt < (latest.minOfOrNull { it.createdAt } ?: Long.MAX_VALUE)
                    }
                    current.copy(messages = (latest + older).distinctBy { it.id })
                }
                chats.markDelivered(chatId)
            }
        }
        viewModelScope.launch { chats.observeTyping(chatId).collect { names -> _state.update { it.copy(typing = names) } } }
        viewModelScope.launch { chats.observeMembers(chatId).collect { members -> _state.update { it.copy(members = members) } } }
        viewModelScope.launch {
            val profile = users.profile(uid)
            chats.markRead(chatId, profile?.privacy?.readReceipts != false)
        }
    }

    fun onComposerChange(value: String) {
        composer = value
        val name = _state.value.peer?.displayName ?: "…"
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            chats.setTyping(chatId, value.isNotBlank(), name)
            delay(2500)
            chats.setTyping(chatId, false, name)
        }
    }

    fun send() {
        val text = composer
        val currentEdit = editing
        val currentReply = reply
        viewModelScope.launch {
            runCatching {
                if (currentEdit != null) chats.editMessage(chatId, currentEdit, text) else chats.sendText(chatId, text, currentReply)
            }.onSuccess {
                composer = ""
                reply = null
                editing = null
            }.onFailure { error -> _state.update { it.copy(error = error.toAppError()) } }
        }
    }

    fun loadOlder() {
        val oldest = _state.value.messages.minOfOrNull { it.createdAt } ?: return
        if (_state.value.loadingOlder || !_state.value.canLoadOlder) return
        viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            val page = runCatching { chats.loadOlder(chatId, oldest) }.getOrElse {
                _state.update { state -> state.copy(loadingOlder = false, error = it.toAppError()) }
                return@launch
            }
            _state.update { current ->
                current.copy(
                    loadingOlder = false,
                    canLoadOlder = page.size >= 30,
                    messages = (current.messages + page).distinctBy { it.id },
                )
            }
        }
    }

    fun react(message: ChatMessage, emoji: String) = action { chats.react(chatId, message.id, emoji) }
    fun delete(message: ChatMessage) = action { chats.deleteMessage(chatId, message) }
    fun pin(message: ChatMessage) = action { chats.pin(chatId, if (_state.value.chat?.pinnedMessageId == message.id) "" else message.id) }
    fun mute(hours: Int) = action {
        val until = if (hours == 0) 0L else System.currentTimeMillis() + hours * 60L * 60L * 1000L
        chats.setMuted(chatId, until)
    }
    fun block() = action {
        val other = _state.value.chat?.memberIds?.firstOrNull { it != uid } ?: return@action
        users.block(other)
        _state.update { it.copy(blocked = true) }
    }
    fun unblock() = action {
        val other = _state.value.chat?.memberIds?.firstOrNull { it != uid } ?: return@action
        users.unblock(other)
        _state.update { it.copy(blocked = false) }
    }
    fun report(reason: String, note: String) = action {
        val other = _state.value.chat?.memberIds?.firstOrNull { it != uid } ?: return@action
        users.report(other, reason, note)
    }
    fun leave() = action { chats.leaveGroup(chatId) }

    fun forward(message: ChatMessage, targetChatId: String) = action {
        chats.sendText(targetChatId, message.preview().ifBlank { message.fileName }, forwarded = true)
    }

    fun sendAttachment(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val staged = withContext(Dispatchers.IO) { media.stage(context, uri) }
                val intent = Intent(context, TransferService::class.java).apply {
                    putExtra(TransferService.EXTRA_CHAT_ID, chatId)
                    putExtra(TransferService.EXTRA_URI, staged.toString())
                    putExtra(TransferService.EXTRA_NAME, staged.lastPathSegment ?: "file")
                    putExtra(TransferService.EXTRA_CAPTION, composer.trim())
                }
                ContextCompat.startForegroundService(context, intent)
                composer = ""
            }.onFailure { error -> _state.update { it.copy(error = error.toAppError()) } }
        }
    }

    fun startRecording(context: Context) {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        runCatching {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            recordFile = file
            _state.update { it.copy(recording = true) }
        }.onFailure { error ->
            _state.update { it.copy(error = error.toAppError(), recording = false) }
        }
    }

    fun stopRecording(send: Boolean, context: Context) {
        val file = recordFile
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        recordFile = null
        _state.update { it.copy(recording = false) }
        if (send && file != null && file.exists()) {
            sendAttachment(context, Uri.fromFile(file))
        } else {
            file?.delete()
        }
    }

    fun placeCall(context: Context, video: Boolean) {
        val peer = _state.value.peer ?: return
        val me = _state.value
        viewModelScope.launch {
            val self = users.profile(uid)
            coordinator.placeCall(
                calleeId = peer.uid,
                calleeName = peer.displayName,
                calleePhoto = peer.photoPath,
                video = video,
                callerName = self?.displayName.orEmpty(),
                callerPhoto = self?.photoPath.orEmpty(),
            )
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_ACTIVE
                putExtra(CallForegroundService.EXTRA_NAME, peer.displayName)
                putExtra(CallForegroundService.EXTRA_VIDEO, video)
            }
            ContextCompat.startForegroundService(context, intent)
            context.startActivity(Intent(context, ActiveCallActivity::class.java))
        }
        if (me.blocked) return
    }

    suspend fun cached(path: String): File = media.cachedFile(path)

    fun clearError() = _state.update { it.copy(error = null) }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { error -> _state.update { it.copy(error = error.toAppError()) } }
        }
    }

    override fun onCleared() {
        recorder?.runCatching { release() }
        super.onCleared()
    }
}

private val Emoji = listOf(
    "😀", "😁", "😂", "😊", "😍", "😘", "😎", "🤔", "😢", "😡",
    "👍", "👎", "🙏", "🔥", "❤️", "✨", "🎉", "👀", "🎉", "☕",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationScreen(onBack: () -> Unit, viewModel: ConversationViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var emoji by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ChatMessage?>(null) }
    var report by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording(context)
    }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            val video = granted.containsKey(Manifest.permission.CAMERA)
            viewModel.placeCall(context, video && granted[Manifest.permission.CAMERA] == true)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.sendAttachment(context, uri)
    }
    val title = if (ui.chat?.isGroup == true) ui.chat?.title.orEmpty() else ui.peer?.displayName ?: stringResource(R.string.chats_title)
    val subtitle = when {
        ui.typing.isNotEmpty() -> ui.typing.joinToString() + " " + stringResource(R.string.typing)
        ui.chat?.isGroup == true -> stringResource(R.string.members) + " ${ui.chat?.memberIds?.size ?: 0}"
        ui.peer?.privacy?.showOnline == false -> stringResource(R.string.offline)
        ui.presence?.online == true && ui.peer?.privacy?.showOnline != false -> stringResource(R.string.online)
        ui.peer?.privacy?.showLastSeen == false -> stringResource(R.string.last_seen_hidden)
        ui.peer != null -> stringResource(R.string.last_seen, formatWhen(context, ui.peer?.lastSeen ?: 0L))
        else -> ""
    }
    val visible = ui.messages.filter { message ->
        viewModel.search.isBlank() || message.text.contains(viewModel.search, ignoreCase = true) || message.fileName.contains(viewModel.search, ignoreCase = true)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    if (ui.chat?.isGroup == false) {
                        IconButton(onClick = {
                            callPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        }) { Icon(Icons.Rounded.Call, contentDescription = stringResource(R.string.cd_call)) }
                        IconButton(onClick = {
                            callPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                        }) { Icon(Icons.Rounded.Videocam, contentDescription = stringResource(R.string.cd_video)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                viewModel.search,
                { viewModel.search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text(stringResource(R.string.search_in_chat)) },
                singleLine = true,
            )
            if (ui.canLoadOlder) {
                TextButton(onClick = viewModel::loadOlder, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.load_older))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.id }) { message ->
                    MessageBubble(message, message.senderId == viewModel.uid, ui, viewModel) { selected = message }
                }
            }
            if (ui.blocked) {
                Text(stringResource(R.string.blocked_notice), modifier = Modifier.padding(12.dp))
                TextButton(onClick = viewModel::unblock) { Text(stringResource(R.string.unblock_user)) }
            } else {
                viewModel.reply?.let { current ->
                    Text(stringResource(R.string.reply) + ": " + current.preview(), modifier = Modifier.padding(horizontal = 12.dp))
                }
                if (emoji) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.height(160.dp)) {
                        items(Emoji.size) { index ->
                            Text(
                                Emoji[index],
                                modifier = Modifier.padding(8.dp).combinedClickable(onClick = { viewModel.onComposerChange(viewModel.composer + Emoji[index]) }),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { picker.launch(arrayOf("image/*", "video/*", "audio/*", "application/pdf", "application/zip", "text/plain")) }) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = stringResource(R.string.attach))
                    }
                    IconButton(onClick = { emoji = !emoji }) { Icon(Icons.Rounded.EmojiEmotions, contentDescription = stringResource(R.string.emoji)) }
                    OutlinedTextField(
                        value = viewModel.composer,
                        onValueChange = viewModel::onComposerChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.message_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                    )
                    IconButton(onClick = viewModel::send, enabled = MessagePolicy.canSendText(viewModel.composer)) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.cd_send))
                    }
                    IconButton(onClick = {
                        if (ui.recording) viewModel.stopRecording(true, context) else {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (granted) viewModel.startRecording(context) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) { Icon(Icons.Rounded.Mic, contentDescription = stringResource(R.string.hold_to_record)) }
                }
                if (ui.recording) Text(stringResource(R.string.recording), modifier = Modifier.padding(horizontal = 16.dp))
            }
            ui.error?.let { Text(it.asText(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        }
    }
    selected?.let { message ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(stringResource(R.string.more)) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { viewModel.reply = message; selected = null }) { Text(stringResource(R.string.reply)) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)); selected = null }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { viewModel.react(message, "🔥"); selected = null }) { Text(stringResource(R.string.react)) }
                    TextButton(onClick = { viewModel.pin(message); selected = null }) { Text(stringResource(if (ui.chat?.pinnedMessageId == message.id) R.string.unpin else R.string.pin)) }
                    if (message.senderId == viewModel.uid && MessagePolicy.canEdit(message.createdAt, System.currentTimeMillis(), true, message.deleted)) {
                        TextButton(onClick = {
                            viewModel.editing = message
                            viewModel.composer = message.text
                            selected = null
                        }) { Text(stringResource(R.string.edit)) }
                    }
                    if (message.senderId == viewModel.uid) {
                        TextButton(onClick = { viewModel.delete(message); selected = null }) { Text(stringResource(R.string.delete)) }
                    }
                    TextButton(onClick = { report = true }) { Text(stringResource(R.string.report_user)) }
                    TextButton(onClick = { viewModel.mute(8); selected = null }) { Text(stringResource(R.string.mute_8h)) }
                    if (ui.chat?.isGroup == false) {
                        TextButton(onClick = { viewModel.block(); selected = null }) { Text(stringResource(R.string.block_user)) }
                    } else {
                        TextButton(onClick = { viewModel.leave(); selected = null; onBack() }) { Text(stringResource(R.string.leave_group)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (report) {
        AlertDialog(
            onDismissRequest = { report = false },
            title = { Text(stringResource(R.string.report_user)) },
            text = { Text(stringResource(R.string.report_reason)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.report("spam", "")
                    report = false
                    selected = null
                }) { Text(stringResource(R.string.submit)) }
            },
            dismissButton = { TextButton(onClick = { report = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopRecording(false, context) }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    mine: Boolean,
    ui: ConversationUi,
    viewModel: ConversationViewModel,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 20.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(12.dp),
        ) {
            if (message.replyPreview.isNotBlank()) {
                Text(message.replyPreview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                message.deleted -> Text(stringResource(R.string.deleted_message))
                message.type == ChatMessage.TYPE_TEXT -> Text(message.text)
                message.type == ChatMessage.TYPE_IMAGE -> {
                    var file by remember(message.mediaPath) { mutableStateOf<File?>(null) }
                    LaunchedEffect(message.mediaPath) { file = runCatching { viewModel.cached(message.mediaPath) }.getOrNull() }
                    if (file == null) CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    else AsyncImage(model = file, contentDescription = message.fileName, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    if (message.text.isNotBlank()) Text(message.text, modifier = Modifier.padding(top = 6.dp))
                }
                message.type == ChatMessage.TYPE_VIDEO || message.type == ChatMessage.TYPE_AUDIO -> {
                    var file by remember(message.mediaPath) { mutableStateOf<File?>(null) }
                    LaunchedEffect(message.mediaPath) { file = runCatching { viewModel.cached(message.mediaPath) }.getOrNull() }
                    if (file == null) {
                        Text(stringResource(R.string.downloading))
                    } else if (message.type == ChatMessage.TYPE_VIDEO) {
                        AndroidView(
                            factory = { ctx ->
                                val player = ExoPlayer.Builder(ctx).build()
                                PlayerView(ctx).apply {
                                    this.player = player
                                    tag = player
                                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                                    player.prepare()
                                }
                            },
                            onRelease = { view ->
                                (view.tag as? ExoPlayer)?.release()
                            },
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                        )
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                val audio = file ?: return@launch
                                val player = ExoPlayer.Builder(context).build()
                                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(audio)))
                                player.prepare()
                                player.play()
                            }
                        }) { Text(stringResource(R.string.voice_message)) }
                    }
                }
                else -> {
                    Text(message.fileName.ifBlank { stringResource(R.string.document) })
                    TextButton(onClick = {
                        scope.launch {
                            val file = runCatching { viewModel.cached(message.mediaPath) }.getOrNull() ?: return@launch
                            context.viewFile(file, message.mime)
                        }
                    }) { Text(stringResource(R.string.open_file)) }
                }
            }
            if (message.reactions.isNotEmpty()) {
                Text(message.reactions.values.groupingBy { it }.eachCount().entries.joinToString(" ") { "${it.key} ${it.value}" })
            }
            val otherReceipt = ui.members.firstOrNull { it.uid != viewModel.uid }?.receiptAt ?: 0L
            val status = when {
                !mine -> ""
                otherReceipt >= message.createdAt && otherReceipt > 0 -> stringResource(R.string.read)
                else -> stringResource(R.string.sent)
            }
            Text(
                listOf(formatWhen(context, message.createdAt), if (message.editedAt > 0) stringResource(R.string.edited) else "", status)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
