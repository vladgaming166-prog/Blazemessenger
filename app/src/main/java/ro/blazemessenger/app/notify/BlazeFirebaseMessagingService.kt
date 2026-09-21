package ro.blazemessenger.app.notify

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ro.blazemessenger.app.call.CallForegroundService
import ro.blazemessenger.app.data.prefs.SettingsRepository
import ro.blazemessenger.app.data.user.UserRepository
import ro.blazemessenger.app.domain.model.CallRecord

@AndroidEntryPoint
class BlazeFirebaseMessagingService : FirebaseMessagingService() {
    @Inject lateinit var users: UserRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var notifications: NotificationCenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { runCatching { users.saveToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        notifications.ensureChannels()
        val data = message.data
        when (data["type"]) {
            "incoming_call" -> {
                val intent = Intent(this, CallForegroundService::class.java).apply {
                    action = CallForegroundService.ACTION_INCOMING
                    putExtra(CallForegroundService.EXTRA_CALL_ID, data["callId"].orEmpty())
                    putExtra(CallForegroundService.EXTRA_CALLER_ID, data["callerId"].orEmpty())
                    putExtra(CallForegroundService.EXTRA_NAME, data["callerName"].orEmpty())
                    putExtra(CallForegroundService.EXTRA_PHOTO, data["callerPhoto"].orEmpty())
                    putExtra(CallForegroundService.EXTRA_VIDEO, data["callType"] == CallRecord.TYPE_VIDEO)
                }
                val started = runCatching { ContextCompat.startForegroundService(this, intent) }
                if (started.isFailure) {
                    notifications.showIncomingCall(
                        callId = data["callId"].orEmpty(),
                        callerName = data["callerName"].orEmpty(),
                        video = data["callType"] == CallRecord.TYPE_VIDEO,
                    )
                }
            }
            "message" -> {
                val chatId = data["chatId"].orEmpty()
                if (chatId.isBlank()) return
                scope.launch {
                    val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: return@launch
                    notifications.showMessage(
                        chatId = chatId,
                        title = data["title"].orEmpty(),
                        body = data["body"].orEmpty(),
                        mention = data["mention"] == "1",
                        settings = settings,
                    )
                }
            }
            "missed_call" -> notifications.showMissed(data["callerName"].orEmpty())
        }
    }
}
