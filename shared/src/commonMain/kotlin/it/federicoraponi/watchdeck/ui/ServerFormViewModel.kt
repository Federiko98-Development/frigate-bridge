package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.auth.DEFAULT_OIDC_CLIENT_ID
import it.federicoraponi.watchdeck.auth.DEFAULT_OIDC_SCOPES
import it.federicoraponi.watchdeck.auth.MemoryOidcTokenStore
import it.federicoraponi.watchdeck.auth.OidcBearer
import it.federicoraponi.watchdeck.auth.OidcSettings
import it.federicoraponi.watchdeck.auth.OidcSignIn
import it.federicoraponi.watchdeck.auth.OidcTokens
import it.federicoraponi.watchdeck.auth.isAcceptableProviderUrl
import it.federicoraponi.watchdeck.auth.issuerFromLoginUrl
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.Server
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSessionFactory
import it.federicoraponi.watchdeck.server.ServerSignIn
import it.federicoraponi.watchdeck.server.isCleartextUrl
import it.federicoraponi.watchdeck.server.keepsSignIn
import it.federicoraponi.watchdeck.server.normalizeServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Which server to edit; `null` adds a new one. */
data class ServerFormArgs(
    val serverId: String? = null,
)

enum class FormError {
    NameMissing,
    UrlInvalid,
    AlternativeUrlInvalid,
    PasswordMissing,
    IssuerInvalid,
    ClientIdMissing,
}

/** The fields of the sign-in through an external provider. */
data class ExternalSignInForm(
    val issuer: String = "",
    val clientId: String = DEFAULT_OIDC_CLIENT_ID,
    val scopes: String = DEFAULT_OIDC_SCOPES,
    val useIdToken: Boolean = false,
    /** The address the provider must send the browser back to, for whoever sets the provider up. */
    val redirectUri: String = "",
)

data class CertificatePrompt(
    val sha256Fingerprint: String,
    /** The user had accepted a different certificate for this server before. */
    val replacesAccepted: Boolean,
)

data class ServerFormState(
    val isEditing: Boolean = false,
    val name: String = "",
    val url: String = "",
    val alternativeUrl: String = "",
    val username: String = "",
    val password: String = "",
    val colorIndex: Int = 0,
    val errors: Set<FormError> = emptySet(),
    val isSaving: Boolean = false,
    /** Why the last connection test failed. */
    val problem: ConnectionProblem? = null,
    /** The server did not answer: it may be saved without the test. */
    val canSaveAnyway: Boolean = false,
    val certificatePrompt: CertificatePrompt? = null,
    /** Set once the server is saved: the screen can close. */
    val savedServerId: String? = null,
    /** Users sign in at an external provider, in the browser, instead of with Frigate's user name and password. */
    val isExternal: Boolean = false,
    val external: ExternalSignInForm = ExternalSignInForm(),
    /** The browser is open: the app waits for the provider to send it back. */
    val isWaitingForBrowser: Boolean = false,
    /** The provider's or the server's own explanation of the [problem]. */
    val problemDetail: String? = null,
    /** The server answered with a sign-in page, whose provider's address could be read or not. */
    val detectedSignIn: DetectedSignIn? = null,
) {
    /** What would cross the network unencrypted: the password, or the tokens. */
    val sendsPasswordUnencrypted: Boolean
        get() = isCleartextUrl(url) || isCleartextUrl(alternativeUrl)
}

data class DetectedSignIn(
    /** `null` when only the user can tell which provider it is. */
    val issuer: String?,
)

