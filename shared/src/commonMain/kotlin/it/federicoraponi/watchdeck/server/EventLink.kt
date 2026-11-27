package it.federicoraponi.watchdeck.server

import io.ktor.http.Url

/**
 * A link that opens the app on a review moment, as a notification may carry:
 * `watchdeck://event?id=<review id>&server=<address of the server>`.
 *
 * @property serverAddress one of the server's addresses, as saved in the app; without it the default server is meant.
 */
data class EventLink(
    val reviewId: String,
    val serverAddress: String? = null,
)

// Frigate's identifiers look like `1718000000.123456-abc123`. Nothing else gets into a request's path.
private val REVIEW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

/** Reads [link], or returns `null` if it is not a link to a review moment. Anything may send one. */
fun parseEventLink(link: String): EventLink? {
    val url = runCatching { Url(link.trim()) }.getOrNull() ?: return null
    if (url.protocol.name != "watchdeck" || url.host != "event") return null
    val id = url.parameters["id"]?.takeIf(REVIEW_ID::matches) ?: return null
    return EventLink(id, url.parameters["server"]?.takeIf { it.isNotBlank() })
}

/**
 * The saved server a link means: the one with that host and port, never an unknown address. Without an address, the
 * default server, or the only one.
 */
fun RegistryState.serverFor(link: EventLink): Server? {
    val address =
        link.serverAddress ?: return servers.firstOrNull { it.id == defaultServerId } ?: servers.singleOrNull()
    val wanted = normalizeServerUrl(address)?.let(::Url) ?: return null
    return servers.firstOrNull { server ->
        server.addresses.any {
            val known = Url(it)
            known.host.equals(wanted.host, ignoreCase = true) && known.port == wanted.port
        }
    }
}
