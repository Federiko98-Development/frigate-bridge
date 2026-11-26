package it.federicoraponi.frigatebridge.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import it.federicoraponi.frigatebridge.server.AndroidHttpClientFactory
import it.federicoraponi.frigatebridge.server.HttpClientFactory
import it.federicoraponi.frigatebridge.server.KeystoreSecretStore
import it.federicoraponi.frigatebridge.server.SecretStore
import okio.Path.Companion.toOkioPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformModule: Module =
    module {
        single<HttpClientFactory> { AndroidHttpClientFactory() }
        single<SecretStore> { KeystoreSecretStore(androidContext()) }
        single {
            val context = androidContext()
            PreferenceDataStoreFactory.createWithPath {
                context.filesDir.resolve("datastore/servers.preferences_pb").toOkioPath()
            }
        }
    }
