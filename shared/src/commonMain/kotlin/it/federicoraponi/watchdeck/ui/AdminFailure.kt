package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.server.AdminNotSaved

/** Why an administration action did not work, in a form the screens can translate into text. */
sealed interface AdminFailure {
    /** The server could not be used at all: unreachable, certificate, login… */
    data class Connection(
        val problem: ConnectionProblem,
    ) : AdminFailure

    data object NotAllowed : AdminFailure

    data object WrongCurrentPassword : AdminFailure

    data object TooManyAttempts : AdminFailure

    /** Something else is writing the configuration. */
    data object Busy : AdminFailure

    data object NotSaved : AdminFailure

    data object WeakPassword : AdminFailure

    /** Any other refusal, with Frigate's own explanation when it gave one. */
    data class Refused(
        val message: String?,
    ) : AdminFailure
}

private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val TOO_MANY_REQUESTS = 429
private const val UNAVAILABLE = 503
private const val WEAK_PASSWORD = "Password must be at least"

fun Throwable.toAdminFailure(): AdminFailure =
    when (this) {
        is AdminNotSaved -> AdminFailure.NotSaved
        is FrigateError.UnexpectedResponse ->
            when {
                // Any other 401 leads to a new login: only a wrong current password is reported with this status.
                status == UNAUTHORIZED -> AdminFailure.WrongCurrentPassword
                status == FORBIDDEN -> AdminFailure.NotAllowed
                status == TOO_MANY_REQUESTS -> AdminFailure.TooManyAttempts
                status == UNAVAILABLE -> AdminFailure.Busy
                serverMessage?.startsWith(WEAK_PASSWORD) == true -> AdminFailure.WeakPassword
                else -> AdminFailure.Refused(serverMessage)
            }
        is FrigateError -> AdminFailure.Connection(toProblem())
        else -> AdminFailure.Refused(null)
    }

/** Sends an administration [action] and returns why it did not work, or `null`. */
internal suspend fun adminFailureOf(action: suspend () -> Unit): AdminFailure? =
    try {
        action()
        null
    } catch (error: FrigateError) {
        error.toAdminFailure()
    } catch (error: AdminNotSaved) {
        error.toAdminFailure()
    } catch (error: IllegalArgumentException) {
        // The session refuses to treat the signed-in user as another one, e.g. after the server was edited.
        error.toAdminFailure()
    }
