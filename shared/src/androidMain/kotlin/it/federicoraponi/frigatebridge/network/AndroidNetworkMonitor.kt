package it.federicoraponi.frigatebridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Follows the default network. A network without access to the internet counts as one: Frigate may well be on
 * it. The app needs the `ACCESS_NETWORK_STATE` permission.
 */
internal class AndroidNetworkMonitor(
    context: Context,
) : NetworkMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override val status: Flow<NetworkStatus> =
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(online(network))
                    }

                    override fun onLost(network: Network) {
                        trySend(NetworkStatus(isOnline = false))
                    }
                }
            // Nothing is called back when there is no network to start with.
            trySend(manager.activeNetwork?.let(::online) ?: NetworkStatus(isOnline = false))
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { manager.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()

    private fun online(network: Network) = NetworkStatus(isOnline = true, id = network.networkHandle)
}
