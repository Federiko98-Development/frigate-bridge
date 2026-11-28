package it.federicoraponi.watchdeck.di

import it.federicoraponi.watchdeck.auth.OidcClient
import it.federicoraponi.watchdeck.auth.OidcSignIn
import it.federicoraponi.watchdeck.server.DataStoreServerStore
import it.federicoraponi.watchdeck.server.ExportDownloads
import it.federicoraponi.watchdeck.server.HttpClientFactory
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSessionFactory
import it.federicoraponi.watchdeck.server.ServerStore
import it.federicoraponi.watchdeck.settings.DataStoreSettingsStore
import it.federicoraponi.watchdeck.settings.SettingsStore
import it.federicoraponi.watchdeck.ui.AccessViewModel
import it.federicoraponi.watchdeck.ui.CameraGroupEditorViewModel
import it.federicoraponi.watchdeck.ui.CameraGroupsViewModel
import it.federicoraponi.watchdeck.ui.CameraViewModel
import it.federicoraponi.watchdeck.ui.CamerasViewModel
import it.federicoraponi.watchdeck.ui.ChangePasswordViewModel
import it.federicoraponi.watchdeck.ui.EventViewModel
import it.federicoraponi.watchdeck.ui.EventsViewModel
import it.federicoraponi.watchdeck.ui.ExportViewModel
import it.federicoraponi.watchdeck.ui.ExportsViewModel
import it.federicoraponi.watchdeck.ui.LockViewModel
import it.federicoraponi.watchdeck.ui.ServerFormViewModel
import it.federicoraponi.watchdeck.ui.ServerListViewModel
import it.federicoraponi.watchdeck.ui.ServerStatusViewModel
import it.federicoraponi.watchdeck.ui.SettingsViewModel
import it.federicoraponi.watchdeck.ui.StartViewModel
import it.federicoraponi.watchdeck.ui.TimelineViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

val sharedModule =
    module {
        single<ServerStore> { DataStoreServerStore(get()) }
        single<SettingsStore> { DataStoreSettingsStore(get()) }
        // Providers are reached with the certificates the phone trusts, never with one accepted for a server.
        single { OidcClient(lazy { get<HttpClientFactory>().create(null) }, ::epochSeconds) }
        single { OidcSignIn(get(), get()) }
        single { ServerSessionFactory(get(), get(), get(), ::epochSeconds) }
        single { ServerRegistry(get(), get(), get()) }
        single { ExportDownloads(get()) }
        viewModelOf(::StartViewModel)
        viewModelOf(::ServerListViewModel)
        viewModelOf(::CamerasViewModel)
        viewModelOf(::SettingsViewModel)
        viewModelOf(::ServerStatusViewModel)
        viewModelOf(::AccessViewModel)
        viewModelOf(::ChangePasswordViewModel)
        viewModelOf(::CameraGroupsViewModel)
        viewModel { parameters -> CameraGroupEditorViewModel(parameters.get(), get(), get()) }
        viewModel { parameters -> ExportsViewModel(parameters.get(), get()) }
        viewModel { parameters -> ExportViewModel(parameters.get(), get()) }
        viewModelOf(::LockViewModel)
        viewModelOf(::EventsViewModel)
        viewModel { parameters -> ServerFormViewModel(parameters.get(), get(), get(), get()) }
        viewModel { parameters -> CameraViewModel(parameters.get(), get(), get(), get()) }
        viewModel { parameters -> TimelineViewModel(parameters.get(), get(), get()) }
        viewModel { parameters -> EventViewModel(parameters.get(), get(), get()) }
    }

@OptIn(ExperimentalTime::class)
private fun epochSeconds(): Long = Clock.System.now().epochSeconds

/** Starts Koin. Call it once, when the app launches. */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(platformModule, sharedModule)
    }
