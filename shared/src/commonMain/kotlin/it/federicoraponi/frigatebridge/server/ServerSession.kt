package it.federicoraponi.frigatebridge.server

import io.ktor.client.HttpClient
import it.federicoraponi.frigatebridge.camera.CameraCatalog
import it.federicoraponi.frigatebridge.camera.cameraCatalog
import it.federicoraponi.frigatebridge.frigate.Credentials
import it.federicoraponi.frigatebridge.frigate.FrigateApi
import it.federicoraponi.frigatebridge.frigate.FrigateError
import it.federicoraponi.frigatebridge.frigate.TokenStore
import it.federicoraponi.frigatebridge.frigate.isSupportedFrigateVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ServerSnapshot(
    /** The address that answered. */
    val url: String,
    val version: String,
    val isSupportedVersion: Boolean,
    val catalog: CameraCatalog,
)

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data class Connected(
        val snapshot: ServerSnapshot,
    ) : ConnectionState

    data class Failed(
        val error: FrigateError,
    ) : ConnectionState
}

/** The connection to one server, independent from the others: its own HTTP client, token and state. */
class ServerSession(
    val server: Server,
    private val http: HttpClient,
    credentials: suspend () -> Credentials?,
    tokens: TokenStore,
) {
    private val apis = server.addresses.map { url -> url to FrigateApi(http, url, credentials, tokens) }
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** Connects and reads cameras and groups. Failures end up in [state] and are not thrown. */
    suspend fun refresh(): ConnectionState =
        refreshMutex.withLock {
            val previous = mutableState.value
            // Keep showing the cameras while an already connected server is being read again.
            if (previous !is ConnectionState.Connected) mutableState.value = ConnectionState.Connecting
            val result =
                try {
                    ConnectionState.Connected(load())
                } catch (cancellation: CancellationException) {
                    mutableState.value = previous
                    throw cancellation
                } catch (error: FrigateError) {
                    ConnectionState.Failed(error)
                }
            mutableState.value = result
            result
        }

    fun close() {
        http.close()
    }

    private suspend fun load(): ServerSnapshot {
        var unreachable: FrigateError = FrigateError.Unreachable()
        for ((url, api) in apis) {
            api.resetLoginFailure()
            val version =
                try {
                    api.version()
                } catch (error: FrigateError.Unreachable) {
                    unreachable = error
                    continue
                }
            val catalog = cameraCatalog(server.id, api.config(), api.userProfile())
            return ServerSnapshot(url, version, isSupportedFrigateVersion(version), catalog)
        }
        throw unreachable
    }
}
