package it.federicoraponi.frigatebridge.server

import android.annotation.SuppressLint
import it.federicoraponi.frigatebridge.frigate.CertificateRejection
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class UntrustedCertificateException(
    override val sha256Fingerprint: String,
    cause: Throwable,
) : CertificateException("Untrusted certificate $sha256Fingerprint", cause),
    CertificateRejection

/** SHA-256 of the certificate, in the `AB:CD:…` form that browsers and `openssl` show. */
fun Certificate.sha256Fingerprint(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(encoded)
        .joinToString(":") { "%02X".format(it) }

/**
 * Normal certificate validation, plus one exception: the certificate whose fingerprint the user accepted for
 * this server. Frigate's self-signed certificate does not name the server's address, so for that certificate
 * only, the host name is not checked either.
 */
fun OkHttpClient.Builder.acceptingCertificate(
    acceptedSha256: String?,
    systemTrust: X509TrustManager = systemTrustManager(),
): OkHttpClient.Builder {
    val trust = PinningTrustManager(systemTrust, acceptedSha256)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    val verifier =
        HostnameVerifier { host, session ->
            OkHostnameVerifier.verify(host, session) ||
                (
                    acceptedSha256 != null &&
                        session.peerCertificates.firstOrNull()?.sha256Fingerprint() == acceptedSha256
                )
        }
    return sslSocketFactory(context.socketFactory, trust).hostnameVerifier(verifier)
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}

// Lint flags any custom trust manager. This one only widens trust to a single certificate the user approved.
@SuppressLint("CustomX509TrustManager")
private class PinningTrustManager(
    private val system: X509TrustManager,
    private val acceptedSha256: String?,
) : X509TrustManager {
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) = system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>,
        authType: String,
    ) {
        val fingerprint = chain.firstOrNull()?.sha256Fingerprint()
        if (fingerprint != null && fingerprint == acceptedSha256) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (rejected: CertificateException) {
            throw UntrustedCertificateException(fingerprint ?: throw rejected, rejected)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
