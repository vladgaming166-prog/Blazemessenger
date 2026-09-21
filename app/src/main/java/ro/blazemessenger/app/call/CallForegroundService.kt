package ro.blazemessenger.app.call

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ro.blazemessenger.app.R
import ro.blazemessenger.app.notify.NotificationCenter

@AndroidEntryPoint
class CallForegroundService : Service() {
    @Inject lateinit var coordinator: CallCoordinator
    @Inject lateinit var notifications: NotificationCenter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, coordinator.state.value.video) ?: false
        val name = intent?.getStringExtra(EXTRA_NAME) ?: coordinator.state.value.remoteName
        val incoming = intent?.action == ACTION_INCOMING
        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val notification = if (incoming) {
            notifications.incomingCallNotification(callId, name, video)
        } else {
            notifications.ongoingCallNotification(name, coordinator.state.value.phase == CallCoordinator.Phase.CONNECTED)
        }
        val type = if (incoming) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            foregroundTypes(video)
        }
        promote(notification, type)
        if (incoming) {
            val accepted = coordinator.onIncoming(
                callId = callId,
                callerId = intent?.getStringExtra(EXTRA_CALLER_ID).orEmpty(),
                callerName = name,
                callerPhoto = intent?.getStringExtra(EXTRA_PHOTO).orEmpty(),
                video = video,
            )
            if (accepted) {
                val launch = Intent(this, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_CALL_ID, callId)
                }
                runCatching { startActivity(launch) }
                runCatching { TelecomRegistry.addIncoming(this, callId, name) }
            }
        }
        if (watchJob == null) {
            watchJob = scope.launch {
                coordinator.state.collect { ui ->
                    when (ui.phase) {
                        CallCoordinator.Phase.IDLE -> {
                            ServiceCompat.stopForeground(this@CallForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        CallCoordinator.Phase.ENDED -> {
                            if (ui.error == "timeout") {
                                notifications.showMissed(ui.remoteName)
                            }
                            ServiceCompat.stopForeground(this@CallForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        CallCoordinator.Phase.RINGING -> {
                            val incomingNotification = notifications.incomingCallNotification(ui.callId, ui.remoteName, ui.video)
                            promote(incomingNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                        }
                        else -> {
                            val ongoing = notifications.ongoingCallNotification(
                                ui.remoteName.ifBlank { getString(R.string.app_name) },
                                ui.phase == CallCoordinator.Phase.CONNECTED,
                            )
                            promote(ongoing, foregroundTypes(ui.video && ui.cameraEnabled))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }

    private fun promote(notification: Notification, preferredType: Int) {
        val types = linkedSetOf(preferredType)
        if (Build.VERSION.SDK_INT >= 34) {
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        }
        var failure: Exception? = null
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NotificationCenter.CALL_NOTIFICATION_ID, notification, type)
                return
            } catch (error: Exception) {
                failure = error
            }
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NotificationCenter.CALL_NOTIFICATION_ID, notification)
        failure?.let { android.util.Log.w("BlazeCall", "Foreground call service was rejected", it) }
    }

    private fun foregroundTypes(video: Boolean): Int {
        val microphoneGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!microphoneGranted) return ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val cameraGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (video && cameraGranted) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return types
    }

    companion object {
        const val ACTION_INCOMING = "ro.blazemessenger.app.action.INCOMING"
        const val ACTION_ACTIVE = "ro.blazemessenger.app.action.ACTIVE"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_CALLER_ID = "callerId"
        const val EXTRA_NAME = "name"
        const val EXTRA_PHOTO = "photo"
        const val EXTRA_VIDEO = "video"
    }
}
