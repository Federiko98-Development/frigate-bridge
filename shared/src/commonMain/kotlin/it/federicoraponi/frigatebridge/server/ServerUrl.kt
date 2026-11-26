package it.federicoraponi.frigatebridge.server

import io.ktor.http.Url

/**
 * Turns what the user typed into a base address: adds `https://` when the scheme is missing and drops a trailing
 * slash or `/api`. Returns `null` if it is not a usable HTTP address.
 */
fun normalizeServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val scheme = withScheme.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest =
        withScheme
            .substringAfter("://")
            .trimEnd('/')
            .removeSuffix("/api")
            .trimEnd('/')
    val authority = rest.substringBefore('/')
    // Credentials, queries and fragments do not belong in a base address.
    if (authority.isEmpty() || '@' in authority || '?' in rest || '#' in rest) return null
    if (authority.startsWith(':')) return null
    val normalized = "$scheme://$rest"
    return normalized.takeIf { runCatching { Url(it).host.isNotEmpty() }.getOrDefault(false) }
}

/** Whether an address sends the password and the video without encryption. */
fun isCleartextUrl(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)
