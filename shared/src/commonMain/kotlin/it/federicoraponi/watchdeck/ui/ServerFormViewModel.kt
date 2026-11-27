package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.Server
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSessionFactory
import it.federicoraponi.watchdeck.server.isCleartextUrl
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
}

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
) {
    val sendsPasswordUnencrypted: Boolean
        get() = isCleartextUrl(url) || isCleartextUrl(alternativeUrl)
}

class ServerFormViewModel(
    args: ServerFormArgs,
    private val registry: ServerRegistry,
    private val sessions: ServerSessionFactory,
) : ViewModel() {
    private val editedId = args.serverId
    private var original: Server? = null
    private var accepted: AcceptedCertificate? = null
    private val mutableState = MutableStateFlow(ServerFormState(isEditing = editedId != null))
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
                    it.copy(
                        name = server.name,
                        url = server.url,
                        alternativeUrl = server.alternativeUrl.orEmpty(),
                        username = server.username,
                        colorIndex = server.colorIndex,
                    )
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
                change(it).copy(errors = emptySet(), problem = null, canSaveAnyway = false)
            }
        }
    }

    private fun submit(testConnection: Boolean) {
        if (mutableState.value.isSaving) return
        val server = readForm() ?: return
        val typedPassword = mutableState.value.password
        mutableState.update { it.copy(isSaving = true, problem = null, canSaveAnyway = false) }
        viewModelScope.launch {
            // An empty password field keeps the stored one.
            val keepsPassword = typedPassword.isEmpty() && canKeepPassword(server)
            if (testConnection) {
                val password = if (keepsPassword) registry.storedPassword(server.id).orEmpty() else typedPassword
                val trial = sessions.trial(server, password)
                val result =
                    try {
                        trial.refresh()
                    } finally {
                        trial.close()
                    }
                if (result is ConnectionState.Failed) {
                    showFailure(result.error, server)
                    return@launch
                }
            }
            registry.save(server, if (keepsPassword) null else typedPassword)
            mutableState.update { it.copy(isSaving = false, savedServerId = server.id) }
        }
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
                username = form.username.trim(),
                colorIndex = form.colorIndex,
            )
        val errors =
            buildSet {
                if (server.name.isEmpty()) add(FormError.NameMissing)
                if (url == null) add(FormError.UrlInvalid)
                if (alternativeInput.isNotEmpty() && alternative == null) add(FormError.AlternativeUrlInvalid)
                if (server.username.isNotEmpty() && form.password.isEmpty() && !canKeepPassword(server)) {
                    add(FormError.PasswordMissing)
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

    private data class AcceptedCertificate(
        val addresses: List<String>,
        val sha256Fingerprint: String,
    )
}

/** How many colours a server can be marked with; the screens define the colours themselves. */
const val SERVER_COLOR_COUNT = 6
