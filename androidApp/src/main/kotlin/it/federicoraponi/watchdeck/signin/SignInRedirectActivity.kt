package it.federicoraponi.watchdeck.signin

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import it.federicoraponi.watchdeck.MainActivity
import it.federicoraponi.watchdeck.R
import it.federicoraponi.watchdeck.auth.OidcSignIn
import org.koin.android.ext.android.inject

/**
 * Where the browser comes back to after a sign-in. It shows nothing: it hands the address to the attempt in
 * progress, which takes it only if it answers it, and returns to the app, closing the browser tab above it.
 */
class SignInRedirectActivity : ComponentActivity() {
    private val signIn: OidcSignIn by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTaken = intent.dataString?.let(signIn::handleRedirect) == true
        // E.g. the system closed the app while the browser was open.
        if (!isTaken) Toast.makeText(this, R.string.sign_in_expired, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
