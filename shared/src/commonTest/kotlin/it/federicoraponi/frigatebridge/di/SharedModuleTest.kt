package it.federicoraponi.frigatebridge.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import it.federicoraponi.frigatebridge.network.NetworkMonitor
import it.federicoraponi.frigatebridge.server.FakeNetwork
import it.federicoraponi.frigatebridge.server.FakeNetworkMonitor
import it.federicoraponi.frigatebridge.server.FakeSecretStore
import it.federicoraponi.frigatebridge.server.HttpClientFactory
import it.federicoraponi.frigatebridge.server.MemoryServerStore
import it.federicoraponi.frigatebridge.server.SecretStore
import it.federicoraponi.frigatebridge.server.ServerStore
import it.federicoraponi.frigatebridge.ui.AccessViewModel
import it.federicoraponi.frigatebridge.ui.CameraGroupEditorArgs
import it.federicoraponi.frigatebridge.ui.CameraGroupEditorViewModel
import it.federicoraponi.frigatebridge.ui.CameraGroupsViewModel
import it.federicoraponi.frigatebridge.ui.CamerasViewModel
import it.federicoraponi.frigatebridge.ui.ChangePasswordViewModel
import it.federicoraponi.frigatebridge.ui.ServerFormArgs
import it.federicoraponi.frigatebridge.ui.ServerFormViewModel
import it.federicoraponi.frigatebridge.ui.ServerListViewModel
import it.federicoraponi.frigatebridge.ui.StartViewModel
import it.federicoraponi.frigatebridge.ui.ViewModelTest
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
