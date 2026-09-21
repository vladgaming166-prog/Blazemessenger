package ro.blazemessenger.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.blazemessenger.app.BuildConfig
import ro.blazemessenger.app.R
import ro.blazemessenger.app.core.ByteFormats
import ro.blazemessenger.app.core.CallStates
import ro.blazemessenger.app.data.auth.AuthRepository
import ro.blazemessenger.app.data.auth.authError
import ro.blazemessenger.app.data.call.CallRepository
import ro.blazemessenger.app.data.chat.ChatRepository
import ro.blazemessenger.app.data.media.MediaRepository
import ro.blazemessenger.app.data.prefs.SettingsRepository
import ro.blazemessenger.app.data.story.StoryRepository
import ro.blazemessenger.app.data.toAppError
import ro.blazemessenger.app.data.user.UserRepository
import ro.blazemessenger.app.domain.model.AppError
import ro.blazemessenger.app.domain.model.AppLanguage
import ro.blazemessenger.app.domain.model.CallRecord
import ro.blazemessenger.app.domain.model.Chat
import ro.blazemessenger.app.domain.model.LocalSettings
import ro.blazemessenger.app.domain.model.PrivacySettings
import ro.blazemessenger.app.domain.model.RingtoneChoice
import ro.blazemessenger.app.domain.model.Story
import ro.blazemessenger.app.domain.model.ThemeMode
import ro.blazemessenger.app.domain.model.UserProfile
import ro.blazemessenger.app.notify.CallRingtonePlayer
import ro.blazemessenger.app.ui.common.Avatar
import ro.blazemessenger.app.ui.common.asText
import ro.blazemessenger.app.ui.common.formatWhen
import ro.blazemessenger.app.ui.common.rememberCachedFile

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    fun finishOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingDone() }
    }
}

