package it.federicoraponi.watchdeck.server

import it.federicoraponi.watchdeck.auth.OidcSettings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Frigate server saved in the app. The password and the session token are not part of it: they live in the
 * [SecretStore].
 *
 * @property url the first address tried, typically the local one.
 * @property alternativeUrl tried when [url] does not answer, e.g. a public domain or a VPN address.
 * @property certificateSha256 fingerprint of a self-signed certificate that the user accepted.
 */
@Serializable
data class Server(
    val id: String,
    val name: String,
    val url: String,
    val alternativeUrl: String? = null,
    val username: String = "",
    val colorIndex: Int = 0,
    val certificateSha256: String? = null,
    val signIn: ServerSignIn = ServerSignIn.Password,
) {
    val addresses: List<String> get() = listOfNotNull(url, alternativeUrl)
}

/** Who signs the users of a server in. Another way of signing in is another kind here, with its authenticator. */
@Serializable
sealed interface ServerSignIn {
    /** Frigate's own login, with the server's user name and password; also servers that ask for none. */
    @Serializable
    @SerialName("password")
    data object Password : ServerSignIn

    /** A proxy in front of Frigate that accepts the tokens of an OpenID Connect provider. */
    @Serializable
    @SerialName("oidc")
    data class Oidc(
        val settings: OidcSettings,
    ) : ServerSignIn
}
