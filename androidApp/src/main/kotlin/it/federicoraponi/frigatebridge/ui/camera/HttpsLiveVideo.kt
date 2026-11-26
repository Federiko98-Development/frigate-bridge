package it.federicoraponi.frigatebridge.ui.camera

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import it.federicoraponi.frigatebridge.server.HttpsLiveStream
import it.federicoraponi.frigatebridge.server.acceptingCertificate
import it.federicoraponi.frigatebridge.video.LiveRenderersFactory
import it.federicoraponi.frigatebridge.video.WebSocketDataSource
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

private const val TAG = "FrigateLive"
private const val FIRST_FRAME_TIMEOUT_MILLIS = 15_000L
private const val CHECK_INTERVAL_MILLIS = 500L

// A picture that does not move for this long is reconnected, a few times at most.
private const val STALL_TIMEOUT_MILLIS = 8_000L
private const val MAX_RECONNECTIONS = 3

// The more video is waiting in the buffer, the further behind the picture is: play faster until it shrinks.
// Below the first limit nothing is sped up: the stream arrives in bursts, and emptying the buffer makes it stop.
private const val TARGET_BUFFERED_MILLIS = 2_000L
private const val FAR_BEHIND_MILLIS = 5_000L
private const val CATCH_UP_SPEED = 1.05f
private const val FAST_CATCH_UP_SPEED = 1.3f

/**
 * A live stream carried over HTTPS. Like the WebRTC view, it runs only while on screen and in the foreground.
 * A stream that stops after the picture appeared is reconnected; one that never shows a picture fails.
 */
@OptIn(UnstableApi::class)
@Composable
fun HttpsLiveVideo(
    source: HttpsLiveStream,
    connectionKey: Any,
    muted: Boolean,
    onPlaying: () -> Unit,
    onFailed: (String) -> Unit,
    onMeasures: (LiveMeasures) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(connectionKey, source) {
        var reconnections by remember { mutableIntStateOf(0) }
        key(reconnections) {
            val context = LocalContext.current
            var player by remember { mutableStateOf<ExoPlayer?>(null) }
            var rendered by remember { mutableStateOf(false) }
            val playing by rememberUpdatedState(onPlaying)
            val failed by rememberUpdatedState(onFailed)
            val report by rememberUpdatedState(onMeasures)

            fun lost(reason: String) {
                Log.w(TAG, "https: $reason")
                // A stream that worked is worth connecting to again.
                if ((rendered || reconnections > 0) && reconnections < MAX_RECONNECTIONS) {
                    reconnections++
                } else {
                    failed(reason)
                }
            }

            LifecycleStartEffect(source) {
                val started = SystemClock.elapsedRealtime()
                var measures = LiveMeasures()
                val http = OkHttpClient.Builder().acceptingCertificate(source.certificateSha256).build()
                val media =
                    ProgressiveMediaSource
                        .Factory(WebSocketDataSource.Factory(http, source.headers))
                        // Retrying would ask to resume from where it stopped, which a live stream cannot do.
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                        .createMediaSource(MediaItem.fromUri(source.url))
                // Small buffers: this is a live view.
                val buffers = DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 8_000, 500, 1_000).build()
                val current =
                    ExoPlayer
                        .Builder(context, LiveRenderersFactory(context))
                        .setLoadControl(buffers)
                        .build()
                current.addListener(
                    object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            if (tracks.isEmpty) return
                            measures = measures.copy(hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO))
                            report(measures)
                        }

                        override fun onRenderedFirstFrame() {
                            // It happens again whenever the picture moves to another surface.
                            if (rendered) return
                            rendered = true
                            val elapsed = (SystemClock.elapsedRealtime() - started).toInt()
                            Log.d(TAG, "https: first frame after $elapsed ms")
                            measures = measures.copy(firstFrameMillis = elapsed)
                            report(measures)
                            playing()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            val buffered = player?.totalBufferedDuration
                            Log.d(TAG, "https: playing=$isPlaying audio=${measures.hasAudio} buffered=$buffered")
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            lost(listOfNotNull(error.errorCodeName, error.cause?.message).joinToString(": "))
                        }
                    },
                )
                current.setMediaSource(media)
                current.playWhenReady = true
                current.prepare()
                player = current
                onStopOrDispose {
                    player = null
                    rendered = false
                    current.release()
                }
            }
            // An early return out of `key` is not allowed.
            player?.let { current ->
                LaunchedEffect(current, muted) { current.volume = if (muted) 0f else 1f }
                LaunchedEffect(current) {
                    delay(FIRST_FRAME_TIMEOUT_MILLIS)
                    if (!rendered) lost("No picture after ${FIRST_FRAME_TIMEOUT_MILLIS / 1_000} seconds")
                }
                LaunchedEffect(current) {
                    var position = 0L
                    var movedAt = SystemClock.elapsedRealtime()
                    while (true) {
                        delay(CHECK_INTERVAL_MILLIS)
                        val now = SystemClock.elapsedRealtime()
                        if (current.currentPosition != position) {
                            position = current.currentPosition
                            movedAt = now
                        } else if (rendered && now - movedAt > STALL_TIMEOUT_MILLIS) {
                            lost("The picture stopped")
                            break
                        }
                        val buffered = current.totalBufferedDuration
                        current.setPlaybackSpeed(
                            when {
                                buffered > FAR_BEHIND_MILLIS -> FAST_CATCH_UP_SPEED
                                buffered > TARGET_BUFFERED_MILLIS -> CATCH_UP_SPEED
                                else -> 1f
                            },
                        )
                    }
                }
                ContentFrame(player = current, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = modifier)
            }
        }
    }
}
