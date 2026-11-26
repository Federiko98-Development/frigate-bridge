package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Which review moment of which server. */
data class EventArgs(
    val serverId: String,
    val reviewId: String,
)

data class EventState(
    /** `null` until the server has answered. */
    val item: ReviewItem? = null,
    val cameraName: String = "",
    /** The video of this moment, with a little before and after it. */
    val playback: PlaybackState = PlaybackState.Loading,
    val export: ExportState = ExportState.None,
    /** The server does not exist any more: go back. */
    val isGone: Boolean = false,
)

/** One review moment: what was seen, and its video. */
class EventViewModel(
    private val args: EventArgs,
    private val registry: ServerRegistry,
) : ViewModel() {
    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var seeks = 0
    private val mutableState = MutableStateFlow(EventState())
    val state: StateFlow<EventState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    fun playerFailed(detail: String?) {
        mutableState.update { it.copy(playback = PlaybackState.Failed(VideoFailure(detail = detail))) }
    }

    /** Asks the server for a file with the video of this moment. */
    fun export() {
        val item = mutableState.value.item ?: return
        if (mutableState.value.export is ExportState.Working) return
        mutableState.update { it.copy(export = ExportState.Working) }
        exportJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                val range = clipRange(item)
                val result = session.cutExport(item.camera.cameraName, range.first, range.last)
                mutableState.update { it.copy(export = result) }
            }
    }

    /** The exported file was handed over, or the message about it dismissed. */
    fun clearExport() {
        if (mutableState.value.export !is ExportState.Working) {
            mutableState.update { it.copy(export = ExportState.None) }
        }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(playback = PlaybackState.Loading) }
        loadJob =
            viewModelScope.launch {
                registry.load()
                val session = session() ?: return@launch
                try {
                    val item = session.review(args.reviewId)
                    val connection = session.state.value as? ConnectionState.Connected
                    val camera =
                        connection
                            ?.snapshot
                            ?.catalog
                            ?.cameras
                            ?.firstOrNull { it.ref == item.camera }
                    mutableState.update {
                        it.copy(item = item, cameraName = camera?.displayName ?: item.camera.cameraName)
                    }
                    markViewed(session, item)
                    val range = clipRange(item)
                    val clip = session.recordingClip(item.camera.cameraName, range.first, range.last)
                    val seek = SeekRequest(0, ++seeks)
                    mutableState.update { it.copy(playback = PlaybackState.Ready(clip, seek)) }
                } catch (error: FrigateError) {
                    mutableState.update { it.copy(playback = PlaybackState.Failed(error.toVideoFailure())) }
                }
            }
    }

    private fun markViewed(
        session: ServerSession,
        item: ReviewItem,
    ) {
        viewModelScope.launch {
            try {
                session.markReviewed(listOf(item.id))
            } catch (_: FrigateError) {
                // It stays to be seen, which is no reason to disturb who is watching it.
            }
        }
    }

    /** The first and last second of the video of [item]; a moment still going on ends now. */
    @OptIn(ExperimentalTime::class)
    private fun clipRange(item: ReviewItem): LongRange {
        val start = item.start - PADDING_SECONDS
        val end = item.end?.let { it + PADDING_SECONDS } ?: Clock.System.now().epochSeconds
        // The server cannot put much more than an hour in one playlist.
        return start..end.coerceIn(start + 1, start + MAXIMUM_SECONDS)
    }

    private fun session(): ServerSession? {
        val session = registry.session(args.serverId)
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }
}

// As Frigate's web interface shows a review moment.
private const val PADDING_SECONDS = 4L
private const val MAXIMUM_SECONDS = 3_600L
