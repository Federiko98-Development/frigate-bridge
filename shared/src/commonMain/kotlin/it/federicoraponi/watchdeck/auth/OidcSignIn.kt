package it.federicoraponi.watchdeck.auth

import io.ktor.http.Url
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.frigate.ProviderFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

// Long enough to type a password and a second factor.
private const val SIGN_IN_TIMEOUT_MILLIS = 10 * 60 * 1_000L

/** The phone's browser, provided by each platform. Never a view inside the app, which could read the password. */
interface SignInBrowser {
    /** Where the provider sends the browser back to, and the app receives it. */
    val redirectUri: String

    /** `false` when nothing can open [url]. */
    fun open(url: String): Boolean
}

/**
 * Signs a user in through the browser. One attempt at a time: a new one replaces the previous. The platform hands
 * the address the browser comes back with to [handleRedirect].
 */
class OidcSignIn(
    private val client: OidcClient,
    private val browser: SignInBrowser,
) {
    private class Pending(
        val state: String,
        val code: CompletableDeferred<String> = CompletableDeferred(),
    ) {
        @Volatile
        var isInBrowser = false
    }

    @Volatile
    private var pending: Pending? = null

    val redirectUri: String get() = browser.redirectUri

    suspend fun signIn(
        settings: OidcSettings,
        askAgain: Boolean = false,
    ): OidcTokens {
        cancel()
        val provider = client.discover(settings)
        val request = client.authorizationRequest(provider, settings, browser.redirectUri, askAgain)
        val attempt = Pending(request.state)
        pending = attempt
        try {
            if (!browser.open(request.url)) {
                throw FrigateError.ProviderFailed(ProviderFailure.NoBrowser, null)
            }
            attempt.isInBrowser = true
            val code =
                withTimeoutOrNull(SIGN_IN_TIMEOUT_MILLIS) { attempt.code.await() }
                    ?: throw FrigateError.ProviderFailed(ProviderFailure.Cancelled, null)
            return client.exchange(provider, settings, request, code)
        } finally {
            if (pending === attempt) pending = null
        }
    }

    /**
     * What the browser came back with. Anything on the phone may send such an address, so only the answer to the
     * attempt in progress, which carries its secret state, is taken. `false` when it is not.
     */
    fun handleRedirect(redirect: String): Boolean {
        val attempt = pending ?: return false
        if (!redirect.startsWith(browser.redirectUri)) return false
        val parameters = runCatching { Url(redirect).parameters }.getOrNull() ?: return false
        if (parameters["state"] != attempt.state) return false
        val code = parameters["code"]
        if (code != null) return attempt.code.complete(code)
        val error = parameters["error"] ?: return false
        val failure =
            if (error == "access_denied") ProviderFailure.Cancelled else ProviderFailure.Misconfigured
        val detail = listOfNotNull(error, parameters["error_description"]).joinToString(": ")
        return attempt.code.completeExceptionally(FrigateError.ProviderFailed(failure, detail))
    }

    /**
     * The user is back in the app. With the browser open and no answer from it, they closed it: the attempt ends
     * instead of waiting on. Call it after [handleRedirect] had its chance.
     */
    fun returnedWithoutAnswer() {
        if (pending?.isInBrowser == true) cancel()
    }

    /** Gives up the attempt in progress, if any. */
    fun cancel() {
        pending?.code?.completeExceptionally(FrigateError.ProviderFailed(ProviderFailure.Cancelled, null))
        pending = null
    }
}
