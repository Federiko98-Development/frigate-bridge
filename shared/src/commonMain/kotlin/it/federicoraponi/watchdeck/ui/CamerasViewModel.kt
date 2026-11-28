package it.federicoraponi.watchdeck.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.federicoraponi.watchdeck.auth.OidcSignIn
import it.federicoraponi.watchdeck.camera.Camera
import it.federicoraponi.watchdeck.camera.CameraFeature
import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.camera.GroupChoice
import it.federicoraponi.watchdeck.camera.groupChoices
import it.federicoraponi.watchdeck.camera.showing
import it.federicoraponi.watchdeck.frigate.FrigateError
import it.federicoraponi.watchdeck.network.NetworkMonitor
import it.federicoraponi.watchdeck.network.connections
import it.federicoraponi.watchdeck.server.ConnectionState
import it.federicoraponi.watchdeck.server.RegistryState
import it.federicoraponi.watchdeck.server.Server
import it.federicoraponi.watchdeck.server.ServerRegistry
import it.federicoraponi.watchdeck.server.ServerSession
import it.federicoraponi.watchdeck.server.ServerSignIn
import it.federicoraponi.watchdeck.settings.AppSettings
import it.federicoraponi.watchdeck.settings.HomeLayout
import it.federicoraponi.watchdeck.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSummary(
    val id: String,
    val name: String,
    val colorIndex: Int,
)

sealed interface CamerasContent {
    data object Loading : CamerasContent

    /** There is no active server any more, e.g. it was just deleted: go back to the list. */
    data object NoServer : CamerasContent

    data class Ready(
        val cameras: List<Camera>,
        /** Set when the server is older than the oldest supported Frigate release. */
        val outdatedVersion: String?,
    ) : CamerasContent

    data class Failed(
        val problem: ConnectionProblem,
        val detail: String? = null,
    ) : CamerasContent
}

data class CamerasState(
    val server: ServerSummary? = null,
    /** All saved servers, for the quick switcher. */
    val servers: List<ServerSummary> = emptyList(),
    val groups: List<String> = emptyList(),
    /** `null` means "all cameras". */
    val selectedGroup: String? = null,
    /** "All cameras" and every group, each with the cameras switched on and off in it. */
    val choices: List<GroupChoice> = emptyList(),
    val content: CamerasContent = CamerasContent.Loading,
    val settings: AppSettings = AppSettings(),
    /** The user asked to read the server again and it has not answered yet. */
    val isRefreshing: Boolean = false,
    /** Grows after every refresh, so that the pictures are loaded again too. */
    val reloads: Int = 0,
    /** The phone has no network: what is on screen stays as it was. */
    val isOffline: Boolean = false,
    /** The server signs users in at an external provider, so signing in again can be offered. */
    val signsInExternally: Boolean = false,
    /** The browser is open: the app waits for the provider to send it back. */
    val isWaitingForBrowser: Boolean = false,
    /** Why the last sign-in in the browser did not work. */
    val signInFailure: SignInFailure? = null,
    /** The camera being held, with its quick actions. */
    val quick: QuickActions? = null,
    /** Whether the signed-in user may change camera settings. */
    val canChangeSettings: Boolean = false,
)

/** What can be done with one camera without opening it. */
data class QuickActions(
    val camera: Camera,
    /** The camera's switches; offered to administrators only. */
    val controls: CameraControls,
    /** Whether it can be hidden from the group on screen: not when it is the only one shown. */
    val canHide: Boolean,
)

data class SignInFailure(
    val problem: ConnectionProblem,
    val detail: String?,
)

