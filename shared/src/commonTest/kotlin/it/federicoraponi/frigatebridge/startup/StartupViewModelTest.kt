package it.federicoraponi.frigatebridge.startup

import app.cash.turbine.test
import it.federicoraponi.frigatebridge.Platform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakePlatform : Platform {
    override val name = "Test OS"
}

class StartupViewModelTest {
    @Test
    fun startsWithPlatformNameAndNoTaps() =
        runTest {
            StartupViewModel(FakePlatform()).state.test {
                assertEquals(StartupState(platformName = "Test OS", tapCount = 0), awaitItem())
            }
        }

    @Test
    fun everyTapPublishesANewState() =
        runTest {
            val viewModel = StartupViewModel(FakePlatform())

            viewModel.state.test {
                awaitItem()
                viewModel.onTap()
                assertEquals(1, awaitItem().tapCount)
                viewModel.onTap()
                assertEquals(2, awaitItem().tapCount)
            }
        }
}
