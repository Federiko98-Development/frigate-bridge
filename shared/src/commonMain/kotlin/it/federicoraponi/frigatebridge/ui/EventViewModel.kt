package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.network.NetworkMonitor
import it.federicoraponi.frigatebridge.network.connections
import it.federicoraponi.frigatebridge.recordings.ReviewItem
import it.federicoraponi.frigatebridge.recordings.savedExportName
import it.federicoraponi.frigatebridge.server.ConnectionState
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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
    /** The phone has no network. */
    val isOffline: Boolean = false,
    /** Whether the moment is kept on the server; `null` for who may not keep moments. */
    val keeping: Keeping? = null,
)

/** Whether a copy of a review moment is kept on the server. */
sealed interface Keeping {
    data object NotKept : Keeping

    data object Working : Keeping

    data object Kept : Keeping

    data class Failed(
        val failure: VideoFailure,
    ) : Keeping
}

/** One review moment: what was seen, and its video. */
class EventViewModel(
    private val args: EventArgs,
    private val registry: ServerRegistry,
    private val network: NetworkMonitor,
) : ViewModel() {
    private var loadJob: Job? = null
    private var exportJob: Job? = null
    private var seeks = 0
    private val mutableState = MutableStateFlow(EventState())
    val state: StateFlow<EventState> = mutableState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            network.status.collect { status -> mutableState.update { it.copy(isOffline = !status.isOnline) } }
        }
        viewModelScope.launch {
            // On a new network the video is asked for again, perhaps on the server's other address.
            network.status.connections().drop(1).collect {
                registry.session(args.serverId)?.refresh()
                load()
            }
        }
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
                    val cameraName = session.cameraNames[item.camera.cameraName] ?: item.camera.cameraName
                    mutableState.update { it.copy(item = item, cameraName = cameraName) }
                    markViewed(session, item)
                    if (connection?.snapshot?.catalog?.canChangeSettings == true) readKeeping(session, item)
                    val range = clipRange(item)
                    val clip = session.recordingClip(item.camera.cameraName, range.first, range.last)
                    val seek = SeekRequest(0, ++seeks)
                    mutableState.update { it.copy(playback = PlaybackState.Ready(clip, seek)) }
                } catch (error: FrigateError) {
                    mutableState.update { it.copy(playback = PlaybackState.Failed(error.toVideoFailure())) }
                }
            }
    }

    /**
     * Keeps the moment: a copy of its video, which stays until deleted, and its objects marked as ones to keep.
     * [title] names the copy.
     */
    fun keep(title: String) {
        val item = mutableState.value.item ?: return
        val keeping = mutableState.value.keeping
        if (keeping != Keeping.NotKept && keeping !is Keeping.Failed) return
        mutableState.update { it.copy(keeping = Keeping.Working) }
        viewModelScope.launch {
            val session = session() ?: return@launch
            val range = clipRange(item)
            val name = savedExportName(title, item.id)
            val result =
                try {
                    session.setRetained(item, retained = true)
                    when (val export = session.cutExport(item.camera.cameraName, range.first, range.last, name)) {
                        is ExportState.Failed -> Keeping.Failed(export.failure)
                        else -> Keeping.Kept
                    }
                } catch (error: FrigateError) {
                    Keeping.Failed(error.toVideoFailure())
                }
            mutableState.update { it.copy(keeping = result) }
        }
    }

    private fun readKeeping(
        session: ServerSession,
        item: ReviewItem,
    ) {
        viewModelScope.launch {
            try {
                val kept = session.exports().any { it.savedReviewId == item.id }
                mutableState.update { it.copy(keeping = if (kept) Keeping.Kept else Keeping.NotKept) }
            } catch (_: FrigateError) {
                // Without the list nothing is offered, so that no second copy is made by mistake.
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
