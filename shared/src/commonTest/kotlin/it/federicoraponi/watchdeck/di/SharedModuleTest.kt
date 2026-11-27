package it.federicoraponi.watchdeck.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.server.FakeNetwork
import it.federicoraponi.watchdeck.server.FakeNetworkMonitor
import it.federicoraponi.watchdeck.server.FakeSecretStore
import it.federicoraponi.watchdeck.server.HttpClientFactory
import it.federicoraponi.watchdeck.server.MemoryServerStore
import it.federicoraponi.watchdeck.server.SecretStore
import it.federicoraponi.watchdeck.server.ServerStore
import it.federicoraponi.watchdeck.ui.AccessViewModel
import it.federicoraponi.watchdeck.ui.CameraGroupEditorArgs
import it.federicoraponi.watchdeck.ui.CameraGroupEditorViewModel
import it.federicoraponi.watchdeck.ui.CameraGroupsViewModel
import it.federicoraponi.watchdeck.ui.CamerasViewModel
import it.federicoraponi.watchdeck.ui.ChangePasswordViewModel
import it.federicoraponi.watchdeck.ui.ServerFormArgs
import it.federicoraponi.watchdeck.ui.ServerFormViewModel
import it.federicoraponi.watchdeck.ui.ServerListViewModel
import it.federicoraponi.watchdeck.ui.StartViewModel
import it.federicoraponi.watchdeck.ui.ViewModelTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test

private class UnusedDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = emptyFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("not used")
}

class SharedModuleTest : ViewModelTest() {
    @Test
    fun resolvesEveryViewModel() {
        val fakePlatform =
            module {
                single<HttpClientFactory> { FakeNetwork() }
                single<NetworkMonitor> { FakeNetworkMonitor() }
                single<SecretStore> { FakeSecretStore() }
                // ViewModels read the saved servers as soon as they are created.
                single<ServerStore> { MemoryServerStore() }
                single<DataStore<Preferences>> { UnusedDataStore() }
            }
        val application = koinApplication { modules(sharedModule, fakePlatform) }
        val koin = application.koin

        koin.get<ServerFormViewModel> { parametersOf(ServerFormArgs()) }
        koin.get<StartViewModel>()
        koin.get<ServerListViewModel>()
        koin.get<CamerasViewModel>()
        koin.get<AccessViewModel>()
        koin.get<ChangePasswordViewModel>()
        koin.get<CameraGroupsViewModel>()
        koin.get<CameraGroupEditorViewModel> { parametersOf(CameraGroupEditorArgs("home", null)) }

        application.close()
    }
}
