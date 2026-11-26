package it.federicoraponi.frigatebridge.video

import android.util.Log
import it.federicoraponi.frigatebridge.ui.LivePeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val GATHERING_TIMEOUT_MILLIS = 1_000L
private const val TAG = "FrigateLive"

/**
 * A receive-only connection to one go2rtc stream.
 *
 * No STUN server is used, so that nothing is sent to third parties: the phone connects to the addresses that
 * the server offers, which covers the home network and a VPN.
 *
 * Use it from the main thread and call [close] when the video leaves the screen.
 */
class WebRtcPeer(
    engine: WebRtcEngine,
    private val video: VideoSink,
    private val onConnectionLost: () -> Unit,
) : LivePeer,
    AutoCloseable {
    private val gathered = CompletableDeferred<Unit>()
    private var audioTrack: AudioTrack? = null
    private var closed = false

    var muted = false
        set(value) {
            field = value
            if (!closed) audioTrack?.setEnabled(!value)
        }

    private val connection: PeerConnection =
        checkNotNull(
            engine.factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                Observer(),
            ),
        ) { "WebRTC could not create a connection" }

    init {
        val receiveOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receiveOnly)
        connection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, receiveOnly)
    }

    override suspend fun createOffer(): String {
        ensureOpen()
        val offer =
            suspendCancellableCoroutine { continuation ->
                connection.createOffer(
                    object : SdpResult({ continuation.resumeWithException(IllegalStateException(it)) }) {
                        override fun onCreateSuccess(description: SessionDescription) {
                            continuation.resume(description)
                        }
                    },
                    MediaConstraints(),
                )
            }
        ensureOpen()
        setDescription { connection.setLocalDescription(it, offer) }
        // Host addresses are found at once; do not wait longer for anything else.
        withTimeoutOrNull(GATHERING_TIMEOUT_MILLIS) { gathered.await() }
        ensureOpen()
        return connection.localDescription.description
    }

    override suspend fun acceptAnswer(sdp: String) {
        ensureOpen()
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        setDescription { connection.setRemoteDescription(it, answer) }
    }

    /** The delay of the network path in use, once connected. */
    fun roundTripMillis(onResult: (Int) -> Unit) {
        if (closed) return
        connection.getStats { report ->
            report.statsMap.values
                .firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true }
                ?.let { (it.members["currentRoundTripTime"] as? Double) }
                ?.let { onResult((it * 1_000).toInt()) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        connection.dispose()
    }

    private fun ensureOpen() {
        if (closed) throw CancellationException("The connection was closed")
    }

    private suspend fun setDescription(apply: (SdpObserver) -> Unit) {
        suspendCancellableCoroutine { continuation ->
            apply(
                object : SdpResult({ continuation.resumeWithException(IllegalStateException(it)) }) {
                    override fun onSetSuccess() {
                        continuation.resume(Unit)
                    }
                },
            )
        }
    }

    private open class SdpResult(
        private val onFailure: (String?) -> Unit,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) = onFailure(error)

        override fun onSetFailure(error: String?) = onFailure(error)
    }

    // Called on WebRTC's own thread.
    private inner class Observer : PeerConnection.Observer {
        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> track.addSink(video)
                is AudioTrack -> {
                    audioTrack = track
                    track.setEnabled(!muted)
                }
            }
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d(TAG, "connection: $newState")
            if (newState == PeerConnection.PeerConnectionState.FAILED) onConnectionLost()
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceCandidate(candidate: IceCandidate) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) = Unit

        override fun onRenegotiationNeeded() = Unit
    }
}
