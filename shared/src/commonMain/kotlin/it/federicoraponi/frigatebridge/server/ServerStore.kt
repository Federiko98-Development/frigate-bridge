package it.federicoraponi.frigatebridge.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedServers(
    val servers: List<Server> = emptyList(),
    val defaultServerId: String? = null,
)

/** Persists the list of servers. */
interface ServerStore {
    suspend fun read(): SavedServers

    suspend fun write(saved: SavedServers)
}

class DataStoreServerStore(
    private val dataStore: DataStore<Preferences>,
) : ServerStore {
    private val key = stringPreferencesKey("servers")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): SavedServers {
        val stored = dataStore.data.first()[key] ?: return SavedServers()
        return json.decodeFromString(stored)
    }

    override suspend fun write(saved: SavedServers) {
        dataStore.edit { it[key] = json.encodeToString(saved) }
    }
}
