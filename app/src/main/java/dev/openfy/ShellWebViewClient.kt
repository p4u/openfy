package dev.openfy

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Navigation policy (DESIGN §2.4) and renderer-crash recovery (DESIGN §2.1).
 * [isPopup] selects the popup variant: it never rebuilds the main WebView and never records the last URL.
 */
class ShellWebViewClient(private val isPopup: Boolean) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Sub-frame navigations are not governed by the policy.
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        return when (val decision = NavigationPolicy.classify(url)) {
            NavDecision.InShell -> false
            is NavDecision.Blocked -> {
                Log.w(TAG, "nav: blocked $url (${decision.reason})")
                true
            }
            NavDecision.External -> {
                Log.i(TAG, "nav: external $url")
                openExternally(view, request.url)
                true
            }
        }
    }

    private fun openExternally(view: WebView, uri: Uri) {
        try {
            view.context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "nav: no activity for $uri", e)
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        Log.i(TAG, "nav: started $url")
        // A new document: whatever the bridge saw before is stale. pushState does not get here.
        if (!isPopup) MediaBridge.onDocumentStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        Log.i(TAG, "nav: finished $url")
        if (!isPopup) WebViewHolder.onPageFinished(url)
    }

    // Also fires for history.pushState navigations of the single-page app.
    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (!isPopup) WebViewHolder.rememberUrl(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            Log.w(TAG, "nav: error ${error.errorCode} '${error.description}' for ${request.url}")
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Log.e(TAG, "renderer gone: crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
        if (isPopup) {
            WebViewHolder.closePopups()
        } else {
            WebViewHolder.rebuild()
        }
        return true
    }
}
