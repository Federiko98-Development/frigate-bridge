package it.federicoraponi.frigatebridge.di

import it.federicoraponi.frigatebridge.getPlatform
import it.federicoraponi.frigatebridge.startup.StartupViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val sharedModule =
    module {
        single { getPlatform() }
        viewModelOf(::StartupViewModel)
    }

/** Starts Koin. Call it once, when the app launches. */
fun initKoin(appDeclaration: KoinAppDeclaration = {}): KoinApplication =
    startKoin {
        appDeclaration()
        modules(sharedModule)
    }
