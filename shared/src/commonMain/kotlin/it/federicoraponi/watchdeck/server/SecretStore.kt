package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.auth.OidcTokenStore
import it.federicoraponi.watchdeck.auth.OidcTokens
import it.federicoraponi.watchdeck.frigate.TokenStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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

internal fun oidcKey(serverId: String) = "oidc.$serverId"

internal class SecretOidcTokenStore(
    private val secrets: SecretStore,
    private val serverId: String,
) : OidcTokenStore {
    override suspend fun read(): OidcTokens? =
        secrets.read(oidcKey(serverId))?.let {
            try {
                Json.decodeFromString<OidcTokens>(it)
            } catch (_: SerializationException) {
                // What cannot be read any more is as good as signed out.
                null
            }
        }

    override suspend fun write(tokens: OidcTokens?) =
        secrets.write(oidcKey(serverId), tokens?.let { Json.encodeToString(it) })
}

internal class SecretTokenStore(
    private val secrets: SecretStore,
    private val serverId: String,
) : TokenStore {
    override suspend fun read(): String? = secrets.read(tokenKey(serverId))

    override suspend fun write(token: String?) = secrets.write(tokenKey(serverId), token)
}
