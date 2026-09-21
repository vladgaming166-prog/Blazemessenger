package ro.blazemessenger.app.call

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import ro.blazemessenger.app.R
import ro.blazemessenger.app.ui.common.Avatar
import ro.blazemessenger.app.ui.theme.BlazeTheme

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {
    @Inject lateinit var coordinator: CallCoordinator

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[android.Manifest.permission.RECORD_AUDIO] == true
        if (mic) {
            coordinator.accept()
            startActivity(android.content.Intent(this, ActiveCallActivity::class.java))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOnLockScreen()
        enableEdgeToEdge()
        if (intent?.action == ro.blazemessenger.app.notify.NotificationCenter.ACTION_ACCEPT) {
            requestCallPermissions()
        }
        setContent {
            val state by coordinator.state.collectAsState()
            BlazeTheme(darkTheme = true) {
                IncomingCallScreen(
                    state = state,
                    onAccept = { requestCallPermissions() },
                    onDecline = {
                        coordinator.decline()
                        finish()
                    },
                )
            }
            LaunchedEffect(state.phase) {
                if (state.phase == CallCoordinator.Phase.IDLE || state.phase == CallCoordinator.Phase.ENDED) finish()
            }
        }
    }

    private fun requestCallPermissions() {
        val needed = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
        if (coordinator.state.value.video) needed += android.Manifest.permission.CAMERA
        permissions.launch(needed.toTypedArray())
    }

    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }
}

@AndroidEntryPoint
class ActiveCallActivity : ComponentActivity() {
    @Inject lateinit var coordinator: CallCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()
        val service = android.content.Intent(this, CallForegroundService::class.java).apply {
            action = CallForegroundService.ACTION_ACTIVE
        }
        androidx.core.content.ContextCompat.startForegroundService(this, service)
        setContent {
            val state by coordinator.state.collectAsState()
            BlazeTheme(darkTheme = true) {
                ActiveCallScreen(
                    state = state,
                    coordinator = coordinator,
                    onEnd = {
                        coordinator.end()
                        finish()
                    },
                )
            }
            LaunchedEffect(state.phase) {
                if (state.phase == CallCoordinator.Phase.IDLE || state.phase == CallCoordinator.Phase.ENDED) finish()
            }
        }
    }
}

@Composable
private fun IncomingCallScreen(
    state: CallCoordinator.Ui,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF140E0C))
            .padding(28.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(label = state.remoteName, photoFile = null, size = 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(state.remoteName.ifBlank { stringResource(R.string.app_name) }, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (state.video) R.string.incoming_video else R.string.incoming_voice),
                color = Color(0xFFFFC7A8),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            CallButton(stringResource(R.string.decline), Color(0xFFB42318), Icons.Rounded.CallEnd, onDecline)
            CallButton(stringResource(R.string.accept), Color(0xFF2E7D32), Icons.Rounded.Call, onAccept)
        }
    }
}

@Composable
private fun ActiveCallScreen(
    state: CallCoordinator.Ui,
    coordinator: CallCoordinator,
    onEnd: () -> Unit,
) {
    val status = when (state.phase) {
        CallCoordinator.Phase.OUTGOING -> stringResource(R.string.outgoing_call)
        CallCoordinator.Phase.RINGING -> stringResource(R.string.ringing)
        CallCoordinator.Phase.CONNECTING -> stringResource(R.string.connecting)
        CallCoordinator.Phase.CONNECTED -> stringResource(R.string.connected)
        CallCoordinator.Phase.ENDED -> when (state.error) {
            "network" -> stringResource(R.string.call_network_error)
            "timeout" -> stringResource(R.string.call_timeout)
            else -> stringResource(R.string.call_ended)
        }
        else -> stringResource(R.string.connecting)
    }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF100E0C))) {
        if (state.video) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> SurfaceViewRenderer(context) },
                update = { view ->
                    if (view.tag != "ready") {
                        val egl = coordinator.eglContext()
                        if (egl != null) {
                            view.init(egl, null)
                            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            view.setEnableHardwareScaler(true)
                            coordinator.attachRemote(view)
                            view.tag = "ready"
                        }
                    }
                },
                onRelease = {
                    coordinator.detachRemote(it)
                    it.release()
                },
            )
            AndroidView(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(120.dp, 160.dp)
                    .clip(MaterialTheme.shapes.medium),
                factory = { context -> SurfaceViewRenderer(context) },
                update = { view ->
                    if (view.tag != "ready") {
                        val egl = coordinator.eglContext()
                        if (egl != null) {
                            view.init(egl, null)
                            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            view.setZOrderMediaOverlay(true)
                            coordinator.attachLocal(view)
                            view.tag = "ready"
                        }
                    }
                },
                onRelease = {
                    coordinator.detachLocal(it)
                    it.release()
                },
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.video) {
                Avatar(label = state.remoteName, photoFile = null, size = 120.dp)
                Spacer(Modifier.height(16.dp))
            }
            Text(state.remoteName, style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            if (state.phase == CallCoordinator.Phase.CONNECTED) {
                DurationText(state.answeredAt)
            } else {
                Text(status, color = Color(0xFFFFC7A8), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallButton(
                stringResource(if (state.muted) R.string.unmute_mic else R.string.mute),
                Color(0xFF3A302B),
                if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                coordinator::toggleMute,
            )
            CallButton(
                stringResource(if (state.speaker) R.string.speaker else R.string.earpiece),
                Color(0xFF3A302B),
                Icons.Rounded.VolumeUp,
                coordinator::toggleSpeaker,
            )
            if (state.video) {
                CallButton(
                    stringResource(if (state.cameraEnabled) R.string.camera_on else R.string.camera_off),
                    Color(0xFF3A302B),
                    if (state.cameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                    coordinator::toggleCamera,
                )
                CallButton(stringResource(R.string.switch_camera), Color(0xFF3A302B), Icons.Rounded.Cameraswitch, coordinator::switchCamera)
            }
            CallButton(stringResource(R.string.end_call), Color(0xFFB42318), Icons.Rounded.CallEnd, onEnd)
        }
    }
}

@Composable
private fun DurationText(answeredAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(answeredAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((now - answeredAt).coerceAtLeast(0L)) / 1000
    val text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    Text(text, color = Color.White, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun CallButton(
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(color),
        ) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}
