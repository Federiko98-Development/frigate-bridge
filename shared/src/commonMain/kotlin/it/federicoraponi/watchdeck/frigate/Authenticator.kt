package it.federicoraponi.watchdeck.frigate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Credentials(
    val username: String,
    val password: String,
)

/** Where the session token of one server is kept. */
interface TokenStore {
    suspend fun read(): String?

    suspend fun write(token: String?)
}

/** Frigate's own login endpoint, offered to the authenticator that uses it. */
fun interface PasswordLogin {
    /** The new token, or `null` if the server has authentication turned off. */
    suspend fun signIn(credentials: Credentials): String?
}

/**
 * How the requests of one server are signed. Every way of signing in ends in a bearer token; they differ in where it
 * comes from. A session has one, shared by all its addresses, so that a token is renewed once.
 */
interface Authenticator {
    /** `true` when something in front of Frigate signs users in, and Frigate's own login is not used. */
    val isExternal: Boolean

    /** The token to send now, or `null` to send none. */
    suspend fun token(): String?

    /** Another token, after [stale] was refused. */
    suspend fun renew(
        stale: String?,
        login: PasswordLogin,
    ): String?

    /** Allows trying again after a refusal. Call it when the user asks to retry. */
    suspend fun reset()

    /** The next token must be a new one, e.g. because the user's role changed. */
    suspend fun invalidate()

    /** Takes a token that the server handed out in an answer, as it does after a password change. */
    suspend fun adopt(token: String)
}

/** Frigate's own users: a name and a password exchanged for a token at `/api/login`. */
class PasswordAuthenticator(
    private val credentials: suspend () -> Credentials?,
    private val tokens: TokenStore,
) : Authenticator {
    private val mutex = Mutex()

    // Set after the server refuses the credentials, so that they are not sent again and again.
    private var failure: FrigateError? = null

    override val isExternal = false

    override suspend fun token(): String? = tokens.read()

    override suspend fun renew(
        stale: String?,
        login: PasswordLogin,
    ): String? =
        mutex.withLock {
            val current = tokens.read()
            // Another request already logged in while this one was waiting.
            if (current != null && current != stale) return@withLock current
            failure?.let { throw it }
            val stored = credentials()
            if (stored == null || stored.username.isBlank()) throw FrigateError.LoginRequired()
            try {
                login.signIn(stored).also { tokens.write(it) }
            } catch (error: FrigateError) {
                if (error is FrigateError.LoginRejected || error is FrigateError.RateLimited) failure = error
                throw error
            }
        }

    override suspend fun reset() = mutex.withLock { failure = null }

    override suspend fun invalidate() {
        reset()
        tokens.write(null)
    }

    override suspend fun adopt(token: String) = tokens.write(token)
}
