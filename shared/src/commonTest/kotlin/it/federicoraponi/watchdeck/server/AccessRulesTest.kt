package it.federicoraponi.watchdeck.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessRulesTest {
    private fun username(name: String) = AccessRules.usernameProblem(name, listOf("admin", "guest"))

    private fun role(name: String) = AccessRules.roleNameProblem(name, listOf("admin", "viewer", "garden_only"))

    @Test
    fun checksUserNames() {
        assertNull(username("a.b_c"))
        // User names differing in case are different users.
        assertNull(username("Guest"))
        assertNull(username("a".repeat(30)))
        assertEquals(NameProblem.Missing, username(""))
        assertEquals(NameProblem.TooLong, username("a".repeat(31)))
        assertEquals(NameProblem.InvalidCharacters, username("..."))
        assertEquals(NameProblem.InvalidCharacters, username("two words"))
        assertEquals(NameProblem.InvalidCharacters, username("a-b"))
        assertEquals(NameProblem.Reserved, username("anonymous"))
        assertEquals(NameProblem.Taken, username("guest"))
    }

    @Test
    fun checksRoleNames() {
        assertNull(role("porch_2"))
        assertEquals(NameProblem.Missing, role(" "))
        assertEquals(NameProblem.InvalidCharacters, role("a.b"))
        assertEquals(NameProblem.InvalidCharacters, role("GardenOnly"))
        assertEquals(NameProblem.Reserved, role("admin"))
        assertEquals(NameProblem.Reserved, role("Viewer"))
        assertEquals(NameProblem.NeedsLetter, role("123"))
        assertEquals(NameProblem.TooLong, role("a".repeat(21)))
        assertEquals(NameProblem.Taken, role("garden_only"))
    }

    @Test
    fun checksPasswords() {
        assertNull(AccessRules.passwordProblem("a".repeat(12), "a".repeat(12)))
        assertEquals(PasswordProblem.TooShort, AccessRules.passwordProblem("a".repeat(11), "a".repeat(11)))
        assertEquals(PasswordProblem.Mismatch, AccessRules.passwordProblem("a".repeat(12), "b".repeat(12)))
    }
}
