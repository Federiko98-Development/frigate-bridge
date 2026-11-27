package it.federicoraponi.watchdeck.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import it.federicoraponi.watchdeck.network.AndroidNetworkMonitor
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.server.AndroidHttpClientFactory
import it.federicoraponi.watchdeck.server.HttpClientFactory
import it.federicoraponi.watchdeck.server.KeystoreSecretStore
import it.federicoraponi.watchdeck.server.SecretStore
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformModule: Module =
    module {
        single<HttpClientFactory> { AndroidHttpClientFactory() }
        single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
        single<SecretStore> { KeystoreSecretStore(androidContext()) }
        single {
            val context = androidContext()
            PreferenceDataStoreFactory.createWithPath {
                context.filesDir.resolve("datastore/servers.preferences_pb").toOkioPath()
            }
        }
    }
