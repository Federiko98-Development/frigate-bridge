package it.federicoraponi.watchdeck.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import it.federicoraponi.watchdeck.auth.OidcClient
import it.federicoraponi.watchdeck.auth.OidcSignIn
import it.federicoraponi.watchdeck.auth.SignInBrowser
import it.federicoraponi.watchdeck.frigate.FakeFrigate
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.network.NetworkStatus
import it.federicoraponi.watchdeck.settings.AppSettings
import it.federicoraponi.watchdeck.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class MemoryServerStore(
    var saved: SavedServers = SavedServers(),
) : ServerStore {
    override suspend fun read(): SavedServers = saved

    override suspend fun write(saved: SavedServers) {
        this.saved = saved
    }
}

class FakeSecretStore : SecretStore {
    val values = mutableMapOf<String, String>()

    override suspend fun read(key: String): String? = values[key]

    override suspend fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class MemorySettingsStore : SettingsStore {
    private val values = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = values

    override suspend fun update(change: (AppSettings) -> AppSettings) {
        values.value = change(values.value)
    }
}

/** Several fake Frigate servers, told apart by host name. A host that is not listed does not answer. */
class FakeNetwork(
    val hosts: MutableMap<String, FakeFrigate> = mutableMapOf(),
) : HttpClientFactory {
    /** Hosts presenting a self-signed certificate, with its fingerprint. */
    val selfSigned = mutableMapOf<String, String>()

    override fun create(acceptedCertificateSha256: String?): HttpClient =
        HttpClient(
            MockEngine { request ->
                val host = request.url.host
                val fingerprint = selfSigned[host]
                if (fingerprint != null && fingerprint != acceptedCertificateSha256) {
                    throw FakeCertificateRejection(fingerprint)
                }
                val server = hosts[host] ?: throw RuntimeException("no route to $host")
                with(server) { handle(request) }
            },
        )
}

private class FakeCertificateRejection(
    override val sha256Fingerprint: String,
) : RuntimeException(),
    it.federicoraponi.watchdeck.frigate.CertificateRejection

/** A connection that the test drives. */
class FakeNetworkMonitor : NetworkMonitor {
    override val status = MutableStateFlow(NetworkStatus(isOnline = true, id = 1))
}

/** The time of every test of the sign-in, in seconds since the epoch. */
const val NOW = 1_000_000L

/** A browser that does what the test says once the app has opened the provider's page in it. */
class FakeBrowser : SignInBrowser {
    override val redirectUri = "com.example.app:/oauth2redirect"
    val opened = mutableListOf<String>()
    var canOpen = true
    var onOpen: (String) -> Unit = {}

    override fun open(url: String): Boolean {
        if (!canOpen) return false
        opened += url
        onOpen(url)
        return true
    }
}

/** The registry with a home and an office server, as in the app's main scenario. */
class Fixture {
    val home = FakeFrigate()
    val office =
        FakeFrigate(
            username = "boss",
            password = "office-password",
            config = """{"cameras": {"front_door": {}, "warehouse": {}}}""",
            allowedCameras = listOf("front_door", "warehouse"),
        )
    val network = FakeNetwork(mutableMapOf("home.example.com" to home, "office.example.com" to office))
    val store = MemoryServerStore()
    val secrets = FakeSecretStore()
    val browser = FakeBrowser()
    val oidc = OidcClient(lazy { network.create(null) }) { NOW }
    val signIn = OidcSignIn(oidc, browser)
    val sessions = ServerSessionFactory(network, secrets, oidc) { NOW }
    val registry = ServerRegistry(store, secrets, sessions)
    val settings = MemorySettingsStore()
    val monitor = FakeNetworkMonitor()

    val homeServer = Server("home", "Home", "https://home.example.com", username = "viewer")
    val officeServer = Server("office", "Office", "https://office.example.com", username = "boss")

    suspend fun saveBoth() {
        registry.save(homeServer, home.password)
        registry.save(officeServer, office.password)
    }
}
