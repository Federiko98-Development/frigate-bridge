package it.federicoraponi.frigatebridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.federicoraponi.frigatebridge.camera.CameraRef
import it.federicoraponi.frigatebridge.ui.CameraArgs
import it.federicoraponi.frigatebridge.ui.CameraViewModel
import it.federicoraponi.frigatebridge.ui.CamerasViewModel
import it.federicoraponi.frigatebridge.ui.EventArgs
import it.federicoraponi.frigatebridge.ui.EventViewModel
import it.federicoraponi.frigatebridge.ui.EventsViewModel
import it.federicoraponi.frigatebridge.ui.ExportArgs
import it.federicoraponi.frigatebridge.ui.ExportViewModel
import it.federicoraponi.frigatebridge.ui.ExportsArgs
import it.federicoraponi.frigatebridge.ui.ExportsViewModel
import it.federicoraponi.frigatebridge.ui.LockViewModel
import it.federicoraponi.frigatebridge.ui.ServerFormArgs
import it.federicoraponi.frigatebridge.ui.ServerFormViewModel
import it.federicoraponi.frigatebridge.ui.ServerListViewModel
import it.federicoraponi.frigatebridge.ui.ServerStatusViewModel
import it.federicoraponi.frigatebridge.ui.SettingsViewModel
import it.federicoraponi.frigatebridge.ui.StartViewModel
import it.federicoraponi.frigatebridge.ui.TimelineArgs
import it.federicoraponi.frigatebridge.ui.TimelineViewModel
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

    fun clear() {
        viewModelStore.clear()
    }

    private inline fun <reified T : ViewModel> resolve(crossinline create: () -> T): T {
        val factory = viewModelFactory { initializer<T> { create() } }
        return ViewModelProvider.create(this, factory)[T::class]
    }
}
