package it.federicoraponi.frigatebridge.di

import it.federicoraponi.frigatebridge.Platform
import it.federicoraponi.frigatebridge.startup.StartupViewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedModuleTest {
    @Test
    fun resolvesTheStartupViewModel() {
        val fakePlatform =
            module {
                single<Platform> {
                    object : Platform {
                        override val name = "Test OS"
                    }
                }
            }
        val application = koinApplication { modules(sharedModule, fakePlatform) }

        val viewModel = application.koin.get<StartupViewModel>()

        assertEquals("Test OS", viewModel.state.value.platformName)
        application.close()
    }
}
