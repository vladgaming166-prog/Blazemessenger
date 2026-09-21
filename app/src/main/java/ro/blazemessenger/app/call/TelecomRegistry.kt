package ro.blazemessenger.app.call

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object TelecomRegistry {
    fun handle(context: Context): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, BlazeConnectionService::class.java),
            "blaze",
        )
    }

    fun register(context: Context) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        val account = PhoneAccount.builder(handle(context), "BlazeMessenger")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        runCatching { telecom.registerPhoneAccount(account) }
    }

    fun addIncoming(context: Context, callId: String, name: String) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            val nested = Bundle().apply {
                putString("callId", callId)
                putString("name", name)
            }
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, nested)
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
        }
        val address = Uri.fromParts("sip", callId, null)
        extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address)
        runCatching { telecom.addNewIncomingCall(handle(context), extras) }
    }
}

@AndroidEntryPoint
class BlazeConnectionService : android.telecom.ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: android.telecom.ConnectionRequest,
    ): android.telecom.Connection {
        return buildConnection(request, incoming = true)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: android.telecom.ConnectionRequest,
    ): android.telecom.Connection {
        return buildConnection(request, incoming = false)
    }

    private fun buildConnection(
        request: android.telecom.ConnectionRequest,
        incoming: Boolean,
    ): android.telecom.Connection {
        val connection = BlazeConnection(applicationContext)
        connection.connectionProperties = android.telecom.Connection.PROPERTY_SELF_MANAGED
        connection.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        val name = request.extras?.getString("name").orEmpty()
        if (name.isNotBlank()) {
            connection.setCallerDisplayName(name, TelecomManager.PRESENTATION_ALLOWED)
        }
        connection.audioModeIsVoip = true
        if (Build.VERSION.SDK_INT >= 28) {
            connection.connectionCapabilities = connection.connectionCapabilities or
                android.telecom.Connection.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL
        }
        if (incoming) {
            connection.setInitializing()
            connection.setRinging()
        } else {
            connection.setDialing()
        }
        return connection
    }
}

class BlazeConnection(
    private val appContext: Context,
) : android.telecom.Connection() {
    override fun onAnswer() {
        setActive()
        val coordinator = entry().coordinator()
        coordinator.accept()
    }

    override fun onReject() {
        entry().coordinator().decline()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        entry().coordinator().end()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        entry().coordinator().end()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
    }

    override fun onShowIncomingCallUi() {
        val intent = Intent(appContext, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { appContext.startActivity(intent) }
    }

    private fun entry(): ro.blazemessenger.app.di.ServiceEntryPoint {
        return dagger.hilt.android.EntryPointAccessors.fromApplication(
            appContext,
            ro.blazemessenger.app.di.ServiceEntryPoint::class.java,
        )
    }
}

@AndroidEntryPoint
class CallActionReceiver : android.content.BroadcastReceiver() {
    @Inject lateinit var coordinator: CallCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ro.blazemessenger.app.notify.NotificationCenter.ACTION_DECLINE) {
            coordinator.decline()
        }
    }
}
