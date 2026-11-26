package it.federicoraponi.frigatebridge.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Placeholder until iOS follows the connection with `NWPathMonitor`: the phone is taken to be online. */
internal class AssumedOnlineNetworkMonitor : NetworkMonitor {
    override val status: Flow<NetworkStatus> = flowOf(NetworkStatus(isOnline = true))
}
