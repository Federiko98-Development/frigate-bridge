package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.frigate.Credentials
import it.federicoraponi.watchdeck.frigate.TokenStore

class ServerSessionFactory(
    private val clients: HttpClientFactory,
    private val secrets: SecretStore,
) {
    /** The session of a saved server, with its stored password and token. */
    fun saved(server: Server): ServerSession =
        ServerSession(
            server = server,
            http = clients.create(server.certificateSha256),
            credentials = { secrets.read(passwordKey(server.id))?.let { Credentials(server.username, it) } },
            tokens = SecretTokenStore(secrets, server.id),
            storePassword = { secrets.write(passwordKey(server.id), it) },
        )

    /** A throwaway session to check a server before saving it. Nothing is stored. */
    fun trial(
        server: Server,
        password: String,
    ): ServerSession =
        ServerSession(
            server = server,
            http = clients.create(server.certificateSha256),
            credentials = { Credentials(server.username, password) },
            tokens =
                object : TokenStore {
                    private var token: String? = null

                    override suspend fun read(): String? = token

                    override suspend fun write(token: String?) {
                        this.token = token
                    }
                },
        )
}