class ServerFormViewModel(
    args: ServerFormArgs,
    private val registry: ServerRegistry,
    private val sessions: ServerSessionFactory,
    private val signIn: OidcSignIn,
) : ViewModel() {
    private val editedId = args.serverId
    private var original: Server? = null
    private var accepted: AcceptedCertificate? = null

    // Signed in for this form, so that testing again, e.g. after accepting a certificate, does not open the browser.
    private var held: HeldSignIn? = null
    private val mutableState =
        MutableStateFlow(
            ServerFormState(
                isEditing = editedId != null,
                external = ExternalSignInForm(redirectUri = signIn.redirectUri),
            ),
        )
    val state: StateFlow<ServerFormState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = registry.load()
            val server = loaded.servers.firstOrNull { it.id == editedId }
            if (server == null) {
                mutableState.update { it.copy(colorIndex = loaded.servers.size % SERVER_COLOR_COUNT) }
            } else {
                original = server
                mutableState.update {
                    it
                        .copy(
                            name = server.name,
                            url = server.url,
                            alternativeUrl = server.alternativeUrl.orEmpty(),
                            username = server.username,
                            colorIndex = server.colorIndex,
                        ).withSignIn(server.signIn)
                }
            }
        }
    }

    fun setName(value: String) = edit { it.copy(name = value) }

    fun setUrl(value: String) = edit { it.copy(url = value) }

    fun setAlternativeUrl(value: String) = edit { it.copy(alternativeUrl = value) }

    fun setUsername(value: String) = edit { it.copy(username = value) }

    fun setPassword(value: String) = edit { it.copy(password = value) }

    fun setColor(index: Int) = edit { it.copy(colorIndex = index) }

    fun setExternal(external: Boolean) = edit { it.copy(isExternal = external, errors = emptySet()) }

    fun setIssuer(value: String) = edit { it.copy(external = it.external.copy(issuer = value)) }

    fun setClientId(value: String) = edit { it.copy(external = it.external.copy(clientId = value)) }

    fun setScopes(value: String) = edit { it.copy(external = it.external.copy(scopes = value)) }

    fun setUseIdToken(value: Boolean) = edit { it.copy(external = it.external.copy(useIdToken = value)) }

    /** Switches to the external sign-in that the server turned out to ask for. */
    fun useDetectedSignIn() {
        val detected = mutableState.value.detectedSignIn ?: return
        edit {
            val issuer = detected.issuer ?: it.external.issuer
            it.copy(isExternal = true, external = it.external.copy(issuer = issuer))
        }
    }

    /** Gives up waiting for the browser, e.g. after the user came back from it without signing in. */
    fun cancelSignIn() = signIn.cancel()

    /** Checks the connection and saves the server if it answers. */
    fun save() = submit(testConnection = true)

    fun saveAnyway() = submit(testConnection = false)

    fun acceptCertificate() {
        val prompt = mutableState.value.certificatePrompt ?: return
        val server = readForm() ?: return
        accepted = AcceptedCertificate(server.addresses, prompt.sha256Fingerprint)
        mutableState.update { it.copy(certificatePrompt = null) }
        submit(testConnection = true)
    }

    fun rejectCertificate() {
        mutableState.update { it.copy(certificatePrompt = null, problem = ConnectionProblem.UntrustedCertificate) }
    }

    private fun edit(change: (ServerFormState) -> ServerFormState) {
        mutableState.update {
            if (it.isSaving) {
                it
            } else {
                change(it).copy(
                    errors = emptySet(),
                    problem = null,
                    problemDetail = null,
                    detectedSignIn = null,
                    canSaveAnyway = false,
                )
            }
        }
    }

    private fun submit(testConnection: Boolean) {
        if (mutableState.value.isSaving) return
        val server = readForm() ?: return
        val typedPassword = mutableState.value.password
        mutableState.update {
            it.copy(isSaving = true, problem = null, problemDetail = null, detectedSignIn = null, canSaveAnyway = false)
        }
        viewModelScope.launch {
            when (val method = server.signIn) {
                ServerSignIn.Password -> submitPassword(server, typedPassword, testConnection)
                is ServerSignIn.Oidc -> submitExternal(server, method.settings, testConnection)
            }
        }
    }

    private suspend fun submitPassword(
        server: Server,
        typedPassword: String,
        testConnection: Boolean,
    ) {
        // An empty password field keeps the stored one.
        val keepsPassword = typedPassword.isEmpty() && canKeepPassword(server)
        if (testConnection) {
            val password = if (keepsPassword) registry.storedPassword(server.id).orEmpty() else typedPassword
            val failure = test(server, password, MemoryOidcTokenStore())
            if (failure != null) {
                showFailure(failure, server)
                return
            }
        }
        registry.save(server, if (keepsPassword) null else typedPassword)
        saved(server)
    }

    private suspend fun submitExternal(
        server: Server,
        settings: OidcSettings,
        testConnection: Boolean,
    ) {
        val reusable = reusableSignIn(server)
        if (!testConnection) {
            registry.save(server, password = "", signIn = reusable)
            saved(server)
            return
        }
        try {
            var tokens = reusable ?: openBrowser(server, settings)
            var store = MemoryOidcTokenStore(tokens)
            var failure = test(server, "", store)
            // Tokens kept from before may have ended meanwhile: signing in again is the way on.
            if (failure is FrigateError.SignInRequired && reusable != null) {
                held = null
                tokens = openBrowser(server, settings)
                store = MemoryOidcTokenStore(tokens)
                failure = test(server, "", store)
            }
            // The test may have renewed them, and a provider may accept each refresh token only once.
            val latest = store.read()
            held = latest?.let { HeldSignIn(server, it) }
            if (failure != null) {
                showFailure(failure, server)
                return
            }
            registry.save(server, password = "", signIn = latest)
            saved(server)
        } catch (failure: FrigateError) {
            showFailure(failure, server)
        }
    }

    private suspend fun openBrowser(
        server: Server,
        settings: OidcSettings,
    ): OidcTokens {
        mutableState.update { it.copy(isWaitingForBrowser = true) }
        try {
            return signIn.signIn(settings).also { held = HeldSignIn(server, it) }
        } finally {
            mutableState.update { it.copy(isWaitingForBrowser = false) }
        }
    }

    // Tokens go only where they went before: the same provider settings, and no new address.
    private suspend fun reusableSignIn(server: Server): OidcTokens? {
        held?.takeIf { keepsSignIn(it.server, server) }?.let { return it.tokens }
        val before = original ?: return null
        return if (keepsSignIn(before, server)) registry.storedSignIn(before.id) else null
    }

    /** Why the server cannot be used, or `null` when it answers. */
    private suspend fun test(
        server: Server,
        password: String,
        oidcTokens: MemoryOidcTokenStore,
    ): FrigateError? {
        val trial = sessions.trial(server, password, oidcTokens)
        return try {
            (trial.refresh() as? ConnectionState.Failed)?.error
        } finally {
            trial.close()
        }
    }

    private fun saved(server: Server) {
        mutableState.update { it.copy(isSaving = false, savedServerId = server.id) }
    }

    private fun showFailure(
        error: FrigateError,
        server: Server,
    ) {
        mutableState.update {
            if (error is FrigateError.UntrustedCertificate) {
                it.copy(
                    isSaving = false,
                    certificatePrompt =
                        CertificatePrompt(error.sha256Fingerprint, server.certificateSha256 != null),
                )
            } else {
                it.copy(
                    isSaving = false,
                    problem = error.toProblem(),
                    problemDetail = error.signInDetail(),
                    detectedSignIn =
                        (error as? FrigateError.ExternalSignIn)?.let { external ->
                            DetectedSignIn(issuerFromLoginUrl(external.loginUrl))
                        },
                    canSaveAnyway = error is FrigateError.Unreachable || error is FrigateError.RateLimited,
                )
            }
        }
    }

    // The stored password is reused only for the same user at the same addresses, so that editing an address
    // never sends it somewhere new without the user typing it again.
    private fun canKeepPassword(server: Server): Boolean {
        val before = original ?: return false
        return before.username == server.username && before.addresses.containsAll(server.addresses)
    }

    /** The server described by the form, or `null` after publishing what is wrong with it. */
    @OptIn(ExperimentalUuidApi::class)
    private fun readForm(): Server? {
        val form = mutableState.value
        val url = normalizeServerUrl(form.url)
        val alternativeInput = form.alternativeUrl.trim()
        val alternative = if (alternativeInput.isEmpty()) null else normalizeServerUrl(alternativeInput)
        val server =
            Server(
                id = original?.id ?: newId ?: Uuid.random().toString().also { newId = it },
                name = form.name.trim(),
                url = url.orEmpty(),
                alternativeUrl = alternative,
                username = if (form.isExternal) "" else form.username.trim(),
                colorIndex = form.colorIndex,
                signIn = form.signInMethod(),
            )
        val errors =
            buildSet {
                if (server.name.isEmpty()) add(FormError.NameMissing)
                if (url == null) add(FormError.UrlInvalid)
                if (alternativeInput.isNotEmpty() && alternative == null) add(FormError.AlternativeUrlInvalid)
                if (server.username.isNotEmpty() && form.password.isEmpty() && !canKeepPassword(server)) {
                    add(FormError.PasswordMissing)
                }
                if (form.isExternal) {
                    if (!isAcceptableProviderUrl(form.external.issuer)) add(FormError.IssuerInvalid)
                    if (form.external.clientId.isBlank()) add(FormError.ClientIdMissing)
                }
            }
        mutableState.update { it.copy(errors = errors) }
        return if (errors.isEmpty()) server.copy(certificateSha256 = certificateFor(server)) else null
    }

    // An accepted certificate stays valid only while the addresses it was accepted for stay the same.
    private fun certificateFor(server: Server): String? {
        accepted?.takeIf { it.addresses == server.addresses }?.let { return it.sha256Fingerprint }
        val before = original ?: return null
        return before.certificateSha256.takeIf { before.addresses.containsAll(server.addresses) }
    }

    private var newId: String? = null

    private class HeldSignIn(
        val server: Server,
        val tokens: OidcTokens,
    )

    private data class AcceptedCertificate(
        val addresses: List<String>,
        val sha256Fingerprint: String,
    )
}

private fun ServerFormState.signInMethod(): ServerSignIn {
    if (!isExternal) return ServerSignIn.Password
    return ServerSignIn.Oidc(
        OidcSettings(
            issuer = external.issuer.trim().trimEnd('/'),
            clientId = external.clientId.trim(),
            scopes = external.scopes.trim().ifEmpty { DEFAULT_OIDC_SCOPES },
            bearer = if (external.useIdToken) OidcBearer.IdToken else OidcBearer.AccessToken,
        ),
    )
}

private fun ServerFormState.withSignIn(method: ServerSignIn): ServerFormState {
    val settings = (method as? ServerSignIn.Oidc)?.settings ?: return this
    return copy(
        isExternal = true,
        external =
            external.copy(
                issuer = settings.issuer,
                clientId = settings.clientId,
                scopes = settings.scopes,
                useIdToken = settings.bearer == OidcBearer.IdToken,
            ),
    )
}

/** How many colours a server can be marked with; the screens define the colours themselves. */
const val SERVER_COLOR_COUNT = 6
