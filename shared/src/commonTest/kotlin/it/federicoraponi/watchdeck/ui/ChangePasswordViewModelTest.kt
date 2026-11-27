package it.federicoraponi.watchdeck.ui

import io.ktor.http.HttpMethod
import it.federicoraponi.watchdeck.server.Fixture
import it.federicoraponi.watchdeck.server.PasswordProblem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangePasswordViewModelTest : ViewModelTest() {
    private val fixture = Fixture()
    private val home = fixture.home
    private val oldPassword = home.password
    private val newPassword = "a-new-made-up-password"

    private suspend fun open(): ChangePasswordViewModel {
        fixture.saveBoth()
        fixture.registry.activate("home")
        val viewModel = ChangePasswordViewModel(fixture.registry)
        viewModel.state.first { it.username.isNotEmpty() }
        return viewModel
    }

    private suspend fun ChangePasswordViewModel.finished() = state.first { !it.isSaving }

    private fun passwordChanges() =
        home.requests.count { it.method == HttpMethod.Put && it.url.encodedPath.endsWith("/password") }

    @Test
    fun logsInWithTheNewPasswordAfterwards() =
        runTest {
            val viewModel = open()
            val logins = home.loginCount

            viewModel.save(oldPassword, newPassword, newPassword)

            assertTrue(viewModel.finished().isDone)
            assertEquals(logins, home.loginCount)
            home.expireSession()
            checkNotNull(fixture.registry.session("home")).exports()
            assertEquals(logins + 1, home.loginCount)
            assertContains(home.lastLoginBody, newPassword)
        }

    @Test
    fun reportsAWrongCurrentPasswordWithoutANewLogin() =
        runTest {
            home.users["viewer"] = "viewer"
            val viewModel = open()
            val logins = home.loginCount

            viewModel.save("not-the-current-password", newPassword, newPassword)

            assertEquals(AdminFailure.WrongCurrentPassword, viewModel.finished().failure)
            assertEquals(logins, home.loginCount)
            assertEquals(1, passwordChanges())
        }

    @Test
    fun checksAnAdministratorsCurrentPasswordItself() =
        runTest {
            val viewModel = open()

            viewModel.save("not-the-current-password", newPassword, newPassword)

            assertEquals(AdminFailure.WrongCurrentPassword, viewModel.finished().failure)
            assertEquals(0, passwordChanges())
        }

    @Test
    fun checksTheFieldsFirst() =
        runTest {
            val viewModel = open()

            viewModel.save("", newPassword, newPassword)
            assertEquals(PasswordProblem.CurrentMissing, viewModel.state.value.password)
            viewModel.save(oldPassword, "short", "short")
            assertEquals(PasswordProblem.TooShort, viewModel.state.value.password)
            viewModel.save(oldPassword, newPassword, "something-else-entirely")
            assertEquals(PasswordProblem.Mismatch, viewModel.state.value.password)
            assertEquals(0, passwordChanges())
        }

    @Test
    fun leavesWhenTheServerSignsNobodyIn() =
        runTest {
            home.authEnabled = false
            fixture.saveBoth()
            fixture.registry.activate("home")

            ChangePasswordViewModel(fixture.registry).state.first { it.isGone }
        }
}
