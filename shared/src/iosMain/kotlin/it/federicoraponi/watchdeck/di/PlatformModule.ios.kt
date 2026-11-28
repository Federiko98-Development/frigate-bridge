package it.federicoraponi.watchdeck.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import it.federicoraponi.watchdeck.auth.IosSignInBrowser
import it.federicoraponi.watchdeck.auth.SignInBrowser
import it.federicoraponi.watchdeck.network.AssumedOnlineNetworkMonitor
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.server.HttpClientFactory
import it.federicoraponi.watchdeck.server.IosHttpClientFactory
import it.federicoraponi.watchdeck.server.MemorySecretStore
import it.federicoraponi.watchdeck.server.SecretStore
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSHomeDirectory

internal actual val platformModule: Module =
    module {
        single<HttpClientFactory> { IosHttpClientFactory() }
        single<NetworkMonitor> { AssumedOnlineNetworkMonitor() }
        single<SecretStore> { MemorySecretStore() }
        single<SignInBrowser> { IosSignInBrowser() }
        single {
            PreferenceDataStoreFactory.createWithPath {
                "${NSHomeDirectory()}/Documents/servers.preferences_pb".toPath()
            }
        }
    }
