package it.federicoraponi.frigatebridge

import android.app.Application
import it.federicoraponi.frigatebridge.di.initKoin
import it.federicoraponi.frigatebridge.video.videoModule
import org.koin.android.ext.koin.androidContext

class FrigateBridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@FrigateBridgeApplication)
            modules(videoModule)
        }
    }
}
