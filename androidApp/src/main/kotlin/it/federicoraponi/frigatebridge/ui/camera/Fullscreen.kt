package it.federicoraponi.frigatebridge.ui.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/** While this is on screen the phone is held sideways and the system bars are hidden. */
@Composable
fun LandscapeFullscreen() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // The pages are made for portrait; only full screen turns the phone sideways.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
