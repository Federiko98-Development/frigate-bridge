package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.camera.CameraFeature
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.network.NetworkMonitor
import it.federicoraponi.frigatebridge.network.connections
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import it.federicoraponi.frigatebridge.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Live view and controls of one camera. */
class CameraViewModel(
    args: CameraArgs,
    private val registry: ServerRegistry,
    private val network: NetworkMonitor,
    private val settings: SettingsStore,
) : ViewModel() {
    private val camera = args.camera
    private var clipJob: Job? = null
    private var clipLimitJob: Job? = null

    // The event of the recording in progress.
    private var clipEvent: String? = null

    // The screen left the foreground before the server had started the recording.
    private var stopWhenStarted = false
    private var liveJob: Job? = null
    private var httpsJob: Job? = null
    private var httpsFailure: VideoFailure? = null

    // One command at a time and in order, so that a STOP never overtakes its movement.
    private val ptzCommands = Channel<String>(Channel.UNLIMITED)
    private var ptzUsed = false

    // A movement was asked for and no STOP has reached the server since.
    private var stopPending = false
    private val mutableState = MutableStateFlow(CameraState(title = camera.cameraName, mode = args.mode))
    val state: StateFlow<CameraState> = mutableState.asStateFlow()

    init {
        loadCamera()
        viewModelScope.launch {
            network.status.collect { status -> mutableState.update { it.copy(isOffline = !status.isOnline) } }
        }
        viewModelScope.launch {
            // The first connection is the one the camera is loaded on anyway.
            network.status
                .connections()
                .drop(1)
                .collect { reconnect() }
        }
        viewModelScope.launch {
            for (command in ptzCommands) {
                try {
                    session()?.sendPtz(camera.cameraName, command)
                    if (command == STOP) stopPending = false
                } catch (error: FrigateError) {
                    mutableState.update { it.copy(controls = it.controls.copy(failure = error.toVideoFailure())) }
                }
            }
        }
    }

    fun setFeature(
        feature: CameraFeature,
        enabled: Boolean,
    ) {
        val controls = mutableState.value.controls
        if (!controls.isAllowed || controls.isApplying || feature !in controls.switchable) return
        val wanted = if (enabled) controls.enabled + feature else controls.enabled - feature
        // Shown at once; the server's answer then replaces it.
        mutableState.update {
            it.copy(controls = controls.copy(enabled = wanted, isApplying = true, failure = null))
        }
        viewModelScope.launch {
            val session = session() ?: return@launch
            var failure: VideoFailure? = null
            try {
                session.setCameraFeature(camera.cameraName, feature, enabled)
            } catch (error: FrigateError) {
                failure = error.toVideoFailure()
            }
            val snapshot = (session.state.value as? ConnectionState.Connected)?.snapshot
            val current = snapshot?.catalog?.cameras?.firstOrNull { it.ref == camera }
            mutableState.update {
                val read = current?.controls(snapshot.catalog.canChangeSettings)
                val shown = if (failure != null) controls else read ?: it.controls
                val records = current?.isRecording ?: false
                it.copy(
                    controls = shown.copy(isApplying = false, failure = failure),
                    canRecordClip = shown.isAllowed && it.canRecord && records,
                )
            }
        }
    }

    /**
     * Leaves the camera with its live view alone ([on]): nothing is recorded and nothing detected. Switched back
     * off, what was on before comes back. Like every switch, it lasts until Frigate restarts.
     */
    fun setLiveOnly(on: Boolean) {
        val controls = mutableState.value.controls
        val clip = mutableState.value.clip
        // Turning recording off would cut a clip being recorded.
        if (!controls.isAllowed || controls.isApplying) return
        if (clip == ClipState.Starting || clip is ClipState.Recording) return
        mutableState.update { it.copy(controls = controls.copy(isLiveOnly = on, isApplying = true, failure = null)) }
        viewModelScope.launch {
            val session = session() ?: return@launch
            val key = "${camera.serverId}/${camera.cameraName}"
            var failure: VideoFailure? = null
            try {
                val current = cameraIn(session)
                if (current != null) {
                    if (on) {
                        // Detection goes before motion: Frigate keeps motion on while it detects.
                        val running =
                            listOfNotNull(
                                RECORDING.takeIf { current.isRecording },
                                SNAPSHOTS.takeIf { current.isSavingSnapshots },
                                DETECTION.takeIf { current.isDetecting },
                                MOTION.takeIf { current.isDetectingMotion },
                                AUDIO.takeIf { current.isDetectingAudio },
                            )
                        // Saved first: a failure half way must not lose what to switch back on.
                        if (running.isNotEmpty()) {
                            settings.update {
                                it.copy(liveOnlyRestore = it.liveOnlyRestore + (key to running.toSet()))
                            }
                        }
                        session.setCameraFeatures(camera.cameraName, running.map { it to false })
                    } else {
                        // Without a note of what was on, e.g. switched off elsewhere, the usual functions come back.
                        val before =
                            settings.settings.first().liveOnlyRestore[key] ?: setOf(MOTION, DETECTION, RECORDING)
                        val wanted =
                            listOf(MOTION, DETECTION, RECORDING, SNAPSHOTS, AUDIO)
                                .filter { it in before && (it != RECORDING || current.canRecord) }
                        session.setCameraFeatures(camera.cameraName, wanted.map { it to true })
                        settings.update { it.copy(liveOnlyRestore = it.liveOnlyRestore - key) }
                    }
                }
            } catch (error: FrigateError) {
                failure = error.toVideoFailure()
            }
            showControls(session, failure, fallback = controls)
        }
    }

    private fun cameraIn(session: ServerSession) =
        (session.state.value as? ConnectionState.Connected)
            ?.snapshot
            ?.catalog
            ?.cameras
            ?.firstOrNull { it.ref == camera }

    /** Shows the switches as the server has them after a change. */
    private fun showControls(
        session: ServerSession,
        failure: VideoFailure?,
        fallback: CameraControls,
    ) {
        val isAllowed =
            (session.state.value as? ConnectionState.Connected)?.snapshot?.catalog?.canChangeSettings == true
        val current = cameraIn(session)
        mutableState.update {
            val shown = current?.controls(isAllowed) ?: fallback
            it.copy(
                controls = shown.copy(isApplying = false, failure = failure),
                canRecordClip = shown.isAllowed && it.canRecord && current?.isRecording == true,
            )
        }
    }

    /** Starts moving the camera; it keeps going until [stopMoving]. */
    fun startMoving(move: PtzMove) {
        ptzUsed = true
        stopPending = true
        ptzCommands.trySend(move.command)
    }

    fun stopMoving() {
        ptzCommands.trySend(STOP)
    }

    fun goToPreset(name: String) {
        ptzUsed = true
        ptzCommands.trySend("preset_$name")
    }

    /** Call it when the screen leaves the foreground: stops the camera and closes the command connection. */
    fun releaseControls() {
        if (mutableState.value.clip == ClipState.Starting) stopWhenStarted = true
        stopClip()
        if (!ptzUsed) return
        ptzUsed = false
        registry.session(camera.serverId)?.finishPtz(camera.cameraName, stop = stopPending)
        stopPending = false
    }

    /** Starts recording a clip of the camera on the server. */
    @OptIn(ExperimentalTime::class)
    fun startClip() {
        val current = mutableState.value
        val busy = current.clip != ClipState.None && current.clip !is ClipState.Failed
        if (!current.canRecordClip || busy) return
        stopWhenStarted = false
        mutableState.update { it.copy(clip = ClipState.Starting) }
        clipJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                try {
                    // Not to be interrupted: a recording the app does not remember would never be ended.
                    val event =
                        withContext(NonCancellable) {
                            val id = session.startManualRecording(camera.cameraName)
                            settings.update { it.copy(openRecordings = it.openRecordings + openKey(id)) }
                            // The screen may have closed while the server was starting.
                            if (!viewModelScope.isActive) session.endManualRecording(id) { forget(openKey(id)) }
                            id
                        }
                    ensureActive()
                    clipEvent = event
                    val now = Clock.System.now().epochSeconds
                    mutableState.update { it.copy(clip = ClipState.Recording(now)) }
                    clipLimitJob =
                        viewModelScope.launch {
                            delay(MAXIMUM_CLIP_MILLIS)
                            stopClip()
                        }
                    if (stopWhenStarted) stopClip()
                } catch (error: FrigateError) {
                    mutableState.update { it.copy(clip = ClipState.Failed(error.toVideoFailure())) }
                }
            }
    }

    /** Ends the recording and prepares its file. */
    fun stopClip() {
        val event = clipEvent ?: return
        val session = registry.session(camera.serverId) ?: return
        val startedAt = (mutableState.value.clip as? ClipState.Recording)?.startedAt ?: return
        clipEvent = null
        clipLimitJob?.cancel()
        // Sent at once and apart from this screen, which may be closing.
        val ending = session.endManualRecording(event) { forget(openKey(event)) }
        mutableState.update { it.copy(clip = ClipState.Preparing) }
        clipJob =
            viewModelScope.launch {
                val refused = ending.await()
                if (refused != null) {
                    // Kept as open: it is tried again the next time the camera is shown.
                    mutableState.update { it.copy(clip = ClipState.Failed(refused.toVideoFailure())) }
                    return@launch
                }
                val result = prepareClip(session, event, startedAt)
                mutableState.update { it.copy(clip = result) }
            }
    }

    /** Hands a finished clip over to be saved: once, also if the screen asks again. */
    fun takeClip(): ClipState.Ready? {
        val ready = mutableState.value.clip as? ClipState.Ready ?: return null
        mutableState.update { it.copy(clip = ClipState.Saving) }
        return ready
    }

    fun clipSaved() {
        mutableState.update { if (it.clip == ClipState.Saving) it.copy(clip = ClipState.None) else it }
    }

    /** The message about a clip was dismissed. */
    fun clearClip() {
        mutableState.update {
            if (it.clip is ClipState.Failed) it.copy(clip = ClipState.None) else it
        }
    }

    private suspend fun prepareClip(
        session: ServerSession,
        event: String,
        startedAt: Long,
    ): ClipState =
        try {
            delay(CLIP_SETTLE_MILLIS)
            val interval = session.eventInterval(event)
            if (interval == null) {
                ClipState.Failed(VideoFailure(detail = "The server has not closed the recording yet"))
            } else {
                when (val export = session.cutExport(camera.cameraName, interval.first, interval.last)) {
                    is ExportState.Ready -> ClipState.Ready(export.file, startedAt)
                    is ExportState.Failed -> ClipState.Failed(export.failure)
                    else -> ClipState.Failed(VideoFailure())
                }
            }
        } catch (error: FrigateError) {
            ClipState.Failed(error.toVideoFailure())
        }

    private fun openKey(event: String) = "${camera.serverId}/${camera.cameraName}/$event"

    private suspend fun forget(key: String) {
        settings.update { it.copy(openRecordings = it.openRecordings - key) }
    }

    /** Ends recordings of this server that were left open, e.g. because the app was closed during one. */
    private suspend fun endLeftOverRecordings(session: ServerSession) {
        settings.settings
            .first()
            .openRecordings
            .filter { it.startsWith("${camera.serverId}/") && it.substringAfterLast('/') != clipEvent }
            .forEach { key -> endLeftOver(session, key) }
    }

    private suspend fun endLeftOver(
        session: ServerSession,
        key: String,
    ) {
        val id = key.substringAfterLast('/')
        val refused =
            try {
                // One that has an end already must not be ended again: its end would move to now.
                if (session.eventInterval(id) == null) {
                    session.endManualRecording(id) { forget(key) }.await()
                } else {
                    forget(key)
                    null
                }
            } catch (error: FrigateError) {
                error
            }
        // A server that answers, even to refuse, has nothing more to be told. Otherwise it is tried again later.
        if (refused is FrigateError.UnexpectedResponse) forget(key)
    }

    override fun onCleared() {
        releaseControls()
    }

    private fun loadPtz(session: ServerSession) {
        viewModelScope.launch {
            val info =
                try {
                    session.ptzInfo(camera.cameraName)
                } catch (_: FrigateError) {
                    // Without it the camera simply shows no movement controls.
                    return@launch
                }
            val ptz =
                PtzControls(
                    canPanAndTilt = "pt" in info.features,
                    canZoom = "zoom" in info.features,
                    presets = info.presets,
                )
            if (ptz.canPanAndTilt || ptz.canZoom || ptz.presets.isNotEmpty()) {
                mutableState.update { it.copy(ptz = ptz) }
            }
        }
    }

    fun selectMode(mode: VideoMode) {
        if (mode == mutableState.value.mode) return
        mutableState.update { it.copy(mode = mode) }
        // The recordings have their own ViewModel.
        if (mode == VideoMode.Live) startLive() else stopLive()
    }

    fun selectStream(name: String) {
        mutableState.update { it.copy(selectedStream = name) }
        startLive()
    }

    /** Negotiates a connection for the selected stream through [peer]. */
    fun connectLive(peer: LivePeer) {
        val stream = mutableState.value.selectedStream ?: return
        liveJob?.cancel()
        mutableState.update { it.copy(live = LiveState.Connecting) }
        liveJob =
            viewModelScope.launch {
                try {
                    val session = session() ?: return@launch
                    val answer = session.webRtcAnswer(stream, peer.createOffer())
                    peer.acceptAnswer(answer)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: FrigateError) {
                    liveFailed(error.toVideoFailure())
                } catch (error: Exception) {
                    // The platform's WebRTC library refused the offer or the answer.
                    liveFailed(VideoFailure(detail = error.message))
                }
            }
    }

    /** Call it before closing the peer given to [connectLive]. */
    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    /** The first frame arrived. */
    fun livePlaying() {
        mutableState.update { it.copy(live = LiveState.Playing) }
    }

    /** The connection could not be established, or was lost. If the HTTPS stream fails, WebRTC is tried. */
    fun liveFailed(failure: VideoFailure = VideoFailure()) {
        val current = mutableState.value
        // A first explanation is more useful than the "connection failed" that may follow it.
        if (current.live is LiveState.Failed) return
        if (current.transport == LiveTransport.Https && current.selectedStream != null) {
            httpsFailure = failure
            useWebRtc()
        } else {
            // The reason the first choice failed usually explains more.
            val shown = httpsFailure?.takeIf { failure == VideoFailure() } ?: failure
            mutableState.update { it.copy(live = LiveState.Failed(shown)) }
        }
    }

    fun retry() {
        val current = mutableState.value
        when {
            current.streams.isEmpty() -> loadCamera()
            current.mode == VideoMode.Live -> startLive()
        }
    }

    /** The live view does not survive a change of network, and the server may answer on another address. */
    private suspend fun reconnect() {
        val current = mutableState.value
        if (current.mode != VideoMode.Live && current.streams.isNotEmpty()) return
        session()?.refresh()
        retry()
    }

    private fun startLive() {
        val stream = mutableState.value.selectedStream ?: return
        stopLive()
        httpsJob?.cancel()
        httpsFailure = null
        mutableState.update {
            it.copy(
                transport = LiveTransport.Https,
                httpsStream = null,
                live = LiveState.Connecting,
                liveAttempt = it.liveAttempt + 1,
            )
        }
        httpsJob =
            viewModelScope.launch {
                try {
                    val session = session() ?: return@launch
                    val source = session.httpsLiveStream(stream)
                    mutableState.update { it.copy(httpsStream = source) }
                } catch (error: FrigateError) {
                    // WebRTC asks the same server, so it would fail the same way.
                    mutableState.update { it.copy(live = LiveState.Failed(error.toVideoFailure())) }
                }
            }
    }

    private fun useWebRtc() {
        httpsJob?.cancel()
        mutableState.update {
            it.copy(
                transport = LiveTransport.WebRtc,
                httpsStream = null,
                live = LiveState.Connecting,
                liveAttempt = it.liveAttempt + 1,
            )
        }
    }

    private fun loadCamera() {
        viewModelScope.launch {
            registry.load()
            val session = session() ?: return@launch
            val connection = session.state.value as? ConnectionState.Connected ?: session.refresh()
            when (connection) {
                is ConnectionState.Connected -> {
                    val found =
                        connection.snapshot.catalog.cameras
                            .firstOrNull { it.ref == camera }
                    mutableState.update {
                        if (found == null) {
                            it.copy(isGone = true)
                        } else {
                            it.copy(
                                title = found.displayName,
                                streams = found.liveStreams,
                                selectedStream = found.liveStreams.firstOrNull()?.name,
                                controls = found.controls(connection.snapshot.catalog.canChangeSettings),
                                canRecord = found.canRecord,
                                canRecordClip =
                                    connection.snapshot.catalog.canChangeSettings &&
                                        found.canRecord &&
                                        found.isRecording,
                            )
                        }
                    }
                    if (found != null) {
                        viewModelScope.launch { endLeftOverRecordings(session) }
                        if (found.hasPtz) loadPtz(session)
                        if (mutableState.value.mode == VideoMode.Live) startLive()
                    }
                }
                is ConnectionState.Failed -> liveFailed(connection.error.toVideoFailure())
                ConnectionState.Idle, ConnectionState.Connecting -> Unit
            }
        }
    }

    private fun session(): ServerSession? {
        val session = registry.session(camera.serverId)
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }
}

private const val STOP = "STOP"

// Frigate's names of the functions switched for the live view alone.
private val DETECTION = CameraFeature.Detection.apiName
private val RECORDING = CameraFeature.Recording.apiName
private val SNAPSHOTS = CameraFeature.Snapshots.apiName
private val MOTION = CameraFeature.Motion.apiName
private const val AUDIO = "audio"