data class HomeUi(
    val uid: String = "",
    val profile: UserProfile? = null,
    val chats: List<Chat> = emptyList(),
    val people: Map<String, UserProfile> = emptyMap(),
    val calls: List<CallRecord> = emptyList(),
    val stories: List<Story> = emptyList(),
    val blocked: List<String> = emptyList(),
    val settings: LocalSettings = LocalSettings(),
    val cacheBytes: Long = 0L,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val auth: FirebaseAuth,
    private val authRepository: AuthRepository,
    private val users: UserRepository,
    private val chats: ChatRepository,
    private val calls: CallRepository,
    private val stories: StoryRepository,
    private val media: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val ringtone: CallRingtonePlayer,
) : ViewModel() {
    private val people = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    private val cache = MutableStateFlow(0L)
    val error = MutableStateFlow<AppError?>(null)
    val message = MutableStateFlow<Int?>(null)

    private val remote = combine(
        users.observeProfile(auth.currentUser?.uid.orEmpty()),
        chats.observeChats(auth.currentUser?.uid.orEmpty()),
        calls.observeHistory(auth.currentUser?.uid.orEmpty()),
        stories.observeActive(),
        users.observeBlocked(auth.currentUser?.uid.orEmpty()),
    ) { profile, chatList, callList, storyList, blockedList ->
        RemoteSlice(profile, chatList, callList, storyList, blockedList)
    }

    val state = combine(remote, settingsRepository.settings, people, cache) { slice, settings, peopleMap, bytes ->
        HomeUi(
            uid = auth.currentUser?.uid.orEmpty(),
            profile = slice.profile,
            chats = slice.chats,
            calls = slice.calls,
            stories = slice.stories,
            blocked = slice.blocked,
            settings = settings,
            people = peopleMap,
            cacheBytes = bytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi(uid = auth.currentUser?.uid.orEmpty()))

    init {
        refreshCache()
        viewModelScope.launch {
            state.collect { ui ->
                val missing = ui.chats.flatMap { it.memberIds }.distinct().filter { it.isNotBlank() && it !in ui.people }
                if (missing.isNotEmpty()) {
                    val loaded = missing.mapNotNull { uid -> runCatching { users.profile(uid) }.getOrNull()?.let { uid to it } }
                    people.value = people.value + loaded.toMap()
                }
            }
        }
    }

    fun search(query: String, onResult: (List<UserProfile>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { users.search(query) }.getOrElse {
                error.value = it.toAppError()
                emptyList()
            })
        }
    }

    fun openDirect(otherUid: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { chats.createDirect(otherUid) }
                .onSuccess(onReady)
                .onFailure { error.value = it.toAppError() }
        }
    }

    fun createGroup(title: String, ids: List<String>, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { chats.createGroup(title, ids) }
                .onSuccess(onReady)
                .onFailure { error.value = it.toAppError() }
        }
    }

    fun publishStory(text: String, path: String) {
        viewModelScope.launch {
            runCatching { stories.publish(text, path) }.onFailure { error.value = it.toAppError() }
        }
    }

    fun setTheme(mode: ThemeMode) = save { settingsRepository.setTheme(mode) }
    fun setLanguage(language: AppLanguage) = save { settingsRepository.setLanguage(language) }
    fun setRingtone(choice: RingtoneChoice) = save { settingsRepository.setRingtone(choice) }
    fun setVolume(volume: Float) = save { settingsRepository.setRingtoneVolume(volume) }
    fun setVibrate(enabled: Boolean) = save { settingsRepository.setVibrateCalls(enabled) }
    fun setMessages(enabled: Boolean) = save { settingsRepository.setMessageNotifications(enabled) }
    fun setPreview(enabled: Boolean) = save { settingsRepository.setMessagePreview(enabled) }
    fun setMessageVibrate(enabled: Boolean) = save { settingsRepository.setMessageVibrate(enabled) }
    fun setPrivacy(privacy: PrivacySettings) = save { users.updatePrivacy(auth.currentUser?.uid.orEmpty(), privacy) }
    fun setCustomRingtone(uri: String) = save { settingsRepository.setCustomRingtone(uri) }
    fun previewRingtone() {
        viewModelScope.launch { ringtone.preview(settingsRepository.settings.first()) }
    }

    suspend fun stageStory(context: Context, uri: Uri): String {
        val staged = media.stage(context, uri)
        val prepared = media.compressImageIfNeeded(media.inspect(staged))
        val path = "stories/${auth.currentUser?.uid}/${System.currentTimeMillis()}.jpg"
        media.upload(path, prepared.uri) {}
        return path
    }

    fun stopPreview() = ringtone.stop()

    fun clearCache() {
        media.clearCache()
        refreshCache()
        message.value = R.string.cache_cleared
    }

    fun unblock(uid: String) = save { users.unblock(uid) }

    fun updateProfile(name: String, bio: String, photo: String?) = save {
        users.updateProfile(auth.currentUser?.uid.orEmpty(), name, bio, photo)
    }

    fun uploadAvatar(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val staged = media.stage(context, uri)
                val prepared = media.compressImageIfNeeded(media.inspect(staged))
                val path = "profiles/${auth.currentUser?.uid}/avatar.jpg"
                media.upload(path, prepared.uri) {}
                users.updateProfile(auth.currentUser?.uid.orEmpty(), state.value.profile?.displayName.orEmpty(), state.value.profile?.bio.orEmpty(), path)
            }.onFailure { error.value = it.toAppError() }
        }
    }

    fun signOut() = authRepository.signOut()

    fun deleteAccount(password: String?) {
        viewModelScope.launch {
            runCatching {
                if (!password.isNullOrBlank()) authRepository.reauthenticate(password)
                authRepository.deleteAccount()
            }.onFailure { error.value = it.authError() }
        }
    }

    fun loadFile(path: String) = viewModelScope.launch { }
    suspend fun cached(path: String) = media.cachedFile(path)

    private fun refreshCache() {
        cache.value = media.cacheSizeBytes()
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { error.value = it.toAppError() } }
    }

    fun titleFor(chat: Chat, ui: HomeUi): String {
        if (chat.isGroup) return chat.title.ifBlank { "Group" }
        val other = chat.memberIds.firstOrNull { it != ui.uid }
        return ui.people[other]?.displayName ?: other.orEmpty()
    }
}

