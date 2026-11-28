package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.frigate.ProviderFailure
import it.federicoraponi.watchdeck.server.ConnectionState

/** Why a server cannot be used, in a form the screens can translate into text. */
enum class ConnectionProblem {
    Unreachable,
    LoginRequired,
    LoginRejected,
    RateLimited,
    UntrustedCertificate,
    UnexpectedResponse,
    ExternalSignIn,
    SignInRequired,
    SignInRefused,
    SignInCancelled,
    ProviderUnreachable,
    ProviderMisconfigured,
    NoBrowser,
}

fun FrigateError.toProblem(): ConnectionProblem =
    when (this) {
        is FrigateError.Unreachable -> ConnectionProblem.Unreachable
        is FrigateError.LoginRequired -> ConnectionProblem.LoginRequired
        is FrigateError.LoginRejected -> ConnectionProblem.LoginRejected
        is FrigateError.RateLimited -> ConnectionProblem.RateLimited
        is FrigateError.UntrustedCertificate -> ConnectionProblem.UntrustedCertificate
        is FrigateError.UnexpectedResponse -> ConnectionProblem.UnexpectedResponse
        is FrigateError.ExternalSignIn -> ConnectionProblem.ExternalSignIn
        is FrigateError.SignInRequired -> ConnectionProblem.SignInRequired
        is FrigateError.SignInRefused -> ConnectionProblem.SignInRefused
        is FrigateError.ProviderFailed ->
            when (reason) {
                ProviderFailure.Unreachable -> ConnectionProblem.ProviderUnreachable
                ProviderFailure.Misconfigured -> ConnectionProblem.ProviderMisconfigured
                ProviderFailure.Cancelled -> ConnectionProblem.SignInCancelled
                ProviderFailure.NoBrowser -> ConnectionProblem.NoBrowser
            }
    }

/** The explanation that comes with a failure to sign in through an external provider, if there is one. */
fun FrigateError.signInDetail(): String? =
    when (this) {
        is FrigateError.ProviderFailed -> detail
        is FrigateError.SignInRefused -> "HTTP $status"
        else -> null
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
