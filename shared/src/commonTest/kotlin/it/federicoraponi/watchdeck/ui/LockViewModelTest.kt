package it.federicoraponi.watchdeck.ui

import it.federicoraponi.watchdeck.server.MemorySettingsStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LockViewModelTest : ViewModelTest() {
    private val settings = MemorySettingsStore()

    @Test
    fun doesNotLockWhoEnablesItButLocksAfterTimeOutOfSight() =
        runTest {
            val viewModel = LockViewModel(settings)
            assertEquals(false, viewModel.state.value.isLocked)

            viewModel.asking(LockPurpose.Enable)
            viewModel.authenticated()
            assertEquals(LockState(isLocked = false, isEnabled = true), viewModel.state.value)

            viewModel.appHidden(1_000)
            viewModel.appShown(1_000 + LOCK_AFTER_MILLIS - 1)
            assertEquals(false, viewModel.state.value.isLocked)
            viewModel.appHidden(50_000)
            viewModel.appShown(50_000 + LOCK_AFTER_MILLIS)
            assertEquals(true, viewModel.state.value.isLocked)
        }

    @Test
    fun startsLockedAndOpensOnlyAfterAuthentication() =
        runTest {
            settings.update { it.copy(appLock = true) }
            val viewModel = LockViewModel(settings)
            assertEquals(true, viewModel.state.value.isLocked)

            viewModel.asking(LockPurpose.Unlock)
            viewModel.authenticationFailed("Cancelled")
            assertEquals(true, viewModel.state.value.isLocked)

            viewModel.asking(LockPurpose.Unlock)
            viewModel.authenticated()
            assertEquals(false, viewModel.state.value.isLocked)
        }
}
