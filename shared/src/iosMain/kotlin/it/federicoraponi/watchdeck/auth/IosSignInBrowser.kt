package it.federicoraponi.watchdeck.auth

/**
 * Not implemented yet: it needs `ASWebAuthenticationSession` and the address of the app's own scheme. Until then
 * signing in through a provider says that no browser can be opened.
 */
class IosSignInBrowser : SignInBrowser {
    override val redirectUri = "it.federicoraponi.watchdeck:/oauth2redirect"

    override fun open(url: String): Boolean = false
}
