package it.federicoraponi.frigatebridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.recordings.ExportItem
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [savedOnly]: only the exports made to keep a review moment. */
data class ExportsArgs(
    val savedOnly: Boolean,
)

data class ExportsState(
    val savedOnly: Boolean = false,
    /** Newest first. */
    val items: List<ExportItem> = emptyList(),
    /** The names of the cameras as shown to the user, by their name on the server. */
    val cameraNames: Map<String, String> = emptyMap(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val failure: VideoFailure? = null,
    /** There is no server to show: go back. */
    val isGone: Boolean = false,
)

/** The videos kept on the active server. For administrators. */
class ExportsViewModel(
    private val args: ExportsArgs,
    private val registry: ServerRegistry,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExportsState(savedOnly = args.savedOnly))
    val state: StateFlow<ExportsState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private val thumbnails = mutableMapOf<String, ByteArray>()

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        load()
    }

    /** Reads the list. The screen calls it each time it comes to the front: something may have been deleted. */
    fun load() {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                val session = session() ?: return@launch
                try {
                    var all = emptyList<ExportItem>()
                    visibleRefresh { all = session.exports() }
                    val names = session.cameraNames
                    val items = if (args.savedOnly) all.filter { it.savedReviewId != null } else all
                    mutableState.update {
                        it.copy(
                            items = items,
                            cameraNames = names,
                            isLoading = false,
                            isRefreshing = false,
                            failure = null,
                        )
                    }
                } catch (error: FrigateError) {
                    mutableState.update {
                        it.copy(isLoading = false, isRefreshing = false, failure = error.toVideoFailure())
                    }
                }
            }
    }

    fun cachedThumbnail(item: ExportItem): ByteArray? = thumbnails[item.id]

    suspend fun loadThumbnail(item: ExportItem): ByteArray? =
        thumbnails[item.id]
            ?: if (item.thumbnailPath.isBlank()) {
                null
            } else {
                try {
                    registry.session(item.serverId)?.mediaFile(item.thumbnailPath)?.also { thumbnails[item.id] = it }
                } catch (_: FrigateError) {
                    null
                }
            }

    private suspend fun session(): ServerSession? {
        val server = registry.load().activeServer
        val session = server?.let { registry.session(it.id) }
        if (session == null) mutableState.update { it.copy(isGone = true) }
        return session
    }
}
