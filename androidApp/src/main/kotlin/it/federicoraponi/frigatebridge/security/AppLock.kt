package it.federicoraponi.frigatebridge.security

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.biometric.AuthenticationRequest
import androidx.biometric.AuthenticationResult
import androidx.biometric.AuthenticationResultCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.registerForAuthenticationResult
import it.federicoraponi.frigatebridge.R
import it.federicoraponi.frigatebridge.ui.LockPurpose
import it.federicoraponi.frigatebridge.ui.LockViewModel

/** Whether the phone can tell who is using it: it needs a screen lock, with or without a fingerprint. */
fun Context.canAuthenticateUser(): Boolean =
    BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** Whether the phone asks for something before it opens. When in doubt it does. */
private fun Context.hasScreenLock(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceSecure != false

/**
 * Asks the system for the fingerprint, the face or the phone's code and tells [lock] how it went. Create it
 * while the activity is being created.
 */
class AppLockPrompt(
    private val activity: ComponentActivity,
    private val lock: LockViewModel,
) {
    private val launcher =
        activity.registerForAuthenticationResult(
            object : AuthenticationResultCallback {
                override fun onAuthResult(result: AuthenticationResult) {
                    if (result.isSuccess()) {
                        lock.authenticated()
                    } else {
                        lock.authenticationFailed(result.error()?.errString?.toString())
                    }
                }
            },
        )

    fun ask(purpose: LockPurpose) {
        // Only a phone without any screen lock leaves nothing to ask for, e.g. after it was removed with the app
        // lock on. Any other trouble is left to the prompt, and the app stays locked until it succeeds.
        if (!activity.hasScreenLock()) {
            if (purpose == LockPurpose.Unlock) lock.cannotAuthenticate()
            return
        }
        lock.asking(purpose)
        val title =
            activity.getString(if (purpose == LockPurpose.Unlock) R.string.lock_unlock_title else R.string.lock_confirm)
        launcher.launch(
            AuthenticationRequest.biometricRequest(
                title,
                AuthenticationRequest.Biometric.Fallback.DeviceCredential,
            ) {
                setMinStrength(AuthenticationRequest.Biometric.Strength.Class2)
            },
        )
    }
}
