package it.federicoraponi.frigatebridge.server

import it.federicoraponi.frigatebridge.frigate.findCertificateRejection
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Real TLS handshakes against a server with a self-signed certificate, like Frigate's. */
class CertificateTrustTest {
    // Like Frigate's certificate, it does not name the address used to reach the server.
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("frigate.invalid").build()
    private val server = MockWebServer()

    @BeforeTest
    fun start() {
        val handshake = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(handshake.sslSocketFactory())
        server.enqueue(MockResponse(body = "0.18.3"))
        server.start()
    }

    @AfterTest
    fun stop() {
        server.close()
    }

    private fun get(acceptedSha256: String?): String {
        val client = OkHttpClient.Builder().acceptingCertificate(acceptedSha256).build()
        val request = Request.Builder().url(server.url("/api/version")).build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    @Test
    fun refusesAnUnknownCertificateAndReportsItsFingerprint() {
        val failure = assertFailsWith<IOException> { get(acceptedSha256 = null) }

        val rejection = findCertificateRejection(failure)
        assertNotNull(rejection)
        assertEquals(certificate.certificate.sha256Fingerprint(), rejection.sha256Fingerprint)
    }

    @Test
    fun acceptsTheApprovedCertificateEvenIfItDoesNotNameTheHost() {
        assertEquals("0.18.3", get(certificate.certificate.sha256Fingerprint()))
    }

    @Test
    fun refusesACertificateOtherThanTheApprovedOne() {
        val other = HeldCertificate.Builder().build()

        val failure = assertFailsWith<IOException> { get(other.certificate.sha256Fingerprint()) }

        assertNotNull(findCertificateRejection(failure))
    }
}
