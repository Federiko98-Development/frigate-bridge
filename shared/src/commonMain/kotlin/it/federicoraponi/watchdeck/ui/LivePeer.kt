package it.federicoraponi.watchdeck.ui

/** The receiving end of a WebRTC connection, provided by each platform's WebRTC library. */
interface LivePeer {
    /** An offer to receive video and audio. */
    suspend fun createOffer(): String

    suspend fun acceptAnswer(sdp: String)
}
