package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * Accepting a self-signed certificate is not implemented on iOS yet: only certificates trusted by the system work.
 */
internal class IosHttpClientFactory : HttpClientFactory {
    override fun create(acceptedCertificateSha256: String?): HttpClient = HttpClient(Darwin) { configureForFrigate() }
}

/** Placeholder until the Keychain is wired up: secrets are kept in memory and lost when the app closes. */
internal class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
