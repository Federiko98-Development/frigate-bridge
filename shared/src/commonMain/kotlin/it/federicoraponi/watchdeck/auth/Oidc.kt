package it.federicoraponi.watchdeck.auth

import io.ktor.http.Url
import kotlinx.serialization.Serializable

/** The client the app asks the provider to know it by, unless the user writes another. */
const val DEFAULT_OIDC_CLIENT_ID = "watchdeck"
const val DEFAULT_OIDC_SCOPES = "openid profile email"

/** Which of the provider's tokens goes to the server. */
@Serializable
enum class OidcBearer {
    /** What OAuth means for an API. The server's proxy must accept the token's audience. */
    AccessToken,

    /** For providers whose access tokens are not JWTs, which a proxy cannot check. */
    IdToken,
}

/** How to sign in at an OpenID Connect provider. Nothing in it is secret: the app is a public client. */
@Serializable
data class OidcSettings(
    /** The provider's address, as it writes it in its tokens. */
    val issuer: String,
    val clientId: String = DEFAULT_OIDC_CLIENT_ID,
    val scopes: String = DEFAULT_OIDC_SCOPES,
    val bearer: OidcBearer = OidcBearer.AccessToken,
)

/** What a provider handed out. It is a secret, kept in the secret store. */
@Serializable
data class OidcTokens(
    val accessToken: String,
    val idToken: String? = null,
    val refreshToken: String? = null,
    /** When the access token stops working, in seconds since the epoch. */
    val accessExpiresAt: Long? = null,
)

/** Where the tokens of one server are kept. */
interface OidcTokenStore {
    suspend fun read(): OidcTokens?

    suspend fun write(tokens: OidcTokens?)
}

/** Tokens held in memory only, e.g. while a server is being tried before it is saved. */
class MemoryOidcTokenStore(
    private var tokens: OidcTokens? = null,
) : OidcTokenStore {
    override suspend fun read(): OidcTokens? = tokens

    override suspend fun write(tokens: OidcTokens?) {
        this.tokens = tokens
    }
}

private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]", "::1")

/**
 * Whether the app may talk to a provider at [url]: over HTTPS, or on the phone itself, which is only useful to try
 * a setup. Tokens never cross a network unencrypted.
 */
fun isAcceptableProviderUrl(url: String): Boolean {
    val text = url.trim()
    val scheme = text.substringBefore("://", "").lowercase()
    if (scheme != "https" && scheme != "http") return false
    val parsed = runCatching { Url(text) }.getOrNull() ?: return false
    if (parsed.host.isBlank() || parsed.user != null) return false
    return scheme == "https" || parsed.host.lowercase() in LOOPBACK
}

/**
 * The issuer behind the address of a sign-in page, where it can be told: Keycloak's pages are under the issuer.
 * For other providers the user writes it.
 */
fun issuerFromLoginUrl(loginUrl: String): String? {
    val path = loginUrl.substringBefore('?')
    val marker = "/protocol/openid-connect/auth"
    if (!path.endsWith(marker)) return null
    return path.removeSuffix(marker).takeIf(::isAcceptableProviderUrl)
}
