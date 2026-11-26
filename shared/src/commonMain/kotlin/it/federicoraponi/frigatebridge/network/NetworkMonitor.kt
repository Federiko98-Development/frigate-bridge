package it.federicoraponi.frigatebridge.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Whether the phone is connected to a network, and to which one. */
data class NetworkStatus(
    val isOnline: Boolean,
    /** Changes when the phone moves to another network, e.g. from Wi-Fi to mobile data. */
    val id: Long = 0,
)

/** Follows the phone's connection, provided by each platform. */
interface NetworkMonitor {
    val status: Flow<NetworkStatus>
}

/**
 * Counts the connections: emits once at the start, then every time the phone comes back online or moves to
 * another network. Losing the network emits nothing, as there is nothing to read then.
 */
internal fun Flow<NetworkStatus>.connections(): Flow<Int> =
    flow {
        var previous: NetworkStatus? = null
        var count = 0
        collect { status ->
            if (previous == null || (status.isOnline && status != previous)) emit(++count)
            previous = status
        }
    }
