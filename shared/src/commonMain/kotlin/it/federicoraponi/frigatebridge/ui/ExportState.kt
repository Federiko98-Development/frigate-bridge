package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ExportedFile

sealed interface ExportState {
    data object None : ExportState

    /** The server is cutting the file. */
    data object Working : ExportState

    data class Ready(
        val file: ExportedFile,
    ) : ExportState

    data class Failed(
        val failure: VideoFailure,
    ) : ExportState
}

internal fun FrigateError.toVideoFailure() =
    VideoFailure(toProblem(), (this as? FrigateError.UnexpectedResponse)?.detail)
