package it.federicoraponi.watchdeck.signin

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import it.federicoraponi.watchdeck.auth.SignInBrowser
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.bind
import org.koin.dsl.module
import java.lang.ref.WeakReference

/**
 * The provider's page in the phone's browser, shown over the app as a Custom Tab: the app never sees what is typed
 * there, and a session already open in the browser is used.
 *
 * The tab is opened by the activity in front, so that it belongs to the app's task: coming back to the app, by the
 * redirect or with the back gesture, closes it.
 */
class CustomTabsBrowser(
    private val application: Application,
) : SignInBrowser {
    private var front = WeakReference<Activity>(null)

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    front = WeakReference(activity)
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (front.get() === activity) front.clear()
                }

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit
            },
        )
    }

    // A scheme of the app's own, named after its package (RFC 8252). The manifest declares the same one.
    override val redirectUri = "${application.packageName}:/oauth2redirect"

    override fun open(url: String): Boolean =
        try {
            val tab = CustomTabsIntent.Builder().build()
            val activity = front.get()
            if (activity == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            tab.launchUrl(activity ?: application, url.toUri())
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}

val signInModule =
    module {
        // From the start, to know which activity is in front when a sign-in opens the browser.
        single(createdAtStart = true) { CustomTabsBrowser(androidApplication()) } bind SignInBrowser::class
    }
