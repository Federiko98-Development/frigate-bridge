package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.recordings.ExportItem
import it.federicoraponi.frigatebridge.server.ExportedFile
import it.federicoraponi.frigatebridge.server.ServerRegistry
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

    init {
        load()
    }

    fun retry() = load()

    fun playerFailed(detail: String?) {
        mutableState.update { it.copy(file = null, failure = VideoFailure(detail = detail)) }
    }

    /** Removes the file from the server, for everyone. A kept moment is no longer marked as one to keep. */
    fun delete() {
        val item = mutableState.value.item ?: return
        if (mutableState.value.isDeleting) return
        mutableState.update { it.copy(isDeleting = true, failure = null) }
        viewModelScope.launch {
            val session = registry.session(args.serverId) ?: return@launch
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
