package ro.blazemessenger.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ro.blazemessenger.app.MainActivity
import ro.blazemessenger.app.R
import ro.blazemessenger.app.call.CallActionReceiver
import ro.blazemessenger.app.call.IncomingCallActivity
import ro.blazemessenger.app.domain.model.LocalSettings

@Singleton
class NotificationCenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_messages_desc)
            enableVibration(true)
        }
        val calls = NotificationChannel(
            CHANNEL_CALLS,
            context.getString(R.string.channel_calls),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_calls_desc)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val transfers = NotificationChannel(
            CHANNEL_TRANSFERS,
            context.getString(R.string.channel_transfers),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_transfers_desc)
        }
        manager.createNotificationChannel(messages)
        manager.createNotificationChannel(calls)
        manager.createNotificationChannel(transfers)
    }

    fun showMessage(
        chatId: String,
        title: String,
        body: String,
        mention: Boolean,
        settings: LocalSettings,
    ) {
        if (!settings.messageNotifications) return
        ensureChannels()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            data = android.net.Uri.parse("blazemessenger://chat/$chatId")
        }
        val pending = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val readIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_CHAT_ID, chatId)
        }
        val readPending = PendingIntent.getActivity(
            context,
            chatId.hashCode() + 1,
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shownBody = if (settings.messagePreview) body else context.getString(R.string.notification_new_message)
        val shownTitle = if (mention) context.getString(R.string.notification_mention) else title
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(shownTitle)
            .setContentText(shownBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shownBody))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setGroup(GROUP_MESSAGES)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(if (settings.messageVibrate) longArrayOf(0, 180, 120, 180) else longArrayOf(0))
            .addAction(0, context.getString(R.string.mark_read), readPending)
            .build()
        manager.notify(chatId.hashCode(), notification)
        val summary = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(shownTitle)
            .setGroup(GROUP_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    fun showIncomingCall(callId: String, callerName: String, video: Boolean) {
        manager.notify(CALL_NOTIFICATION_ID, incomingCallNotification(callId, callerName, video))
    }

    fun incomingCallNotification(
        callId: String,
        callerName: String,
        video: Boolean,
    ): Notification {
        ensureChannels()
        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val accept = PendingIntent.getActivity(
            context,
            callId.hashCode() + 2,
            Intent(context, IncomingCallActivity::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(EXTRA_CALL_ID, callId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context,
            callId.hashCode() + 3,
            Intent(context, CallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_CALL_ID, callId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = context.getString(if (video) R.string.incoming_video else R.string.incoming_voice)
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(callerName.ifBlank { context.getString(R.string.app_name) })
            .setContentText(label)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(0, context.getString(R.string.decline), decline)
            .addAction(0, context.getString(R.string.accept), accept)
            .build()
    }

    fun ongoingCallNotification(name: String, connected: Boolean): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            context,
            42,
            Intent(context, ro.blazemessenger.app.call.ActiveCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(if (connected) R.string.connected else R.string.ongoing_call)
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(name.ifBlank { context.getString(R.string.app_name) })
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun transferNotification(title: String, progress: Int, done: Boolean, failed: Boolean): Notification {
        ensureChannels()
        val text = when {
            failed -> context.getString(R.string.upload_failed)
            done -> context.getString(R.string.upload_complete)
            else -> context.getString(R.string.uploading, progress)
        }
        return NotificationCompat.Builder(context, CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(!done && !failed)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, !done && !failed && progress == 0)
            .build()
    }

    fun showMissed(name: String) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_blaze)
            .setContentTitle(context.getString(R.string.call_missed))
            .setContentText(context.getString(R.string.missed_call_from, name))
            .setAutoCancel(true)
            .build()
        manager.notify(MISSED_ID, notification)
    }

    fun cancel(id: Int) {
        manager.cancel(id)
    }

    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= 34) manager.canUseFullScreenIntent() else true
    }

    companion object {
        const val CHANNEL_MESSAGES = "blaze_messages"
        const val CHANNEL_CALLS = "blaze_calls"
        const val CHANNEL_TRANSFERS = "blaze_transfers"
        const val GROUP_MESSAGES = "blaze_message_group"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_CALL_ID = "callId"
        const val ACTION_MARK_READ = "ro.blazemessenger.app.MARK_READ"
        const val ACTION_ACCEPT = "ro.blazemessenger.app.ACCEPT_CALL"
        const val ACTION_DECLINE = "ro.blazemessenger.app.DECLINE_CALL"
        const val CALL_NOTIFICATION_ID = 7101
        const val TRANSFER_NOTIFICATION_ID = 7102
        private const val SUMMARY_ID = 7200
        private const val MISSED_ID = 7201
    }
}
