package dev.openspotify.shell

import android.app.Dialog
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

/** EME permission (DESIGN §2.5) and popup windows (DESIGN §2.3). */
class ShellChromeClient : WebChromeClient() {

    override fun onPermissionRequest(request: PermissionRequest) {
        val granted = PermissionPolicy.grantedResources(request.origin.host, request.resources)
        if (granted.isEmpty()) {
            Log.w(TAG, "permission: denied ${request.resources.toList()} for ${request.origin}")
            request.deny()
        } else {
            Log.i(TAG, "permission: EME granted for ${request.origin}")
            request.grant(granted.toTypedArray())
        }
    }

    // With no Activity attached (WebView kept alive by the service) the default dialogs would throw
    // BadTokenException on the application context, so cancel instead.
    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        if (WebViewHolder.activity == null) cancelDialog("alert", result) else super.onJsAlert(view, url, message, result)

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
        if (WebViewHolder.activity == null) cancelDialog("confirm", result) else super.onJsConfirm(view, url, message, result)

    override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
        if (WebViewHolder.activity == null) cancelDialog("prompt", result) else super.onJsPrompt(view, url, message, defaultValue, result)

    private fun cancelDialog(kind: String, result: JsResult): Boolean {
        Log.w(TAG, "webview: JS $kind dialog cancelled, no Activity attached")
        result.cancel()
        return true
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // A dialog needs an Activity window; without one the request is refused.
        val activity = WebViewHolder.activity ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(activity)
        WebViewConfig.configure(activity, popup, WebViewHolder.arm, popup = true)
        popup.webViewClient = ShellWebViewClient(isPopup = true)
        popup.webChromeClient = this

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
            setContentView(
                FrameLayout(activity).apply { addView(popup, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) },
            )
            setOnDismissListener { WebViewHolder.popupClosed(popup) }
        }
        WebViewHolder.popupOpened(popup, dialog)
        dialog.show()
        Log.i(TAG, "window: popup opened")

        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) {
        Log.i(TAG, "window: popup closed by page")
        WebViewHolder.closePopup(window)
    }

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "console: ${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
        }
        return false // keep the default `chromium` logcat output as well
    }
}
