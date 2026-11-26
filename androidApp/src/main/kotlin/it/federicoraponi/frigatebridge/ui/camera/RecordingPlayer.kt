package it.federicoraponi.frigatebridge.ui.camera

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.server.RecordingClip
import it.federicoraponi.frigatebridge.server.acceptingCertificate
import it.federicoraponi.frigatebridge.ui.designsystem.Spacing
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.text.DateFormat
import java.util.Date

private const val POSITION_INTERVAL_MILLIS = 250L
private const val SEEK_MILLIS = 10_000L

/** A player for the recorded interval, that downloads it with the server's token and accepted certificate. */
@OptIn(UnstableApi::class)
@Composable
fun rememberRecordingPlayer(
    clip: RecordingClip,
    onError: (String) -> Unit,
): ExoPlayer {
    val context = LocalContext.current
    val failed by rememberUpdatedState(onError)
    val player =
        remember(clip) {
            val http = OkHttpClient.Builder().acceptingCertificate(clip.certificateSha256).build()
            val data = OkHttpDataSource.Factory(http).setDefaultRequestProperties(clip.headers)
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(data))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(clip.url))
                    prepare()
                    playWhenReady = true
                }
        }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    failed(listOfNotNull(error.errorCodeName, error.cause?.message).joinToString(": "))
                }
            }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleStartEffect(player) { onStopOrDispose { player.pause() } }
    return player
}

@OptIn(UnstableApi::class)
@Composable
fun RecordingPicture(
    player: Player,
    modifier: Modifier = Modifier,
) {
    // A texture view moves with the screen during page transitions.
    ContentFrame(player = player, surfaceType = SURFACE_TYPE_TEXTURE_VIEW, modifier = modifier)
}

@Composable
fun RecordingControls(
    clip: RecordingClip,
    player: Player,
    modifier: Modifier = Modifier,
) {
    var progress by remember(player) { mutableStateOf(0f) }
    var playing by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            progress = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            playing = player.isPlaying
            delay(POSITION_INTERVAL_MILLIS)
        }
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(
                R.string.video_recorded_from,
                time.format(Date(clip.startTime * 1_000)),
                time.format(Date(clip.endTime * 1_000)),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { player.seekTo((player.currentPosition - SEEK_MILLIS).coerceAtLeast(0)) },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.video_back_10)) }
            FilledIconButton(
                onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        // At the end, play starts again from the beginning.
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(56.dp),
            ) {
                if (playing) {
                    Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.video_pause))
                } else {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.video_play))
                }
            }
            FilledTonalButton(
                onClick = { player.seekTo(player.currentPosition + SEEK_MILLIS) },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(R.string.video_forward_10)) }
        }
    }
}
