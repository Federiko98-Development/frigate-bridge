package it.federicoraponi.watchdeck.auth

import it.federicoraponi.watchdeck.frigate.Authenticator
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.frigate.PasswordLogin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// A token this close to its end is replaced before it is sent.
private const val RENEW_AHEAD_SECONDS = 60L

/**
 * Signs the requests of a server behind a proxy that accepts the tokens of an OpenID Connect provider. It renews
 * them with the refresh token; when that no longer works, the user has to sign in again in the browser.
 */
class OidcAuthenticator(
    private val settings: OidcSettings,
    private val client: OidcClient,
    private val store: OidcTokenStore,
    private val now: () -> Long,
) : Authenticator {
    private val mutex = Mutex()
    private var provider: OidcProvider? = null
    private var mustRenew = false

    override val isExternal = true

    override suspend fun token(): String =
        mutex.withLock {
            val current = store.read() ?: throw FrigateError.SignInRequired()
            val bearer = current.bearer(settings)
            val expiry = bearer?.let(::jwtClaims)?.number("exp") ?: current.accessExpiresAt
            val isEnding = expiry != null && expiry - now() <= RENEW_AHEAD_SECONDS
            when {
                bearer == null -> refresh(current)
                !mustRenew && !isEnding -> bearer
                // Without a refresh token the old one is used to its last second.
                current.refreshToken == null && !mustRenew && expiry != null && expiry > now() -> bearer
                else -> refresh(current)
            }
        }

    override suspend fun renew(
        stale: String?,
        login: PasswordLogin,
    ): String =
        mutex.withLock {
            val current = store.read() ?: throw FrigateError.SignInRequired()
            // Another request already renewed it while this one was waiting.
            current.bearer(settings)?.takeIf { it != stale } ?: refresh(current)
        }

    private suspend fun refresh(current: OidcTokens): String {
        val known = provider ?: client.discover(settings).also { provider = it }
        // A provider may replace the refresh token at each use: once sent, the answer must be saved.
        return withContext(NonCancellable) {
            val renewed =
                try {
                    client.refresh(known, settings, current)
                } catch (ended: FrigateError.SignInRequired) {
                    store.write(null)
                    throw ended
                }
            store.write(renewed)
            mustRenew = false
            renewed.bearer(settings) ?: throw FrigateError.SignInRequired()
        }
    }

    override suspend fun reset() = Unit

    override suspend fun invalidate() = mutex.withLock { mustRenew = true }

    // Frigate's tokens mean nothing to the proxy.
    override suspend fun adopt(token: String) = Unit
}
