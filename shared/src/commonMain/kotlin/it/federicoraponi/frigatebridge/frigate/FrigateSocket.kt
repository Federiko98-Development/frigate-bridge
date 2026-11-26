package it.federicoraponi.frigatebridge.frigate

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Frigate's WebSocket, used for the commands that have no HTTP endpoint, such as moving a camera. */
class FrigateSocket internal constructor(
    private val session: DefaultClientWebSocketSession,
) {
    init {
        // Frigate also sends every event on this socket; unread messages would end up blocking it.
        session.launch { session.incoming.consumeEach {} }
    }

    val isOpen: Boolean get() = session.isActive

    suspend fun publish(
        topic: String,
        payload: String,
    ) {
        val message =
            buildJsonObject {
                put("topic", topic)
                put("payload", payload)
                put("retain", false)
            }
        session.send(Frame.Text(message.toString()))
    }

    /** Drops the connection without waiting. */
    fun cancel() {
        session.cancel()
    }

    suspend fun close() {
        try {
            session.close()
        } finally {
            session.cancel()
        }
    }
}
