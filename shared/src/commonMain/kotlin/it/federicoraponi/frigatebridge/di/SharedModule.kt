package it.federicoraponi.frigatebridge.di

import it.federicoraponi.frigatebridge.server.DataStoreServerStore
import it.federicoraponi.frigatebridge.server.ServerRegistry
import it.federicoraponi.frigatebridge.server.ServerSessionFactory
import it.federicoraponi.frigatebridge.server.ServerStore
import it.federicoraponi.frigatebridge.ui.CamerasViewModel
import it.federicoraponi.frigatebridge.ui.ServerFormViewModel
import it.federicoraponi.frigatebridge.ui.ServerListViewModel
import it.federicoraponi.frigatebridge.ui.StartViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val sharedModule =
    module {
        single<ServerStore> { DataStoreServerStore(get()) }
        single { ServerSessionFactory(get(), get()) }
        single { ServerRegistry(get(), get(), get()) }
        viewModelOf(::StartViewModel)
        viewModelOf(::ServerListViewModel)
        viewModelOf(::CamerasViewModel)
        viewModel { parameters -> ServerFormViewModel(parameters.get(), get(), get()) }
    }

/** Starts Koin. Call it once, when the app launches. */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(platformModule, sharedModule)
    }
