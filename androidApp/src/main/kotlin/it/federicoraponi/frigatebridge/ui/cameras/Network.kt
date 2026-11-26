package it.federicoraponi.frigatebridge.ui.cameras

import android.content.Context
import android.net.ConnectivityManager

/** Whether the connection in use is one where data counts, such as mobile data. */
fun Context.isOnMeteredNetwork(): Boolean =
    getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
