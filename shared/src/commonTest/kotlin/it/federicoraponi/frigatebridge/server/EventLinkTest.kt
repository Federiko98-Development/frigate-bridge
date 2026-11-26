package it.federicoraponi.frigatebridge.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventLinkTest {
    private val home = Server("home", "Home", "https://home.example.com:8971", "https://frigate.example.com")
    private val office = Server("office", "Office", "https://office.example.com")
    private val saved = RegistryState(listOf(home, office), defaultServerId = "office")

    @Test
    fun readsTheMomentAndTheServer() {
        val link =
            parseEventLink("frigatebridge://event?id=1718000000.123-abc&server=https%3A%2F%2Ffrigate.example.com")

        assertEquals(EventLink("1718000000.123-abc", "https://frigate.example.com"), link)
    }

    @Test
    fun refusesOtherLinksAndIdentifiersThatCouldChangeTheRequest() {
        assertNull(parseEventLink("https://event?id=abc"))
        assertNull(parseEventLink("frigatebridge://camera?id=abc"))
        assertNull(parseEventLink("frigatebridge://event"))
        assertNull(parseEventLink("frigatebridge://event?id=.."))
        assertNull(parseEventLink("frigatebridge://event?id=a%2F..%2Fconfig"))
    }

    @Test
    fun findsOnlySavedServersByHostAndPort() {
        assertEquals(home, saved.serverFor(EventLink("a", "https://home.example.com:8971")))
        assertEquals(home, saved.serverFor(EventLink("a", "frigate.example.com")))
        assertNull(saved.serverFor(EventLink("a", "https://home.example.com")))
        assertNull(saved.serverFor(EventLink("a", "https://elsewhere.example.com")))
    }

    @Test
    fun withoutAnAddressMeansTheDefaultOrTheOnlyServer() {
        assertEquals(office, saved.serverFor(EventLink("a")))
        assertEquals(home, RegistryState(listOf(home)).serverFor(EventLink("a")))
        assertNull(RegistryState(listOf(home, office)).serverFor(EventLink("a")))
    }
}
