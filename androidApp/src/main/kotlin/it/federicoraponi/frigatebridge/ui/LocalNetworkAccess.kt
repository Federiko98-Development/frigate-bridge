package it.federicoraponi.frigatebridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The permission that Android 17 and later require to reach devices on the local network, such as a Frigate
 * server at home. Without it connections simply time out.
 */
@Stable
class LocalNetworkAccess(
    isGranted: () -> Boolean,
    private val launch: () -> Unit,
) {
    private val check = isGranted
    var isGranted by mutableStateOf(isGranted())
        private set

    internal var afterRequest: ((Boolean) -> Unit)? = null

    fun refresh() {
        isGranted = check()
    }

    /** Asks for the permission and then calls [onResult], also when Android answers without showing a dialog. */
    fun request(onResult: (Boolean) -> Unit) {
        if (check()) {
            onResult(true)
        } else {
            afterRequest = onResult
            launch()
        }
    }
}

@Composable
fun rememberLocalNetworkAccess(): LocalNetworkAccess {
    val context = LocalContext.current
    val currentContext by rememberUpdatedState(context)
    var access: LocalNetworkAccess? = null
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            access?.refresh()
            access?.afterRequest?.invoke(granted)
            access?.afterRequest = null
        }
    val remembered =
        remember(launcher) {
            LocalNetworkAccess(
                isGranted = { currentContext.hasLocalNetworkAccess() },
                launch = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                        launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                    }
                },
            )
        }
    access = remembered
    // The user may have changed the permission in the system settings.
    LifecycleResumeEffect(remembered) {
        remembered.refresh()
        onPauseOrDispose {}
    }
    return remembered
}

private fun Context.hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
        checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

/** Requests the permission, or opens the app's settings when Android no longer shows the request. */
@Composable
fun rememberAllowLocalNetwork(
    access: LocalNetworkAccess,
    onAllowed: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    return {
        access.request { granted ->
            if (granted) {
                onAllowed()
            } else if (!context.canAskForLocalNetworkAgain()) {
                context.openAppSettings()
            }
        }
    }
}

private fun Context.canAskForLocalNetworkAgain(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return false
    var current: Context? = this
    while (current is ContextWrapper && current !is Activity) current = current.baseContext
    val activity = current as? Activity ?: return false
    return activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_LOCAL_NETWORK)
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
