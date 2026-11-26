package it.federicoraponi.frigatebridge.ui

import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.server.ConnectionState

/** Why a server cannot be used, in a form the screens can translate into text. */
enum class ConnectionProblem {
    Unreachable,
    LoginRequired,
    LoginRejected,
    RateLimited,
    UntrustedCertificate,
    UnexpectedResponse,
}

fun FrigateError.toProblem(): ConnectionProblem =
    when (this) {
        is FrigateError.Unreachable -> ConnectionProblem.Unreachable
        is FrigateError.LoginRequired -> ConnectionProblem.LoginRequired
        is FrigateError.LoginRejected -> ConnectionProblem.LoginRejected
        is FrigateError.RateLimited -> ConnectionProblem.RateLimited
        is FrigateError.UntrustedCertificate -> ConnectionProblem.UntrustedCertificate
        is FrigateError.UnexpectedResponse -> ConnectionProblem.UnexpectedResponse
    }

sealed interface ServerStatus {
    data object Checking : ServerStatus

    data object Online : ServerStatus

    data class Unavailable(
        val problem: ConnectionProblem,
    ) : ServerStatus
}

fun ConnectionState.toStatus(): ServerStatus =
    when (this) {
        ConnectionState.Idle, ConnectionState.Connecting -> ServerStatus.Checking
        is ConnectionState.Connected -> ServerStatus.Online
        is ConnectionState.Failed -> ServerStatus.Unavailable(error.toProblem())
    }
