package it.federicoraponi.frigatebridge.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import it.federicoraponi.frigatebridge.network.AssumedOnlineNetworkMonitor
import it.federicoraponi.frigatebridge.network.NetworkMonitor
import it.federicoraponi.frigatebridge.server.HttpClientFactory
import it.federicoraponi.frigatebridge.server.IosHttpClientFactory
import it.federicoraponi.frigatebridge.server.MemorySecretStore
import it.federicoraponi.frigatebridge.server.SecretStore
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSHomeDirectory

internal actual val platformModule: Module =
    module {
        single<HttpClientFactory> { IosHttpClientFactory() }
        single<NetworkMonitor> { AssumedOnlineNetworkMonitor() }
        single<SecretStore> { MemorySecretStore() }
        single {
            PreferenceDataStoreFactory.createWithPath {
                "${NSHomeDirectory()}/Documents/servers.preferences_pb".toPath()
            }
        }
    }
