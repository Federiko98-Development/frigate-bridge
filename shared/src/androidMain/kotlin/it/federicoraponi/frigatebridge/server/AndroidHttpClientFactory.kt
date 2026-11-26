package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

class AndroidHttpClientFactory : HttpClientFactory {
    override fun create(acceptedCertificateSha256: String?): HttpClient =
        HttpClient(OkHttp) {
            configureForFrigate()
            engine {
                preconfigured = OkHttpClient.Builder().acceptingCertificate(acceptedCertificateSha256).build()
            }
        }
}
