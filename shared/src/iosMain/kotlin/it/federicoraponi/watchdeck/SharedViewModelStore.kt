package it.federicoraponi.watchdeck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.federicoraponi.watchdeck.camera.CameraRef
import it.federicoraponi.watchdeck.ui.AccessViewModel
import it.federicoraponi.watchdeck.ui.CameraArgs
import it.federicoraponi.watchdeck.ui.CameraGroupEditorArgs
import it.federicoraponi.watchdeck.ui.CameraGroupEditorViewModel
import it.federicoraponi.watchdeck.ui.CameraGroupsViewModel
import it.federicoraponi.watchdeck.ui.CameraViewModel
import it.federicoraponi.watchdeck.ui.CamerasViewModel
import it.federicoraponi.watchdeck.ui.ChangePasswordViewModel
import it.federicoraponi.watchdeck.ui.EventArgs
import it.federicoraponi.watchdeck.ui.EventViewModel
import it.federicoraponi.watchdeck.ui.EventsViewModel
import it.federicoraponi.watchdeck.ui.ExportArgs
import it.federicoraponi.watchdeck.ui.ExportViewModel
import it.federicoraponi.watchdeck.ui.ExportsArgs
import it.federicoraponi.watchdeck.ui.ExportsViewModel
import it.federicoraponi.watchdeck.ui.LockViewModel
import it.federicoraponi.watchdeck.ui.ServerFormArgs
import it.federicoraponi.watchdeck.ui.ServerFormViewModel
import it.federicoraponi.watchdeck.ui.ServerListViewModel
import it.federicoraponi.watchdeck.ui.ServerStatusViewModel
import it.federicoraponi.watchdeck.ui.SettingsViewModel
import it.federicoraponi.watchdeck.ui.StartViewModel
import it.federicoraponi.watchdeck.ui.TimelineArgs
import it.federicoraponi.watchdeck.ui.TimelineViewModel
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * ViewModel lifecycle on iOS: each Swift store owns one of these and calls
 * [clear] when it is destroyed. Add one function per ViewModel.
 */
class SharedViewModelStore : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun startViewModel(): StartViewModel = resolve { KoinPlatform.getKoin().get() }

    fun serverListViewModel(): ServerListViewModel = resolve { KoinPlatform.getKoin().get() }

    fun camerasViewModel(): CamerasViewModel = resolve { KoinPlatform.getKoin().get() }

    fun settingsViewModel(): SettingsViewModel = resolve { KoinPlatform.getKoin().get() }

    fun lockViewModel(): LockViewModel = resolve { KoinPlatform.getKoin().get() }

    fun eventsViewModel(): EventsViewModel = resolve { KoinPlatform.getKoin().get() }

    fun serverFormViewModel(serverId: String?): ServerFormViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(ServerFormArgs(serverId)) } }

    fun cameraViewModel(
        serverId: String,
        cameraName: String,
    ): CameraViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(CameraArgs(CameraRef(serverId, cameraName))) } }

    fun timelineViewModel(
        serverId: String,
        cameraName: String,
    ): TimelineViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(TimelineArgs(CameraRef(serverId, cameraName))) } }

    fun eventViewModel(
        serverId: String,
        reviewId: String,
    ): EventViewModel = resolve { KoinPlatform.getKoin().get { parametersOf(EventArgs(serverId, reviewId)) } }

    fun serverStatusViewModel(): ServerStatusViewModel = resolve { KoinPlatform.getKoin().get() }

    fun exportsViewModel(savedOnly: Boolean): ExportsViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(ExportsArgs(savedOnly)) } }

    fun exportViewModel(
        serverId: String,
        exportId: String,
    ): ExportViewModel = resolve { KoinPlatform.getKoin().get { parametersOf(ExportArgs(serverId, exportId)) } }

    fun accessViewModel(): AccessViewModel = resolve { KoinPlatform.getKoin().get() }

    fun changePasswordViewModel(): ChangePasswordViewModel = resolve { KoinPlatform.getKoin().get() }

    fun cameraGroupsViewModel(): CameraGroupsViewModel = resolve { KoinPlatform.getKoin().get() }

    fun cameraGroupEditorViewModel(
        serverId: String,
        groupName: String?,
    ): CameraGroupEditorViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(CameraGroupEditorArgs(serverId, groupName)) } }

    fun clear() {
        viewModelStore.clear()
    }

    private inline fun <reified T : ViewModel> resolve(crossinline create: () -> T): T {
        val factory = viewModelFactory { initializer<T> { create() } }
        return ViewModelProvider.create(this, factory)[T::class]
    }
}
