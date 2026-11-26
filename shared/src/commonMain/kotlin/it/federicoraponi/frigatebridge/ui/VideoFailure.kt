package it.federicoraponi.frigatebridge.ui

/** Why a video cannot be played. [detail] is the server's or player's own explanation, for diagnosis. */
data class VideoFailure(
    val problem: ConnectionProblem? = null,
    val detail: String? = null,
)
