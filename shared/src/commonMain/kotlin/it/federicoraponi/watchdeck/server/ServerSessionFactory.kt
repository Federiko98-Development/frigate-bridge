package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.auth.OidcAuthenticator
import it.federicoraponi.watchdeck.auth.OidcClient
import it.federicoraponi.watchdeck.auth.OidcTokenStore
import it.federicoraponi.watchdeck.frigate.Authenticator
import it.federicoraponi.watchdeck.frigate.Credentials
import it.federicoraponi.watchdeck.frigate.PasswordAuthenticator
import it.federicoraponi.watchdeck.frigate.TokenStore

class ServerSessionFactory(
    private val clients: HttpClientFactory,
    private val secrets: SecretStore,
    private val oidc: OidcClient,
    /** Seconds since the epoch. */
    private val now: () -> Long,
) {
    /** The session of a saved server, with what is stored to sign in to it. */
    fun saved(server: Server): ServerSession =
        ServerSession(
            server = server,
            http = clients.create(server.certificateSha256),
            authenticator =
                authenticator(
                    server,
                    password =
                        PasswordParts(
                            credentials = {
                                secrets.read(passwordKey(server.id))?.let { Credentials(server.username, it) }
                            },
                            tokens = SecretTokenStore(secrets, server.id),
                        ),
                    oidcTokens = SecretOidcTokenStore(secrets, server.id),
                ),
            storePassword = { secrets.write(passwordKey(server.id), it) },
        )

    /** A throwaway session to check a server before saving it. Nothing is stored. */
    fun trial(
        server: Server,
        password: String,
        oidcTokens: OidcTokenStore,
    ): ServerSession =
        ServerSession(
            server = server,
            http = clients.create(server.certificateSha256),
            authenticator =
                authenticator(
                    server,
                    password =
                        PasswordParts(
                            credentials = { Credentials(server.username, password) },
                            tokens =
                                object : TokenStore {
                                    private var token: String? = null

                                    override suspend fun read(): String? = token

                                    override suspend fun write(token: String?) {
                                        this.token = token
                                    }
                                },
                        ),
                    oidcTokens = oidcTokens,
                ),
        )

    private class PasswordParts(
        val credentials: suspend () -> Credentials?,
        val tokens: TokenStore,
    )

    private fun authenticator(
        server: Server,
        password: PasswordParts,
        oidcTokens: OidcTokenStore,
    ): Authenticator =
        when (val signIn = server.signIn) {
            ServerSignIn.Password -> PasswordAuthenticator(password.credentials, password.tokens)
            is ServerSignIn.Oidc -> OidcAuthenticator(signIn.settings, oidc, oidcTokens, now)
        }
}