@Composable
fun HomeScreen(nav: NavHostController, viewModel: HomeViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(
        stringResource(R.string.tab_chats) to Icons.Rounded.Chat,
        stringResource(R.string.tab_calls) to Icons.Rounded.Call,
        stringResource(R.string.tab_contacts) to Icons.Rounded.Contacts,
        stringResource(R.string.tab_stories) to Icons.Rounded.AutoAwesome,
        stringResource(R.string.tab_settings) to Icons.Rounded.Settings,
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            bottomBar = {
                if (!expanded) {
                    NavigationBar {
                        labels.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (tab == 0) {
                    FloatingActionButton(onClick = { tab = 2 }) {
                        Icon(Icons.Rounded.Chat, contentDescription = stringResource(R.string.new_chat))
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (expanded) {
                    NavigationRail {
                        labels.forEachIndexed { index, item ->
                            NavigationRailItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.second, contentDescription = item.first) },
                                label = { Text(item.first) },
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> ChatList(ui, viewModel) { nav.navigate("chat/$it") }
                        1 -> CallList(ui, viewModel)
                        2 -> PeopleTab(ui, viewModel) { nav.navigate("chat/$it") }
                        3 -> StoriesTab(ui, viewModel)
                        else -> SettingsTab(ui, viewModel, onProfile = { nav.navigate("profile") })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatList(ui: HomeUi, viewModel: HomeViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val visible = ui.chats.filter {
        viewModel.titleFor(it, ui).contains(query, ignoreCase = true) || it.lastMessage.contains(query, ignoreCase = true)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.chats_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), label = { Text(stringResource(R.string.search_chats)) })
        if (visible.isEmpty()) {
            Empty(stringResource(R.string.chats_empty_title), stringResource(R.string.chats_empty_body))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(visible, key = { it.id }) { chat ->
                    val title = viewModel.titleFor(chat, ui)
                    val unread = chat.unreadCounts[ui.uid] ?: 0
                    val photo = ui.people[chat.memberIds.firstOrNull { it != ui.uid }]?.photoPath.orEmpty()
                    Card(modifier = Modifier.fillMaxWidth().clickable { open(chat.id) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(title, rememberCachedFile(photo) { viewModel.cached(it) })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium)
                                Text(chat.lastMessage.ifBlank { stringResource(R.string.direct_chat) }, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatWhen(context, chat.lastMessageAt), style = MaterialTheme.typography.bodyMedium)
                                if (unread > 0 && chat.lastMessageSenderId != ui.uid) {
                                    Text(
                                        unread.coerceAtMost(99).toString(),
                                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallList(ui: HomeUi, viewModel: HomeViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.calls_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.turn_notice), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        if (ui.calls.isEmpty()) {
            Empty(stringResource(R.string.calls_empty_title), stringResource(R.string.calls_empty_body))
        } else {
            LazyColumn {
                items(ui.calls, key = { it.id }) { call ->
                    val other = if (call.callerId == ui.uid) call.calleeId else call.callerId
                    val name = ui.people[other]?.displayName ?: other
                    val label = when (call.state) {
                        CallStates.MISSED -> stringResource(R.string.call_missed)
                        CallStates.DECLINED -> stringResource(R.string.call_declined)
                        CallStates.FAILED -> stringResource(R.string.call_failed)
                        CallStates.BUSY -> stringResource(R.string.call_busy)
                        else -> if (call.callerId == ui.uid) stringResource(R.string.outgoing) else stringResource(R.string.incoming)
                    }
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("$label · ${if (call.type == CallRecord.TYPE_VIDEO) stringResource(R.string.video_call) else stringResource(R.string.voice_call)}") },
                        trailingContent = { Text(formatWhen(context, call.createdAt)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeopleTab(ui: HomeUi, viewModel: HomeViewModel, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var groupMode by rememberSaveable { mutableStateOf(false) }
    var groupTitle by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    val known = ui.chats.filter { !it.isGroup }.mapNotNull { chat ->
        val other = chat.memberIds.firstOrNull { it != ui.uid }
        ui.people[other]
    }.distinctBy { it.uid }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.contacts_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(query, { query = it; viewModel.search(it) { found -> results = found } }, label = { Text(stringResource(R.string.search_users)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Row {
            TextButton(onClick = { groupMode = !groupMode }) { Text(stringResource(R.string.new_group)) }
        }
        if (groupMode) {
            OutlinedTextField(groupTitle, { groupTitle = it }, label = { Text(stringResource(R.string.group_title)) }, modifier = Modifier.fillMaxWidth())
            Text(selected.joinToString { it.displayName })
            Button(onClick = { viewModel.createGroup(groupTitle, selected.map { it.uid }, open) }, enabled = selected.size >= 2 && groupTitle.isNotBlank()) {
                Text(stringResource(R.string.group_create))
            }
        }
        val rows = if (query.length >= 2) results else known
        if (rows.isEmpty()) Empty(stringResource(R.string.contacts_empty_title), stringResource(R.string.contacts_empty_body))
        LazyColumn {
            items(rows, key = { it.uid }) { person ->
                ListItem(
                    headlineContent = { Text(person.displayName.ifBlank { person.username }) },
                    supportingContent = { Text("@${person.username}") },
                    leadingContent = { Avatar(person.displayName, rememberCachedFile(person.photoPath) { viewModel.cached(it) }, size = 40.dp) },
                    modifier = Modifier.clickable {
                        if (groupMode) {
                            selected = if (selected.any { it.uid == person.uid }) selected.filterNot { it.uid == person.uid } else selected + person
                        } else {
                            viewModel.openDirect(person.uid, open)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StoriesTab(ui: HomeUi, viewModel: HomeViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) { viewModel.stageStory(context, uri) }
                viewModel.publishStory(text, path)
                text = ""
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.stories_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.story_text_hint)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.publishStory(text, "") }, enabled = text.isNotBlank()) { Text(stringResource(R.string.story_publish)) }
            TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.photo)) }
        }
        val active = ui.stories.filter { it.expiresAt > System.currentTimeMillis() }
        if (active.isEmpty()) Empty(stringResource(R.string.stories_empty_title), stringResource(R.string.stories_empty_body))
        LazyColumn {
            items(active, key = { it.id }) { story ->
                val author = ui.people[story.authorId]?.displayName ?: story.authorId
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.story_from, author), style = MaterialTheme.typography.titleMedium)
                        if (story.text.isNotBlank()) Text(story.text, modifier = Modifier.padding(top = 6.dp))
                        Text(stringResource(R.string.story_viewers, story.viewerCount), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

private data class RemoteSlice(
    val profile: UserProfile?,
    val chats: List<Chat>,
    val calls: List<CallRecord>,
    val stories: List<Story>,
    val blocked: List<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(ui: HomeUi, viewModel: HomeViewModel, onProfile: () -> Unit) {
    val context = LocalContext.current
    var logout by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setCustomRingtone(uri.toString())
            viewModel.setRingtone(RingtoneChoice.CUSTOM)
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium) }
        item {
            ListItem(
                headlineContent = { Text(ui.profile?.displayName.orEmpty()) },
                supportingContent = { Text("@${ui.profile?.username.orEmpty()}") },
                leadingContent = { Avatar(ui.profile?.displayName.orEmpty(), rememberCachedFile(ui.profile?.photoPath.orEmpty()) { viewModel.cached(it) }) },
                modifier = Modifier.clickable(onClick = onProfile),
            )
        }
        item { Section(stringResource(R.string.appearance)) }
        item { Choice(stringResource(R.string.theme_system), ui.settings.themeMode == ThemeMode.SYSTEM) { viewModel.setTheme(ThemeMode.SYSTEM) } }
        item { Choice(stringResource(R.string.theme_light), ui.settings.themeMode == ThemeMode.LIGHT) { viewModel.setTheme(ThemeMode.LIGHT) } }
        item { Choice(stringResource(R.string.theme_dark), ui.settings.themeMode == ThemeMode.DARK) { viewModel.setTheme(ThemeMode.DARK) } }
        item { Section(stringResource(R.string.language)) }
        item { Choice(stringResource(R.string.language_system), ui.settings.language == AppLanguage.SYSTEM) { viewModel.setLanguage(AppLanguage.SYSTEM) } }
        item { Choice(stringResource(R.string.language_english), ui.settings.language == AppLanguage.EN) { viewModel.setLanguage(AppLanguage.EN) } }
        item { Choice(stringResource(R.string.language_romanian), ui.settings.language == AppLanguage.RO) { viewModel.setLanguage(AppLanguage.RO) } }
        item { Section(stringResource(R.string.notifications)) }
        item { Toggle(stringResource(R.string.message_notifications), ui.settings.messageNotifications, viewModel::setMessages) }
        item { Toggle(stringResource(R.string.notification_preview), ui.settings.messagePreview, viewModel::setPreview) }
        item { Toggle(stringResource(R.string.message_vibrate), ui.settings.messageVibrate, viewModel::setMessageVibrate) }
        item { Section(stringResource(R.string.ringtone)) }
        item { Choice(stringResource(R.string.ringtone_default), ui.settings.ringtone == RingtoneChoice.DEFAULT) { viewModel.setRingtone(RingtoneChoice.DEFAULT) } }
        item { Choice(stringResource(R.string.ringtone_soft), ui.settings.ringtone == RingtoneChoice.SOFT) { viewModel.setRingtone(RingtoneChoice.SOFT) } }
        item { Choice(stringResource(R.string.ringtone_custom), ui.settings.ringtone == RingtoneChoice.CUSTOM) { ringtonePicker.launch(arrayOf("audio/*")) } }
        item {
            Text(stringResource(R.string.ringtone_volume))
            Slider(ui.settings.ringtoneVolume, viewModel::setVolume)
            Row {
                TextButton(onClick = { viewModel.previewRingtone() }) { Text(stringResource(R.string.preview_ringtone)) }
                TextButton(onClick = viewModel::stopPreview) { Text(stringResource(R.string.stop_preview)) }
            }
            Text(stringResource(R.string.dnd_respected), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Toggle(stringResource(R.string.vibrate_calls), ui.settings.vibrateCalls, viewModel::setVibrate) }
        item { Section(stringResource(R.string.privacy)) }
        item { Toggle(stringResource(R.string.read_receipts), ui.profile?.privacy?.readReceipts != false) { viewModel.setPrivacy((ui.profile?.privacy ?: PrivacySettings()).copy(readReceipts = it)) } }
        item { Toggle(stringResource(R.string.show_online), ui.profile?.privacy?.showOnline != false) { viewModel.setPrivacy((ui.profile?.privacy ?: PrivacySettings()).copy(showOnline = it)) } }
        item { Toggle(stringResource(R.string.show_last_seen), ui.profile?.privacy?.showLastSeen != false) { viewModel.setPrivacy((ui.profile?.privacy ?: PrivacySettings()).copy(showLastSeen = it)) } }
        item { Text(stringResource(R.string.privacy_body), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Section(stringResource(R.string.blocked_users)) }
        if (ui.blocked.isEmpty()) {
            item { Text(stringResource(R.string.blocked_empty)) }
        } else {
            items(ui.blocked) { uid ->
                ListItem(
                    headlineContent = { Text(ui.people[uid]?.displayName ?: uid) },
                    trailingContent = { TextButton(onClick = { viewModel.unblock(uid) }) { Text(stringResource(R.string.unblock_user)) } },
                )
            }
        }
        item { Section(stringResource(R.string.storage)) }
        item {
            Text(stringResource(R.string.storage_used, ByteFormats.format(ui.cacheBytes)))
            TextButton(onClick = viewModel::clearCache) { Text(stringResource(R.string.clear_cache)) }
        }
        item { Section(stringResource(R.string.about)) }
        item { Text(stringResource(R.string.about_body, BuildConfig.VERSION_NAME)) }
        item {
            TextButton(onClick = { logout = true }) { Text(stringResource(R.string.logout)) }
            TextButton(onClick = { delete = true }) { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }
        }
        item {
            viewModel.error.collectAsState().value?.let { Text(it.asText(), color = MaterialTheme.colorScheme.error) }
            viewModel.message.collectAsState().value?.let { Text(stringResource(it)) }
        }
    }
    if (logout) {
        AlertDialog(
            onDismissRequest = { logout = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = { TextButton(onClick = { logout = false; viewModel.signOut() }) { Text(stringResource(R.string.logout)) } },
            dismissButton = { TextButton(onClick = { logout = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text(stringResource(R.string.delete_account)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_account_body))
                    OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.auth_password)) })
                    OutlinedTextField(confirm, { confirm = it }, label = { Text(stringResource(R.string.auth_username)) })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirm == ui.profile?.username) {
                            viewModel.deleteAccount(password)
                            delete = false
                        }
                    },
                ) { Text(stringResource(R.string.delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val ui by viewModel.state.collectAsState()
    val profile = ui.profile
    var name by remember(profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
    var bio by remember(profile?.bio) { mutableStateOf(profile?.bio.orEmpty()) }
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.uploadAvatar(context, uri)
    }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Avatar(name, rememberCachedFile(profile?.photoPath.orEmpty()) { viewModel.cached(it) }, size = 88.dp)
        TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text(stringResource(R.string.change_photo)) }
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.auth_display_name)) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bio, { bio = it }, label = { Text(stringResource(R.string.bio)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(stringResource(R.string.user_id), modifier = Modifier.padding(top = 12.dp))
        Text(ui.uid, modifier = Modifier.clickable {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("uid", ui.uid))
            copied = true
        })
        if (copied) Text(stringResource(R.string.copied))
        Button(onClick = { viewModel.updateProfile(name, bio, null) }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(if (selected) "• $label" else label) }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Suppress("unused")
private val darkIcon = Icons.Rounded.DarkMode
