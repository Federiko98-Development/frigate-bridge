package it.federicoraponi.frigatebridge.ui.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import it.federicoraponi.frigatebridge.ui.LivePeer
import it.federicoraponi.frigatebridge.ui.capture.ProvidesFrames
import it.federicoraponi.frigatebridge.video.WebRtcEngine
import it.federicoraponi.frigatebridge.video.WebRtcPeer
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import org.webrtc.EglRenderer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.coroutines.resume

private const val STATS_INTERVAL_MILLIS = 2_000L
private const val FRAME_TIMEOUT_MILLIS = 2_000L

// Short: on a network where WebRTC works the picture arrives within a second or two.
private const val FIRST_FRAME_TIMEOUT_MILLIS = 6_000L

/** Measurements shown under the video, to judge the delay of the live view. */
data class LiveMeasures(
    val firstFrameMillis: Int? = null,
    val roundTripMillis: Int? = null,
    /** `false` when the stream turned out to carry no sound the phone can play; `null` while unknown. */
    val hasAudio: Boolean? = null,
)

/**
 * The WebRTC picture of one stream. A connection exists only while this is on screen and the app is in the
 * foreground.
 */
@Composable
fun LiveVideo(
    connectionKey: Any,
    muted: Boolean,
    onConnect: (LivePeer) -> Unit,
    onDisconnect: () -> Unit,
    onPlaying: () -> Unit,
    onLost: () -> Unit,
    onMeasures: (LiveMeasures) -> Unit,
    modifier: Modifier = Modifier,
    engine: WebRtcEngine = koinInject(),
) {
    key(connectionKey) {
        val context = LocalContext.current
        val renderer = remember { SurfaceViewRenderer(context) }
        var peer by remember { mutableStateOf<WebRtcPeer?>(null) }
        var measures by remember { mutableStateOf(LiveMeasures()) }
        val playing by rememberUpdatedState(onPlaying)
        val lost by rememberUpdatedState(onLost)
        val report by rememberUpdatedState(onMeasures)
        val isMuted by rememberUpdatedState(muted)

        LifecycleStartEffect(renderer) {
            val main = Handler(Looper.getMainLooper())
            val started = SystemClock.elapsedRealtime()
            var active = true
            renderer.init(
                engine.egl.eglBaseContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        val elapsed = (SystemClock.elapsedRealtime() - started).toInt()
                        main.post {
                            // Only the first one: the surface may be recreated later.
                            if (active && measures.firstFrameMillis == null) {
                                measures = measures.copy(firstFrameMillis = elapsed)
                                playing()
                            }
                        }
                    }

                    override fun onFrameResolutionChanged(
                        width: Int,
                        height: Int,
                        rotation: Int,
                    ) = Unit
                },
            )
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            val connection = WebRtcPeer(engine, renderer, onConnectionLost = { main.post { if (active) lost() } })
            connection.muted = isMuted
            peer = connection
            onConnect(connection)
            onStopOrDispose {
                active = false
                onDisconnect()
                peer = null
                // The connection first: it stops feeding the renderer.
                connection.close()
                renderer.release()
            }
        }
        LaunchedEffect(muted, peer) { peer?.muted = muted }
        LaunchedEffect(peer) {
            val connection = peer ?: return@LaunchedEffect
            val main = Handler(Looper.getMainLooper())
            while (true) {
                delay(STATS_INTERVAL_MILLIS)
                connection.roundTripMillis { millis ->
                    main.post { measures = measures.copy(roundTripMillis = millis) }
                }
            }
        }
        LaunchedEffect(measures) { report(measures) }
        // Without a picture after a while, say so instead of spinning for ever.
        LaunchedEffect(peer) {
            if (peer == null) return@LaunchedEffect
            delay(FIRST_FRAME_TIMEOUT_MILLIS)
            if (measures.firstFrameMillis == null) lost()
        }
        AndroidView(factory = { renderer }, modifier = modifier)
        if (measures.firstFrameMillis != null) ProvidesFrames { renderer.nextFrame() }
    }
}

/** The next picture the renderer draws, at the size of the video, or `null` if none comes. */
private suspend fun SurfaceViewRenderer.nextFrame(): Bitmap? =
    withTimeoutOrNull(FRAME_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            // Called once, with the next frame.
            val listener = EglRenderer.FrameListener { continuation.resume(it) }
            addFrameListener(listener, 1f)
            continuation.invokeOnCancellation { removeFrameListener(listener) }
        }
    }
