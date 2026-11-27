package it.federicoraponi.watchdeck.server

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
) {
    val addresses: List<String> get() = listOfNotNull(url, alternativeUrl)
}
