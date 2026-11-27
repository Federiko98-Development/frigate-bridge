package it.federicoraponi.watchdeck.video

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val videoModule =
    module {
        // Created only when a live view is first opened: it loads the native WebRTC library.
        single { WebRtcEngine(androidContext()) }
    }
