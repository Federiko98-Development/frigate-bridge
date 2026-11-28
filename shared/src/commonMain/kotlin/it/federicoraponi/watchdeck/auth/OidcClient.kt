package it.federicoraponi.watchdeck.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.frigate.ProviderFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The addresses a provider publishes about itself (OpenID Connect Discovery). */
@Serializable
data class OidcProvider(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
)

/** One attempt to sign in: what is sent to the browser, and what checks the answer. */
data class AuthorizationRequest(
    val url: String,
    val state: String,
    val nonce: String,
    val verifier: String,
    val redirectUri: String,
)

@Serializable
private data class TokenAnswer(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

private val oidcJson = Json { ignoreUnknownKeys = true }

/**
 * Talks to OpenID Connect providers: the authorization code flow with PKCE, for a client without a secret
 * (RFC 8252). It has its own HTTP client, which trusts only the certificates the phone trusts.
 */
class OidcClient(
    client: Lazy<HttpClient>,
    /** Seconds since the epoch. */
    private val now: () -> Long,
) {
    private val http by client

    suspend fun discover(settings: OidcSettings): OidcProvider {
        val issuer = settings.issuer.trim().trimEnd('/')
        if (!isAcceptableProviderUrl(issuer)) misconfigured("The provider's address must use HTTPS")
        val response = send(HttpMethod.Get, "$issuer/.well-known/openid-configuration")
        if (!response.status.isSuccess()) {
            misconfigured("HTTP ${response.status.value} on the provider's configuration: check its address")
        }
        val provider = decode<OidcProvider>(response)
        // A provider that names another issuer is not the one that was asked for.
        if (provider.issuer.trimEnd('/') != issuer) misconfigured("The provider calls itself ${provider.issuer}")
        if (!isAcceptableProviderUrl(provider.authorizationEndpoint.substringBefore('?')) ||
            !isAcceptableProviderUrl(provider.tokenEndpoint)
        ) {
            misconfigured("The provider's endpoints must use HTTPS")
        }
        return provider
    }

    /** [askAgain] makes the provider ask who is signing in, even with a session open in the browser. */
    fun authorizationRequest(
        provider: OidcProvider,
        settings: OidcSettings,
        redirectUri: String,
        askAgain: Boolean = false,
    ): AuthorizationRequest {
        val state = randomToken()
        val nonce = randomToken()
        val verifier = randomToken()
        val url =
            URLBuilder(provider.authorizationEndpoint)
                .apply {
                    parameters.append("response_type", "code")
                    parameters.append("client_id", settings.clientId)
                    parameters.append("redirect_uri", redirectUri)
                    parameters.append("scope", settings.scopes)
                    parameters.append("state", state)
                    parameters.append("nonce", nonce)
                    parameters.append("code_challenge", pkceChallenge(verifier))
                    parameters.append("code_challenge_method", "S256")
                    if (askAgain) parameters.append("prompt", "login")
                }.buildString()
        return AuthorizationRequest(url, state, nonce, verifier, redirectUri)
    }

    /** Exchanges the [code] that came back to the app for tokens. */
    suspend fun exchange(
        provider: OidcProvider,
        settings: OidcSettings,
        request: AuthorizationRequest,
        code: String,
    ): OidcTokens {
        val answer =
            token(
                provider,
                "grant_type" to "authorization_code",
                "client_id" to settings.clientId,
                "code" to code,
                "redirect_uri" to request.redirectUri,
                "code_verifier" to request.verifier,
            )
        val tokens = tokens(answer, previous = null)
        checkIdToken(tokens.idToken, provider, settings, request.nonce)
        checkBearer(tokens, settings)
        return tokens
    }

    /** New tokens for the old ones. Throws [FrigateError.SignInRequired] once the provider's session has ended. */
    suspend fun refresh(
        provider: OidcProvider,
        settings: OidcSettings,
        current: OidcTokens,
    ): OidcTokens {
        val refreshToken = current.refreshToken ?: throw FrigateError.SignInRequired()
        val answer =
            token(
                provider,
                "grant_type" to "refresh_token",
                "client_id" to settings.clientId,
                "refresh_token" to refreshToken,
            )
        val renewed = tokens(answer, previous = current)
        // An ID token kept from before would be sent although it has expired.
        if (settings.bearer == OidcBearer.IdToken && answer.idToken == null) throw FrigateError.SignInRequired()
        checkBearer(renewed, settings)
        return renewed
    }

    private suspend fun token(
        provider: OidcProvider,
        vararg form: Pair<String, String>,
    ): TokenAnswer {
        val body = form.joinToString("&") { (name, value) -> "$name=${value.encodeURLParameter()}" }
        val response = send(HttpMethod.Post, provider.tokenEndpoint, body)
        if (response.status.isSuccess()) return decode(response)
        if (response.status.value >= 500) {
            throw FrigateError.ProviderFailed(ProviderFailure.Unreachable, "HTTP ${response.status.value}")
        }
        val refusal =
            try {
                oidcJson.decodeFromString<TokenAnswer>(response.bodyAsText())
            } catch (_: SerializationException) {
                TokenAnswer()
            } catch (_: IllegalArgumentException) {
                TokenAnswer()
            }
        // The code or the refresh token is no longer good: only signing in again helps.
        if (refusal.error == "invalid_grant") throw FrigateError.SignInRequired()
        if (response.status == HttpStatusCode.TooManyRequests) throw FrigateError.RateLimited()
        misconfigured(
            listOfNotNull(refusal.error, refusal.errorDescription)
                .joinToString(": ")
                .ifEmpty { "HTTP ${response.status.value} from the provider" },
        )
    }

    private fun tokens(
        answer: TokenAnswer,
        previous: OidcTokens?,
    ): OidcTokens {
        val access = answer.accessToken ?: misconfigured("The provider sent no access token")
        val expiry = jwtClaims(access)?.number("exp") ?: answer.expiresIn?.let { now() + it }
        return OidcTokens(
            accessToken = access,
            idToken = answer.idToken ?: previous?.idToken,
            // Some providers replace the refresh token at every use, others keep it.
            refreshToken = answer.refreshToken ?: previous?.refreshToken,
            accessExpiresAt = expiry,
        )
    }

    // The checks of OpenID Connect Core 3.1.3.7 for a token received directly from the provider.
    private fun checkIdToken(
        idToken: String?,
        provider: OidcProvider,
        settings: OidcSettings,
        nonce: String,
    ) {
        val claims = idToken?.let(::jwtClaims) ?: misconfigured("The provider sent no ID token: is openid a scope?")
        if (claims.text("iss") != provider.issuer) misconfigured("The ID token has another issuer")
        if (settings.clientId !in claims.audiences()) misconfigured("The ID token is meant for another client")
        if (claims.text("nonce") != nonce) misconfigured("The ID token answers another request")
        if ((claims.number("exp") ?: 0) <= now()) misconfigured("The ID token has expired: check the phone's clock")
    }

    private fun checkBearer(
        tokens: OidcTokens,
        settings: OidcSettings,
    ) {
        if (jwtClaims(tokens.bearer(settings) ?: "") == null) {
            misconfigured("The token to send is not a JWT: the server cannot check it. Try the ID token")
        }
    }

    private suspend fun send(
        method: HttpMethod,
        url: String,
        form: String? = null,
    ): HttpResponse =
        try {
            http.request(url) {
                this.method = method
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (form != null) setBody(TextContent(form, ContentType.Application.FormUrlEncoded))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw FrigateError.ProviderFailed(ProviderFailure.Unreachable, failure.message, failure)
        }

    private suspend inline fun <reified T> decode(response: HttpResponse): T =
        try {
            oidcJson.decodeFromString<T>(response.bodyAsText())
        } catch (_: SerializationException) {
            misconfigured("The provider's answer is not the expected JSON")
        } catch (_: IllegalArgumentException) {
            misconfigured("The provider's answer is not the expected JSON")
        }

    private fun misconfigured(detail: String): Nothing =
        throw FrigateError.ProviderFailed(ProviderFailure.Misconfigured, detail)
}

/** The token that goes to the server. */
internal fun OidcTokens.bearer(settings: OidcSettings): String? =
    when (settings.bearer) {
        OidcBearer.AccessToken -> accessToken
        OidcBearer.IdToken -> idToken
    }
