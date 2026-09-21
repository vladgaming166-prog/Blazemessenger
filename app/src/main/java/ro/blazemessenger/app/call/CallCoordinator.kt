package ro.blazemessenger.app.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.core.CallStates
import ro.blazemessenger.app.data.call.CallRepository
import ro.blazemessenger.app.data.prefs.SettingsRepository
import ro.blazemessenger.app.domain.model.CallRecord
import ro.blazemessenger.app.domain.model.IceCandidateDto
import ro.blazemessenger.app.notify.CallRingtonePlayer

@Singleton
class CallCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calls: CallRepository,
    private val settingsRepository: SettingsRepository,
    private val ringtone: CallRingtonePlayer,
    private val config: BackendConfig,
) {
    enum class Phase { IDLE, OUTGOING, RINGING, CONNECTING, CONNECTED, ENDED }

    data class Ui(
        val callId: String = "",
        val phase: Phase = Phase.IDLE,
        val remoteName: String = "",
        val remotePhoto: String = "",
        val remoteUid: String = "",
        val video: Boolean = false,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val cameraEnabled: Boolean = true,
        val answeredAt: Long = 0L,
        val error: String? = null,
        val incoming: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("blaze_call", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var remoteDescriptionSet = false
    private val pendingIce = mutableListOf<IceCandidate>()
    private val seenCandidates = mutableSetOf<String>()
    private var listenJob: Job? = null
    private var timeoutJob: Job? = null
    private var latest: CallRecord? = null
    private var usingFrontCamera = true

    fun eglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun onIncoming(
        callId: String,
        callerId: String,
        callerName: String,
        callerPhoto: String,
        video: Boolean,
    ): Boolean {
        if (!config.configured) return false
        val phase = _state.value.phase
        if (_state.value.callId == callId && (phase == Phase.RINGING || phase == Phase.CONNECTING || phase == Phase.CONNECTED)) {
            return true
        }
        if (phase == Phase.OUTGOING || phase == Phase.RINGING || phase == Phase.CONNECTING || phase == Phase.CONNECTED) {
            scope.launch {
                runCatching { calls.transition(callId, CallStates.BUSY, endedAt = System.currentTimeMillis()) }
            }
            return false
        }
        remember(callId)
        _state.value = Ui(
            callId = callId,
            phase = Phase.RINGING,
            remoteName = callerName,
            remotePhoto = callerPhoto,
            remoteUid = callerId,
            video = video,
            incoming = true,
            speaker = video,
            cameraEnabled = video,
        )
        scope.launch {
            val settings = settingsRepository.settings.first()
            ringtone.start(settings)
        }
        listen(callId, caller = false)
        armTimeout(callId)
        return true
    }

    fun placeCall(
        calleeId: String,
        calleeName: String,
        calleePhoto: String,
        video: Boolean,
        callerName: String,
        callerPhoto: String,
    ) {
        if (!config.configured) {
            _state.value = Ui(phase = Phase.ENDED, error = "config")
            return
        }
        val uid = calls.currentUid() ?: return
        scope.launch {
            mutex.withLock {
                releaseMediaLocked()
                _state.value = Ui(
                    phase = Phase.OUTGOING,
                    remoteName = calleeName,
                    remotePhoto = calleePhoto,
                    remoteUid = calleeId,
                    video = video,
                    speaker = video,
                    cameraEnabled = video,
                )
                try {
                    ensureFactory()
                    createPeer(video)
                    val offer = createOffer()
                    setLocal(offer)
                    val id = calls.create(
                        CallRecord(
                            callerId = uid,
                            calleeId = calleeId,
                            type = if (video) CallRecord.TYPE_VIDEO else CallRecord.TYPE_VOICE,
                            createdAt = System.currentTimeMillis(),
                            offer = offer.description,
                            callerName = callerName,
                            callerPhoto = callerPhoto,
                        ),
                    )
                    remember(id)
                    _state.update { it.copy(callId = id) }
                    applySpeaker(video)
                    listen(id, caller = true)
                    armTimeout(id)
                } catch (error: Throwable) {
                    _state.update { it.copy(phase = Phase.ENDED, error = error.message ?: "failed") }
                    releaseMediaLocked()
                }
            }
        }
    }

    fun accept() {
        val current = _state.value
        if (current.phase != Phase.RINGING || current.callId.isBlank()) return
        ringtone.stop()
        scope.launch {
            mutex.withLock {
                try {
                    val record = latest ?: calls.get(current.callId) ?: error("missing-offer")
                    if (record.offer.isBlank()) error("missing-offer")
                    ensureFactory()
                    createPeer(current.video)
                    setRemote(SessionDescription(SessionDescription.Type.OFFER, record.offer))
                    val answer = createAnswer()
                    setLocal(answer)
                    calls.saveAnswer(current.callId, answer.description)
                    val now = System.currentTimeMillis()
                    calls.transition(current.callId, CallStates.ACCEPTED, answeredAt = now)
                    _state.update { it.copy(phase = Phase.CONNECTING, answeredAt = now, error = null) }
                    applySpeaker(current.speaker)
                } catch (error: Throwable) {
                    _state.update { it.copy(phase = Phase.ENDED, error = error.message ?: "failed") }
                    runCatching { calls.transition(current.callId, CallStates.FAILED, endedAt = System.currentTimeMillis()) }
                    releaseMediaLocked()
                }
            }
        }
    }

    fun decline() {
        val id = _state.value.callId
        ringtone.stop()
        scope.launch {
            if (id.isNotBlank()) {
                runCatching { calls.transition(id, CallStates.DECLINED, endedAt = System.currentTimeMillis()) }
            }
            finish()
        }
    }

    fun end() {
        val snapshot = _state.value
        ringtone.stop()
        scope.launch {
            if (snapshot.callId.isNotBlank()) {
                val next = if (snapshot.phase == Phase.CONNECTED || snapshot.phase == Phase.CONNECTING) {
                    CallStates.ENDED
                } else {
                    CallStates.MISSED
                }
                runCatching { calls.transition(snapshot.callId, next, endedAt = System.currentTimeMillis()) }
            }
            finish()
        }
    }

    fun toggleMute() {
        val enabled = !_state.value.muted
        audioTrack?.setEnabled(!enabled)
        _state.update { it.copy(muted = enabled) }
    }

    fun toggleSpeaker() {
        val speaker = !_state.value.speaker
        applySpeaker(speaker)
        _state.update { it.copy(speaker = speaker) }
    }

    fun toggleCamera() {
        val enabled = !_state.value.cameraEnabled
        localVideoTrack?.setEnabled(!enabled)
        _state.update { it.copy(cameraEnabled = !enabled) }
    }

    fun switchCamera() {
        val camera = capturer as? CameraVideoCapturer ?: return
        camera.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                usingFrontCamera = isFront
                localRenderer?.setMirror(isFront)
            }

            override fun onCameraSwitchError(error: String?) = Unit
        })
    }

    fun attachLocal(renderer: SurfaceViewRenderer) {
        if (localRenderer === renderer) return
        localRenderer?.let { localVideoTrack?.removeSink(it) }
        localRenderer = renderer
        renderer.setMirror(usingFrontCamera)
        localVideoTrack?.addSink(renderer)
    }

    fun detachLocal(renderer: SurfaceViewRenderer) {
        localVideoTrack?.removeSink(renderer)
        if (localRenderer === renderer) localRenderer = null
    }

    fun attachRemote(renderer: SurfaceViewRenderer) {
        if (remoteRenderer === renderer) return
        remoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
        remoteRenderer = renderer
        renderer.setMirror(false)
        remoteVideoTrack?.addSink(renderer)
    }

    fun detachRemote(renderer: SurfaceViewRenderer) {
        remoteVideoTrack?.removeSink(renderer)
        if (remoteRenderer === renderer) remoteRenderer = null
    }

    fun restore() {
        if (!config.configured) return
        val id = prefs.getString(KEY_ID, null) ?: return
        scope.launch {
            val record = runCatching { calls.get(id) }.getOrNull()
            if (record == null || CallStates.isTerminal(record.state)) {
                forget()
                return@launch
            }
            val me = calls.currentUid()
            if (record.state == CallStates.ACCEPTED) {
                runCatching { calls.transition(id, CallStates.FAILED, endedAt = System.currentTimeMillis()) }
                forget()
                return@launch
            }
            if (record.state == CallStates.RINGING && record.calleeId == me) {
                onIncoming(id, record.callerId, record.callerName, record.callerPhoto, record.type == CallRecord.TYPE_VIDEO)
                context.startActivity(
                    Intent(context, IncomingCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("callId", id)
                    },
                )
            } else if (record.state == CallStates.RINGING && record.callerId == me) {
                runCatching { calls.transition(id, CallStates.FAILED, endedAt = System.currentTimeMillis()) }
                forget()
            }
        }
    }

    private fun listen(callId: String, caller: Boolean) {
        listenJob?.cancel()
        listenJob = scope.launch {
            launch {
                calls.observe(callId).collect { record ->
                    if (record == null) return@collect
                    latest = record
                    if (caller && record.answer.isNotBlank() && !remoteDescriptionSet) {
                        runCatching {
                            setRemote(SessionDescription(SessionDescription.Type.ANSWER, record.answer))
                            _state.update {
                                it.copy(
                                    phase = if (it.phase == Phase.CONNECTED) Phase.CONNECTED else Phase.CONNECTING,
                                    answeredAt = record.answeredAt.takeIf { value -> value > 0 } ?: System.currentTimeMillis(),
                                )
                            }
                        }.onFailure { error ->
                            _state.update { it.copy(phase = Phase.ENDED, error = error.message) }
                        }
                    }
                    if (CallStates.isTerminal(record.state)) {
                        ringtone.stop()
                        _state.update { it.copy(phase = Phase.ENDED, error = if (record.state == CallStates.FAILED) "network" else it.error) }
                        delay(900)
                        finish()
                    }
                }
            }
            launch {
                calls.observeCandidates(callId).collect { candidates ->
                    val me = calls.currentUid()
                    candidates.filter { it.from != me && seenCandidates.add(it.id) }.forEach { dto ->
                        val ice = IceCandidate(dto.sdpMid, dto.sdpMLineIndex, dto.sdp)
                        if (remoteDescriptionSet) peer?.addIceCandidate(ice) else pendingIce += ice
                    }
                }
            }
        }
    }

    private fun armTimeout(callId: String) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(45_000)
            val current = _state.value
            if (current.callId == callId && (current.phase == Phase.RINGING || current.phase == Phase.OUTGOING)) {
                ringtone.stop()
                runCatching { calls.transition(callId, CallStates.MISSED, endedAt = System.currentTimeMillis()) }
                _state.update { it.copy(phase = Phase.ENDED, error = "timeout") }
                delay(700)
                finish()
            }
        }
    }

    private fun ensureFactory() {
        if (factory != null && eglBase != null) return
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        val egl = EglBase.create()
        eglBase = egl
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeer(video: Boolean) {
        val connection = factory?.createPeerConnection(rtcConfig(), observer)
            ?: error("peer-connection")
        peer = connection
        val constraints = MediaConstraints()
        audioSource = factory?.createAudioSource(constraints)
        audioTrack = factory?.createAudioTrack("audio0", audioSource)
        audioTrack?.setEnabled(true)
        connection.addTrack(audioTrack, listOf("blaze"))
        if (video) startCapture()
        remoteDescriptionSet = false
        pendingIce.clear()
    }

    private fun startCapture() {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return
        val camera = enumerator.createCapturer(name, null)
        capturer = camera
        val helper = SurfaceTextureHelper.create("BlazeCapture", eglBase!!.eglBaseContext)
        captureHelper = helper
        val source = factory!!.createVideoSource(camera.isScreencast)
        camera.initialize(helper, context, source.capturerObserver)
        camera.startCapture(1280, 720, 30)
        videoSource = source
        val track = factory!!.createVideoTrack("video0", source)
        track.setEnabled(true)
        localVideoTrack = track
        localRenderer?.let { track.addSink(it) }
        peer?.addTrack(track, listOf("blaze"))
        usingFrontCamera = true
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val servers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        if (config.turnConfigured) {
            servers += PeerConnection.IceServer.builder(config.turnUrl)
                .setUsername(config.turnUsername)
                .setPassword(config.turnPassword)
                .createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED,
                -> _state.update { it.copy(phase = Phase.CONNECTED, error = null) }
                PeerConnection.IceConnectionState.FAILED -> {
                    val id = _state.value.callId
                    scope.launch {
                        if (id.isNotBlank()) {
                            runCatching { calls.transition(id, CallStates.FAILED, endedAt = System.currentTimeMillis()) }
                        }
                    }
                    _state.update { it.copy(phase = Phase.ENDED, error = "network") }
                }
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            val id = _state.value.callId
            val uid = calls.currentUid() ?: return
            if (id.isBlank()) return
            scope.launch(Dispatchers.IO) {
                runCatching {
                    calls.addCandidate(
                        id,
                        IceCandidateDto(
                            from = uid,
                            sdp = candidate.sdp,
                            sdpMid = candidate.sdpMid.orEmpty(),
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        ),
                    )
                }
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
            val track = receiver.track()
            if (track is VideoTrack) {
                remoteVideoTrack = track
                remoteRenderer?.let { track.addSink(it) }
            }
        }
    }

    private suspend fun createOffer(): SessionDescription = awaitSdp { peer?.createOffer(it, mediaConstraints()) }
    private suspend fun createAnswer(): SessionDescription = awaitSdp { peer?.createAnswer(it, mediaConstraints()) }

    private suspend fun setLocal(description: SessionDescription) = awaitSet { peer?.setLocalDescription(it, description) }
    private suspend fun setRemote(description: SessionDescription) {
        awaitSet { peer?.setRemoteDescription(it, description) }
        remoteDescriptionSet = true
        pendingIce.forEach { peer?.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun mediaConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private suspend fun awaitSdp(start: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            start(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onCreateFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "sdp"))
                }
                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            })
        }

    private suspend fun awaitSet(start: (SdpObserver) -> Unit) {
        suspendCancellableCoroutine { continuation ->
            start(object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onCreateFailure(error: String?) = Unit
                override fun onSetSuccess() {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onSetFailure(error: String?) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error ?: "sdp-set"))
                }
            })
        }
    }

    private fun applySpeaker(speaker: Boolean) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = speaker
    }

    private suspend fun finish() {
        mutex.withLock {
            if (_state.value.phase == Phase.IDLE && peer == null) return
            timeoutJob?.cancel()
            val job = listenJob
            listenJob = null
            ringtone.stop()
            releaseMediaLocked()
            forget()
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            _state.value = Ui()
            latest = null
            seenCandidates.clear()
            if (job != null && job != coroutineContext[Job]) job.cancel()
        }
    }

    private fun releaseMediaLocked() {
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        remoteVideoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { captureHelper?.dispose() }
        captureHelper = null
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        remoteDescriptionSet = false
        pendingIce.clear()
    }

    private fun remember(callId: String) {
        prefs.edit().putString(KEY_ID, callId).apply()
    }

    private fun forget() {
        prefs.edit().remove(KEY_ID).apply()
    }

    companion object {
        private const val KEY_ID = "active_call_id"
    }
}
