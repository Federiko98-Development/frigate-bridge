package it.federicoraponi.watchdeck

import android.app.Application
import it.federicoraponi.watchdeck.di.initKoin
import it.federicoraponi.watchdeck.video.videoModule
import org.koin.android.ext.koin.androidContext

class WatchdeckApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@WatchdeckApplication)
            modules(videoModule)
        }
    }
}
