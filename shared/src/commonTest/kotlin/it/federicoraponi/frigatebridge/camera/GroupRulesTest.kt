package it.federicoraponi.frigatebridge.camera

import it.federicoraponi.frigatebridge.server.NameProblem
import it.federicoraponi.frigatebridge.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupRulesTest {
    private fun problem(
        name: String,
        original: String? = null,
    ) = GroupRules.nameProblem(GroupRules.normalize(name), listOf("Outside", "Indoor"), original)

    @Test
    fun normalizesNamesAsTheWebInterfaceDoes() {
        assertEquals("Back_yard", GroupRules.normalize("  Back yard "))
        assertEquals("Back_yard", GroupRules.normalize("Back \t  yard"))
    }

    @Test
    fun checksNewNames() {
        assertNull(problem("Back yard"))
        assertNull(problem("Zoë-2"))
        assertEquals(NameProblem.Missing, problem("   "))
        assertEquals(NameProblem.TooShort, problem("a"))
        assertEquals(NameProblem.InvalidCharacters, problem("a.b"))
        assertEquals(NameProblem.InvalidCharacters, problem("a&b"))
        assertEquals(NameProblem.NeedsLetter, problem("2024"))
        assertEquals(NameProblem.Reserved, problem("default"))
        assertEquals(NameProblem.Reserved, problem("Default"))
        assertEquals(NameProblem.Taken, problem("Indoor"))
    }

    @Test
    fun aGroupMayKeepItsOwnNameButNotTakeAnother() {
        assertNull(problem("Outside", original = "Outside"))
        assertNull(GroupRules.nameProblem("Made.elsewhere", listOf("Made.elsewhere"), "Made.elsewhere"))
        assertEquals(NameProblem.Taken, problem("Indoor", original = "Outside"))
        assertEquals(NameProblem.NeedsLetter, GroupRules.nameProblem("2024", listOf("2024"), "2024"))
    }

    @Test
    fun theSwitchedOffCamerasFollowARenamedGroup() {
        val settings =
            AppSettings(hiddenCameras = mapOf("home/Outside" to setOf("garden"), "office/Outside" to setOf("hall")))

        assertEquals(
            mapOf("home/Yard" to setOf("garden"), "office/Outside" to setOf("hall")),
            settings.renamingGroup("home", "Outside", "Yard").hiddenCameras,
        )
        assertEquals(settings.hiddenCameras, settings.renamingGroup("home", "Outside", "Outside").hiddenCameras)
    }

    @Test
    fun aDeletedGroupIsForgotten() {
        val settings =
            AppSettings(hiddenCameras = mapOf("home/Outside" to setOf("garden"), "office/Outside" to setOf("hall")))

        assertEquals(
            mapOf("office/Outside" to setOf("hall")),
            settings.forgettingGroup("home", "Outside").hiddenCameras,
        )
    }
}
