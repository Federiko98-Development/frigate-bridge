package it.federicoraponi.watchdeck.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import it.federicoraponi.watchdeck.auth.OidcSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class MemoryDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    override val data = MutableStateFlow(initial)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(data.value).also { data.value = it }
}

class ServerStoreTest {
    @Test
    fun readsTheServersSavedByVersion101AsUsingFrigatesLogin() =
        runTest {
            val saved =
                """{"servers":[{"id":"home","name":"Home","url":"https://frigate.example.com",""" +
                    """"username":"viewer","colorIndex":2}],"defaultServerId":"home"}"""
            val store =
                DataStoreServerStore(
                    MemoryDataStore(
                        mutablePreferencesOf(
                            stringPreferencesKey("servers") to saved,
                        ),
                    ),
                )

            val server = store.read().servers.single()

            assertEquals(ServerSignIn.Password, server.signIn)
            assertEquals("viewer", server.username)
        }

    @Test
    fun keepsHowEachServerSignsItsUsersIn() =
        runTest {
            val store = DataStoreServerStore(MemoryDataStore())
            val external =
                Server(
                    "lab",
                    "Lab",
                    "https://frigate.example.com",
                    signIn = ServerSignIn.Oidc(OidcSettings("https://id.example.com/realms/demo")),
                )
            val servers = SavedServers(listOf(external, Server("home", "Home", "https://home.example.com")), "lab")

            store.write(servers)

            assertEquals(servers, store.read())
        }
}
