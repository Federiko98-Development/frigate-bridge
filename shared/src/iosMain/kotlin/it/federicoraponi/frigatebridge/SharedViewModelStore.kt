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
import it.federicoraponi.frigatebridge.ui.ServerFormArgs
import it.federicoraponi.frigatebridge.ui.ServerFormViewModel
import it.federicoraponi.frigatebridge.ui.ServerListViewModel
import it.federicoraponi.frigatebridge.ui.StartViewModel
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

    fun serverFormViewModel(serverId: String?): ServerFormViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(ServerFormArgs(serverId)) } }

    fun cameraViewModel(
        serverId: String,
        cameraName: String,
    ): CameraViewModel =
        resolve { KoinPlatform.getKoin().get { parametersOf(CameraArgs(CameraRef(serverId, cameraName))) } }

    fun clear() {
        viewModelStore.clear()
    }

    private inline fun <reified T : ViewModel> resolve(crossinline create: () -> T): T {
        val factory = viewModelFactory { initializer<T> { create() } }
        return ViewModelProvider.create(this, factory)[T::class]
    }
}
