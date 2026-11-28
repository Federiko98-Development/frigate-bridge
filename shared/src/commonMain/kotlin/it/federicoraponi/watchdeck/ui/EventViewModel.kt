package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.network.connections
import it.federicoraponi.watchdeck.recordings.ReviewItem
import it.federicoraponi.watchdeck.recordings.savedExportName
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    // The video already failed once and was asked for again.
    private var hasRetried = false
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

    fun retry() {
        hasRetried = false
        load()
    }

    /** The player gave up: the video is asked for once more, which also renews an ended sign-in. */
    fun playerFailed(failure: VideoFailure) {
        if (!hasRetried) {
            hasRetried = true
            load()
        } else {
            mutableState.update { it.copy(playback = PlaybackState.Failed(failure)) }
        }
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

    private fun session(): ServerSession? {
        val session = registry.session(args.serverId)
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }
}