/** The cameras of the active server, and only of that one. */
class CamerasViewModel(
    private val registry: ServerRegistry,
    private val settingsStore: SettingsStore,
    private val network: NetworkMonitor,
    private val oidc: OidcSignIn,
) : ViewModel() {
    private val browser = MutableStateFlow(BrowserProgress())

    private val held = MutableStateFlow<HeldCamera?>(null)

    private val selectedGroup = MutableStateFlow<GroupSelection?>(null)

    private val refresh = MutableStateFlow(RefreshProgress())

    private val offline = MutableStateFlow(false)

    // Kept so that the grid is not empty when the user comes back to it.
    private val previews = mutableMapOf<CameraRef, ByteArray>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CamerasState> =
        combine(
            combine(registry.state.flatMapLatest { activeServer(it) }, browser, held) { cameras, signIn, camera ->
                val isHere = cameras.server != null && signIn.serverId == cameras.server.id
                cameras.copy(
                    quick = camera?.let { quickActions(cameras, it) },
                    isWaitingForBrowser = isHere && signIn.isWaiting,
                    signInFailure = signIn.failure.takeIf { isHere },
                )
            },
            settingsStore.settings,
            refresh,
            offline,
        ) { cameras, settings, progress, isOffline ->
            cameras.copy(
                settings = settings,
                isRefreshing = progress.running,
                reloads = progress.done,
                isOffline = isOffline,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CamerasState())

    init {
        viewModelScope.launch { network.status.collect { offline.value = !it.isOnline } }
        viewModelScope.launch {
            registry.load()
            val servers = registry.state.map { it?.activeServerId to it?.revision }.distinctUntilChanged()
            // On a new network the server is read again: it may answer on its other address.
            combine(servers, network.status.connections()) { server, connection -> server to connection }
                // Switching server abandons the request to the previous one.
                .collectLatest { (server, _) -> server.first?.let { connect(it) } }
        }
    }

    private suspend fun connect(serverId: String) {
        val session = registry.session(serverId) ?: return
        // Without a network, reading a connected server again would only replace its cameras with an error.
        val isOnline = network.status.first().isOnline
        if (isOnline || session.state.value !is ConnectionState.Connected) session.refresh()
    }

    /** Signs in again in the browser, for the active server. */
    fun signIn() {
        val server = registry.state.value?.activeServer ?: return
        val method = server.signIn as? ServerSignIn.Oidc ?: return
        if (browser.value.isWaiting) return
        browser.value = BrowserProgress(server.id, isWaiting = true)
        viewModelScope.launch {
            val failure =
                try {
                    registry.signedIn(server.id, oidc.signIn(method.settings))
                    null
                } catch (error: FrigateError) {
                    SignInFailure(error.toProblem(), error.signInDetail())
                }
            browser.value = BrowserProgress(server.id, failure = failure)
        }
    }

    fun cancelSignIn() = oidc.cancel()

    /** Offers the quick actions of [camera]. */
    fun hold(camera: CameraRef) {
        // A change still on its way stays with its camera.
        if (held.value?.isApplying != true) held.value = HeldCamera(camera)
    }

    fun release() {
        held.value = null
    }

    fun setFeature(
        feature: CameraFeature,
        enabled: Boolean,
    ) {
        val controls = state.value.quick?.controls ?: return
        if (feature !in controls.switchable) return
        change { session, ref -> session.setCameraFeature(ref.cameraName, feature, enabled) }
    }

    /** Leaves the held camera with its live view alone, or brings back what was on. */
    fun setLiveOnly(on: Boolean) =
        change(liveOnly = on) { session, ref -> session.switchLiveOnly(ref, on, settingsStore) }

    /** Hides the held camera from the group on screen; holding the group brings it back. */
    fun hideHeld() {
        val current = state.value
        val quick = current.quick?.takeIf { it.canHide } ?: return
        val shown =
            (current.content as? CamerasContent.Ready)
                ?.cameras
                ?.map { it.ref.cameraName }
                ?.toSet()
                ?: return
        release()
        showCameras(current.selectedGroup, shown - quick.camera.ref.cameraName)
    }

    private fun change(
        liveOnly: Boolean? = null,
        apply: suspend (ServerSession, CameraRef) -> Unit,
    ) {
        val target = held.value ?: return
        if (target.isApplying || !state.value.canChangeSettings) return
        val session = registry.session(target.camera.serverId) ?: return
        val applying = target.copy(isApplying = true, wantsLiveOnly = liveOnly, failure = null)
        held.value = applying
        viewModelScope.launch {
            val failure =
                try {
                    apply(session, target.camera)
                    null
                } catch (error: FrigateError) {
                    error.toVideoFailure()
                }
            // Nothing is shown if the panel was closed meanwhile.
            held.compareAndSet(applying, HeldCamera(target.camera, failure = failure))
        }
    }

    private fun quickActions(
        cameras: CamerasState,
        target: HeldCamera,
    ): QuickActions? {
        val ready = cameras.content as? CamerasContent.Ready ?: return null
        val camera = ready.cameras.firstOrNull { it.ref == target.camera } ?: return null
        val controls = camera.controls(cameras.canChangeSettings)
        return QuickActions(
            camera = camera,
            controls =
                controls.copy(
                    isApplying = target.isApplying,
                    isLiveOnly = target.wantsLiveOnly ?: controls.isLiveOnly,
                    failure = target.failure,
                ),
            canHide = ready.cameras.size > 1,
        )
    }

    fun selectGroup(name: String?) {
        val serverId = registry.state.value?.activeServerId ?: return
        selectedGroup.value = name?.let { GroupSelection(serverId, it) }
    }

    fun switchTo(serverId: String) {
        viewModelScope.launch { registry.activate(serverId) }
    }

    /** Switches on, in a group, only the cameras named in [shown], and shows that group. */
    fun showCameras(
        group: String?,
        shown: Set<String>,
    ) {
        val serverId = registry.state.value?.activeServerId ?: return
        val choice = state.value.choices.firstOrNull { it.name == group } ?: return
        selectGroup(group)
        viewModelScope.launch { settingsStore.update { it.showing(serverId, choice, shown) } }
    }

    fun toggleLayout() {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(homeLayout = if (it.homeLayout == HomeLayout.Grid) HomeLayout.List else HomeLayout.Grid)
            }
        }
    }

    /** Reads the active server again: cameras, groups, state and pictures. */
    fun retry() {
        val serverId = registry.state.value?.activeServerId ?: return
        if (refresh.value.running) return
        refresh.update { it.copy(running = true) }
        viewModelScope.launch {
            try {
                // Without a network, reading the server would only replace the cameras with an error.
                visibleRefresh { if (!offline.value) registry.session(serverId)?.refresh() }
                refresh.update { it.copy(done = it.done + 1) }
            } finally {
                refresh.update { it.copy(running = false) }
            }
        }
    }

    /** The last picture loaded for [camera], if any. */
    fun cachedPreview(camera: CameraRef): ByteArray? = previews[camera]

    /** A current low-resolution JPEG of [camera], or `null` if it cannot be read right now. */
    suspend fun loadPreview(camera: CameraRef): ByteArray? {
        if (offline.value) return null
        val session = registry.session(camera.serverId) ?: return null
        return try {
            session.latestFrame(camera.cameraName, PREVIEW_HEIGHT).also { previews[camera] = it }
        } catch (_: FrigateError) {
            // The grid keeps the previous picture; the server's state is reported elsewhere.
            null
        }
    }

    private fun activeServer(current: RegistryState?): Flow<CamerasState> {
        if (current == null) return flowOf(CamerasState())
        val servers = current.servers.map { it.summary() }
        val server = current.activeServer
        val session = server?.let { registry.session(it.id) }
        if (server == null || session == null) {
            return flowOf(CamerasState(servers = servers, content = CamerasContent.NoServer))
        }
        val isExternal = server.signIn is ServerSignIn.Oidc
        val hiddenCameras = settingsStore.settings.map { it.hiddenCameras }.distinctUntilChanged()
        return combine(session.state, selectedGroup, hiddenCameras) { connection, selection, hidden ->
            val summary = server.summary()
            when (connection) {
                ConnectionState.Idle, ConnectionState.Connecting ->
                    CamerasState(summary, servers)
                is ConnectionState.Failed ->
                    CamerasState(
                        server = summary,
                        servers = servers,
                        content =
                            CamerasContent.Failed(connection.error.toProblem(), connection.error.signInDetail()),
                        signsInExternally = isExternal,
                    )
                is ConnectionState.Connected -> {
                    val snapshot = connection.snapshot
                    val groups = snapshot.catalog.groups.map { it.name }
                    // A selection made on another server, or on a group that no longer exists, does not apply.
                    val group = selection?.takeIf { it.serverId == server.id && it.name in groups }?.name
                    val catalog = snapshot.catalog
                    val choices = catalog.groupChoices(server.id, catalog.camerasIn(null), hidden)
                    CamerasState(
                        server = summary,
                        servers = servers,
                        groups = groups,
                        selectedGroup = group,
                        choices = choices,
                        canChangeSettings = catalog.canChangeSettings,
                        content =
                            CamerasContent.Ready(
                                cameras = choices.first { it.name == group }.shown,
                                outdatedVersion = snapshot.version.takeUnless { snapshot.isSupportedVersion },
                            ),
                    )
                }
            }
        }
    }

    private fun Server.summary() = ServerSummary(id, name, colorIndex)

    private data class HeldCamera(
        val camera: CameraRef,
        val isApplying: Boolean = false,
        val wantsLiveOnly: Boolean? = null,
        val failure: VideoFailure? = null,
    )

    private data class BrowserProgress(
        val serverId: String? = null,
        val isWaiting: Boolean = false,
        val failure: SignInFailure? = null,
    )

    private data class RefreshProgress(
        val running: Boolean = false,
        val done: Int = 0,
    )

    private data class GroupSelection(
        val serverId: String,
        val name: String,
    )
}

/** Height in pixels of the pictures in the camera grid. */
private const val PREVIEW_HEIGHT = 270
