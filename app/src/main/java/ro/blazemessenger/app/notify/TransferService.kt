package ro.blazemessenger.app.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ro.blazemessenger.app.core.MediaKind
import ro.blazemessenger.app.data.chat.ChatRepository
import ro.blazemessenger.app.data.media.MediaRepository
import ro.blazemessenger.app.domain.model.ChatMessage

@AndroidEntryPoint
class TransferService : Service() {
    @Inject lateinit var media: MediaRepository
    @Inject lateinit var chats: ChatRepository
    @Inject lateinit var notifications: NotificationCenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_NAME) ?: "file"
        val initial = notifications.transferNotification(title, 0, done = false, failed = false)
        ServiceCompat.startForeground(
            this,
            NotificationCenter.TRANSFER_NOTIFICATION_ID,
            initial,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val uri = intent?.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        if (chatId.isNullOrBlank() || uri == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val caption = intent.getStringExtra(EXTRA_CAPTION).orEmpty()
        scope.launch {
            var temp: java.io.File? = null
            try {
                var prepared = media.inspect(uri)
                if (prepared.kind == MediaKind.IMAGE) {
                    prepared = media.compressImageIfNeeded(prepared)
                    temp = prepared.tempFile
                }
                val path = "chats/$chatId/${UUID.randomUUID()}_${prepared.name}"
                media.upload(path, prepared.uri) { fraction ->
                    val note = notifications.transferNotification(prepared.name, (fraction * 100).toInt(), false, false)
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.notify(NotificationCenter.TRANSFER_NOTIFICATION_ID, note)
                }
                val type = when (prepared.kind) {
                    MediaKind.IMAGE -> ChatMessage.TYPE_IMAGE
                    MediaKind.VIDEO -> ChatMessage.TYPE_VIDEO
                    MediaKind.AUDIO -> ChatMessage.TYPE_AUDIO
                    MediaKind.DOCUMENT -> ChatMessage.TYPE_FILE
                }
                chats.sendMedia(chatId, type, path, prepared.mime, prepared.name, prepared.size, caption)
                val done = notifications.transferNotification(prepared.name, 100, done = true, failed = false)
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(NotificationCenter.TRANSFER_NOTIFICATION_ID, done)
            } catch (_: Throwable) {
                val failed = notifications.transferNotification(title, 0, done = false, failed = true)
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(NotificationCenter.TRANSFER_NOTIFICATION_ID, failed)
            } finally {
                temp?.delete()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"
        const val EXTRA_CAPTION = "caption"
    }
}
