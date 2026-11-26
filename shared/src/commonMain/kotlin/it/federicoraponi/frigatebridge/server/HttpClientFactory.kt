package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout

/** Creates the HTTP client of one server, so that an accepted certificate applies to that server only. */
fun interface HttpClientFactory {
    fun create(acceptedCertificateSha256: String?): HttpClient
}

/** Settings shared by the platform clients. */
internal fun HttpClientConfig<*>.configureForFrigate() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        // Short, so that an unreachable local address gives way quickly to the alternative one.
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
}
