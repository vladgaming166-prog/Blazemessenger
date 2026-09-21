package ro.blazemessenger.app.nav

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class DeepLinks @Inject constructor() {
    private val _chats = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val chats = _chats.asSharedFlow()

    fun openChat(chatId: String) {
        if (chatId.isNotBlank()) _chats.tryEmit(chatId)
    }
}
