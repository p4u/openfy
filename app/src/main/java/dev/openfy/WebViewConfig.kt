package dev.openfy

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView

/** DESIGN §2.3: settings shared by the main WebView and popup WebViews. */
object WebViewConfig {
    const val BACKGROUND = 0xFF121212.toInt()

    @SuppressLint("SetJavaScriptEnabled") // The web player is a JavaScript application.
    fun configure(context: Context, webView: WebView, arm: IdentityArm, popup: Boolean) {
        webView.setBackgroundColor(BACKGROUND)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            // Browser default. Only relax if H4 shows NotAllowedError on native-initiated play.
            mediaPlaybackRequiresUserGesture = true
            // Popups (Facebook/Apple login) need window.open; a popup itself does not nest further.
            setSupportMultipleWindows(!popup)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        BrowserIdentity.apply(context, webView.settings, arm)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    fun initCookies() {
        CookieManager.getInstance().setAcceptCookie(true)
    }
}
