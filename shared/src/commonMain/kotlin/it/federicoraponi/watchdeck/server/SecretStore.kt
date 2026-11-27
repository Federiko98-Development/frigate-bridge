package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.frigate.TokenStore

/** Encrypted storage for passwords and tokens: the Android Keystore, or the Keychain on iOS. */
interface SecretStore {
    suspend fun read(key: String): String?

    /** Stores [value], or deletes the entry when it is `null`. */
    suspend fun write(
        key: String,
        value: String?,
    )
}

internal fun passwordKey(serverId: String) = "password.$serverId"

internal fun tokenKey(serverId: String) = "token.$serverId"

internal class SecretTokenStore(
    private val secrets: SecretStore,
    private val serverId: String,
) : TokenStore {
    override suspend fun read(): String? = secrets.read(tokenKey(serverId))

    override suspend fun write(token: String?) = secrets.write(tokenKey(serverId), token)
}
