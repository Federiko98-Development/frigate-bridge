package it.federicoraponi.watchdeck.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RegistryState(
    val servers: List<Server> = emptyList(),
    val defaultServerId: String? = null,
    /** The server whose cameras are on screen. Screens only ever show one server. */
    val activeServerId: String? = null,
    /** Grows on every [ServerRegistry.save], which replaces the session even if only the password changed. */
    val revision: Int = 0,
) {
    val activeServer: Server? get() = servers.firstOrNull { it.id == activeServerId }
}

/**
 * The saved servers, the default and the active one, and one [ServerSession] each.
 *
 * Call it from the main thread: sessions are created on demand without locking.
 */
class ServerRegistry(
    private val store: ServerStore,
    private val secrets: SecretStore,
    private val sessions: ServerSessionFactory,
) {
    private val mutex = Mutex()
    private val openSessions = mutableMapOf<String, ServerSession>()
    private val mutableState = MutableStateFlow<RegistryState?>(null)

    /** `null` until [load] has read the saved servers. */
    val state: StateFlow<RegistryState?> = mutableState.asStateFlow()

    suspend fun load(): RegistryState =
        mutex.withLock {
            mutableState.value ?: store.read().let { saved ->
                // The default server is the one the app opens on.
                RegistryState(saved.servers, saved.defaultServerId, activeServerId = saved.defaultServerId)
                    .also { mutableState.value = it }
            }
        }

    /** The session of a saved server, or `null` if there is no such server. */
    fun session(serverId: String): ServerSession? {
        val server = mutableState.value?.servers?.firstOrNull { it.id == serverId } ?: return null
        val open = openSessions[serverId]
        if (open != null && open.server == server) return open
        open?.close()
        return sessions.saved(server).also { openSessions[serverId] = it }
    }

    suspend fun storedPassword(serverId: String): String? = secrets.read(passwordKey(serverId))

    /** Adds or replaces a server. A `null` [password] keeps the stored one. */
    suspend fun save(
        server: Server,
        password: String?,
    ) {
        load()
        mutex.withLock {
            val current = checkNotNull(mutableState.value)
            // Secrets first: whoever observes the new server must already find them.
            if (password != null) secrets.write(passwordKey(server.id), password)
            secrets.write(tokenKey(server.id), null)
            openSessions.remove(server.id)?.close()
            val exists = current.servers.any { it.id == server.id }
            val servers =
                if (exists) {
                    current.servers.map { if (it.id == server.id) server else it }
                } else {
                    current.servers + server
                }
            publish(current.copy(servers = servers, revision = current.revision + 1))
        }
    }

    suspend fun remove(serverId: String) {
        load()
        mutex.withLock {
            val current = checkNotNull(mutableState.value)
            publish(
                current.copy(
                    servers = current.servers.filterNot { it.id == serverId },
                    defaultServerId = current.defaultServerId.takeIf { it != serverId },
                    activeServerId = current.activeServerId.takeIf { it != serverId },
                ),
            )
            openSessions.remove(serverId)?.close()
            secrets.write(passwordKey(serverId), null)
            secrets.write(tokenKey(serverId), null)
        }
    }

    suspend fun setDefault(serverId: String?) {
        load()
        mutex.withLock {
            val current = checkNotNull(mutableState.value)
            if (serverId != null && current.servers.none { it.id == serverId }) return
            publish(current.copy(defaultServerId = serverId))
        }
    }

    suspend fun activate(serverId: String) {
        load()
        mutex.withLock {
            val current = checkNotNull(mutableState.value)
            if (current.servers.none { it.id == serverId }) return
            mutableState.value = current.copy(activeServerId = serverId)
        }
    }

    private suspend fun publish(next: RegistryState) {
        store.write(SavedServers(next.servers, next.defaultServerId))
        mutableState.value = next
    }
}
