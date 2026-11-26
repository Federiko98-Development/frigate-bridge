package it.federicoraponi.frigatebridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.federicoraponi.frigatebridge.startup.StartupViewModel
import org.koin.mp.KoinPlatform

/**
 * ViewModel lifecycle on iOS: each Swift store owns one of these and calls
 * [clear] when it is destroyed. Add one function per ViewModel.
 */
class SharedViewModelStore : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun startupViewModel(): StartupViewModel = resolve()

    fun clear() {
        viewModelStore.clear()
    }

    private inline fun <reified T : ViewModel> resolve(): T {
        val factory = viewModelFactory { initializer<T> { KoinPlatform.getKoin().get<T>() } }
        return ViewModelProvider.create(this, factory)[T::class]
    }
}
