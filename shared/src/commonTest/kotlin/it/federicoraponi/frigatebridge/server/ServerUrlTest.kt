package it.federicoraponi.frigatebridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerUrlTest {
    @Test
    fun addsHttpsWhenTheSchemeIsMissing() {
        assertEquals("https://frigate.example.com", normalizeServerUrl("  frigate.example.com "))
        assertEquals("https://192.168.1.20:8971", normalizeServerUrl("192.168.1.20:8971"))
    }

    @Test
    fun keepsHttpPortsAndSubPaths() {
        assertEquals("http://192.168.1.20:5000", normalizeServerUrl("http://192.168.1.20:5000/"))
        assertEquals("https://example.com/frigate", normalizeServerUrl("https://example.com/frigate/api/"))
    }

    @Test
    fun rejectsWhatIsNotAServerAddress() {
        listOf(
            "",
            "   ",
            "ftp://example.com",
            "https://",
            "my server",
            "https://user:secret@example.com",
            "https://example.com/?a=1",
            "https://:8971",
        ).forEach { assertNull(normalizeServerUrl(it), it) }
    }
}
