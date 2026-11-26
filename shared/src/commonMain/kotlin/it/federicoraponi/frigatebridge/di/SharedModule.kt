package it.federicoraponi.frigatebridge.di

import it.federicoraponi.frigatebridge.server.DataStoreServerStore
import it.federicoraponi.frigatebridge.server.ExportDownloads
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSessionFactory
import it.federicoraponi.frigatebridge.server.ServerStore
import it.federicoraponi.frigatebridge.settings.DataStoreSettingsStore
import it.federicoraponi.frigatebridge.settings.SettingsStore
import it.federicoraponi.frigatebridge.ui.CameraViewModel
import it.federicoraponi.frigatebridge.ui.CamerasViewModel
import it.federicoraponi.frigatebridge.ui.EventViewModel
import it.federicoraponi.frigatebridge.ui.EventsViewModel
import it.federicoraponi.frigatebridge.ui.ExportViewModel
import it.federicoraponi.frigatebridge.ui.ExportsViewModel
import it.federicoraponi.frigatebridge.ui.LockViewModel
import it.federicoraponi.frigatebridge.ui.ServerFormViewModel
import it.federicoraponi.frigatebridge.ui.ServerListViewModel
import it.federicoraponi.frigatebridge.ui.ServerStatusViewModel
import it.federicoraponi.frigatebridge.ui.SettingsViewModel
import it.federicoraponi.frigatebridge.ui.StartViewModel
import it.federicoraponi.frigatebridge.ui.TimelineViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val sharedModule =
    module {
        single<ServerStore> { DataStoreServerStore(get()) }
        single<SettingsStore> { DataStoreSettingsStore(get()) }
        single { ServerSessionFactory(get(), get()) }
        single { ServerRegistry(get(), get(), get()) }
        single { ExportDownloads(get()) }
        viewModelOf(::StartViewModel)
        viewModelOf(::ServerListViewModel)
        viewModelOf(::CamerasViewModel)
        viewModelOf(::SettingsViewModel)
        viewModelOf(::ServerStatusViewModel)
        viewModel { parameters -> ExportsViewModel(parameters.get(), get()) }
        viewModel { parameters -> ExportViewModel(parameters.get(), get()) }
        viewModelOf(::LockViewModel)
        viewModelOf(::EventsViewModel)
        viewModel { parameters -> ServerFormViewModel(parameters.get(), get(), get()) }
        viewModel { parameters -> CameraViewModel(parameters.get(), get(), get(), get()) }
        viewModel { parameters -> TimelineViewModel(parameters.get(), get(), get()) }
        viewModel { parameters -> EventViewModel(parameters.get(), get(), get()) }
    }

/** Starts Koin. Call it once, when the app launches. */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(platformModule, sharedModule)
    }
