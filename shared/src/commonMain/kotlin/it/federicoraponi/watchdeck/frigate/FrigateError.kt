package it.federicoraponi.watchdeck.frigate

/** Everything that can go wrong while talking to a Frigate server. */
sealed class FrigateError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** No answer: wrong address, server down or network problem. */
    class Unreachable(
        cause: Throwable? = null,
    ) : FrigateError("The server cannot be reached", cause)

    /** The server asks for a login and no credentials are stored. */
    class LoginRequired : FrigateError("The server requires a user name and password")

    /** The server refused the stored credentials. */
    class LoginRejected : FrigateError("User name or password not accepted")

    /** Too many failed logins: the server is temporarily refusing new ones. */
    class RateLimited : FrigateError("Too many login attempts")

    /** The TLS certificate is not trusted by the device and does not match the one the user accepted. */
    class UntrustedCertificate(
        val sha256Fingerprint: String,
    ) : FrigateError("Untrusted certificate")

    /** The server answered with something that is not a valid Frigate response. */
    class UnexpectedResponse(
        val detail: String,
        cause: Throwable? = null,
        /** The HTTP status, when the server answered. */
        val status: Int? = null,
        /** Frigate's own explanation: the `message` or `detail` field of its JSON answer. */
        val serverMessage: String? = null,
    ) : FrigateError("Unexpected response: $detail", cause)
}

/** Implemented by the platform exception that a TLS handshake throws for an untrusted certificate. */
interface CertificateRejection {
    val sha256Fingerprint: String
}

/**
 * Looks for a [CertificateRejection] behind [failure]. A host with several addresses is tried one address at a
 * time, so the rejection may be a suppressed exception of a later "connection refused" rather than its cause.
 */
internal fun findCertificateRejection(failure: Throwable): CertificateRejection? {
    val seen = mutableSetOf<Throwable>()
    val pending = ArrayDeque(listOf(failure))
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (!seen.add(next)) continue
        if (next is CertificateRejection) return next
        next.cause?.let { pending.add(it) }
        pending.addAll(next.suppressedExceptions)
    }
    return null
}
