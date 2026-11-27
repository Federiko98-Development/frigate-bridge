package it.federicoraponi.watchdeck.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.server.AccessRules
import it.federicoraponi.watchdeck.ui.AdminFailure

/** Why an administration action did not work. A refusal ends with Frigate's own explanation, if it gave one. */
@Composable
fun adminFailureText(failure: AdminFailure): String =
    when (failure) {
        is AdminFailure.Connection -> problemText(failure.problem)
        AdminFailure.NotAllowed -> stringResource(R.string.admin_not_allowed)
        AdminFailure.WrongCurrentPassword -> stringResource(R.string.password_wrong_current)
        AdminFailure.TooManyAttempts -> stringResource(R.string.admin_too_many_attempts)
        AdminFailure.Busy -> stringResource(R.string.admin_busy)
        AdminFailure.NotSaved -> stringResource(R.string.admin_not_saved)
        AdminFailure.WeakPassword -> stringResource(R.string.password_too_short, AccessRules.MIN_PASSWORD)
        is AdminFailure.Refused ->
            listOfNotNull(stringResource(R.string.admin_refused), failure.message).joinToString("\n")
    }
