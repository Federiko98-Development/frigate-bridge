package it.federicoraponi.watchdeck.video

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/** The WebRTC objects shared by all connections of the app. */
class WebRtcEngine(
    context: Context,
) {
    val egl: EglBase
    val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(
                    context.applicationContext,
                ).createInitializationOptions(),
        )
        egl = EglBase.create()
        // Camera audio is media, not a phone call: loudspeaker and media volume.
        val audio =
            JavaAudioDeviceModule
                .builder(context.applicationContext)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                ).createAudioDeviceModule()
        factory =
            PeerConnectionFactory
                .builder()
                .setAudioDeviceModule(audio)
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .createPeerConnectionFactory()
        // The factory keeps its own reference to the audio module.
        audio.release()
    }
}
