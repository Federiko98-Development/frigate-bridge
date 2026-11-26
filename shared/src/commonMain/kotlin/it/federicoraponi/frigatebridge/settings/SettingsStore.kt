package it.federicoraponi.frigatebridge.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Persists the app's settings. */
interface SettingsStore {
    val settings: Flow<AppSettings>

    suspend fun update(change: (AppSettings) -> AppSettings)
}

class DataStoreSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    private val key = stringPreferencesKey("settings")
    private val json =
        Json {
            ignoreUnknownKeys = true
            // A value written by a newer version of the app falls back to the default.
            coerceInputValues = true
        }

    override val settings: Flow<AppSettings> = dataStore.data.map { read(it) }.distinctUntilChanged()

    override suspend fun update(change: (AppSettings) -> AppSettings) {
        dataStore.edit { it[key] = json.encodeToString(change(read(it))) }
    }

    private fun read(preferences: Preferences): AppSettings {
        val stored = preferences[key] ?: return AppSettings()
        return try {
            json.decodeFromString(stored)
        } catch (_: SerializationException) {
            AppSettings()
        } catch (_: IllegalArgumentException) {
            AppSettings()
        }
    }
}
