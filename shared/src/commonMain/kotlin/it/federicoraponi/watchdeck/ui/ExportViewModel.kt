package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.recordings.ExportItem
import it.federicoraponi.watchdeck.server.ExportedFile
import it.federicoraponi.watchdeck.server.ServerRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which export of which server. */
data class ExportArgs(
    val serverId: String,
    val exportId: String,
)

data class ExportViewState(
    /** `null` until the server has answered. */
    val item: ExportItem? = null,
    /** Where to play and download it from; `null` while it is not available. */
    val file: ExportedFile? = null,
    val failure: VideoFailure? = null,
    val isDeleting: Boolean = false,
    /** It was deleted, or the server does not exist any more: go back. */
    val isGone: Boolean = false,
)

/** One video kept on the server: playing it and deleting it. */
class ExportViewModel(
    private val args: ExportArgs,
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExportViewState())
    val state: StateFlow<ExportViewState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    // The video already failed once and was asked for again.
    private var hasRetried = false

    init {
        load()
    }

    fun retry() {
        hasRetried = false
        load()
    }

    /** The player gave up: the video is asked for once more, which also renews an ended sign-in. */
    fun playerFailed(failure: VideoFailure) {
        val isFirst = !hasRetried
        hasRetried = true
        // Without the file the player goes, and a new one is made when it is back.
        mutableState.update { it.copy(file = null, failure = if (isFirst) null else failure) }
        if (isFirst) load()
    }

    /** Removes the file from the server, for everyone. A kept moment is no longer marked as one to keep. */
    fun delete() {
        val item = mutableState.value.item ?: return
        if (mutableState.value.isDeleting) return
        mutableState.update { it.copy(isDeleting = true, failure = null) }
        viewModelScope.launch {
            val session = registry.session(args.serverId)
            if (session == null) {
                // The server was removed meanwhile: there is nothing left to show.
                mutableState.update { it.copy(isGone = true) }
                return@launch
            }
            try {
                session.deleteExport(item.id)
                item.savedReviewId?.let { review ->
                    try {
                        session.setRetained(session.review(review), retained = false)
                    } catch (_: FrigateError.UnexpectedResponse) {
                        // The moment itself has expired meanwhile.
                    }
                }
                mutableState.update { it.copy(isGone = true) }
            } catch (error: FrigateError) {
                mutableState.update { it.copy(isDeleting = false, failure = error.toVideoFailure()) }
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.update { it.copy(failure = null) }
        loadJob =
            viewModelScope.launch {
                registry.load()
                val session = registry.session(args.serverId)
                if (session == null) {
                    mutableState.update { it.copy(isGone = true) }
                    return@launch
                }
                try {
                    val item = session.exports().firstOrNull { it.id == args.exportId }
                    if (item == null) {
                        mutableState.update { it.copy(isGone = true) }
                        return@launch
                    }
                    mutableState.update { it.copy(item = item) }
                    if (!item.inProgress) {
                        val file = session.fileOf(item)
                        mutableState.update { it.copy(file = file) }
                    }
                } catch (error: FrigateError) {
                    mutableState.update { it.copy(failure = error.toVideoFailure()) }
                }
            }
    }
}
