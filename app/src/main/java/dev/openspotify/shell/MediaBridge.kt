package dev.openspotify.shell

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Native end of `media-bridge.js` (DESIGN §2.6). Everything here runs on the main thread:
 * `WebMessageListener.onPostMessage` is delivered on the UI thread.
 */
object MediaBridge {
    private const val OBJECT_NAME = "OpenSpotifyBridge"
    private const val SCRIPT_ASSET = "media-bridge.js"
    private val ORIGINS = setOf("https://open.spotify.com")

    fun interface Listener {
        fun onBridgeState(state: BridgeState)
    }

    var state = BridgeState()
        private set

    /** Receives the raw `diag` JSON (async, answers [requestDiag]). */
    var diagListener: ((String) -> Unit)? = null

    private val listeners = mutableSetOf<Listener>()
    private var webView: WebView? = null
    private var script: String? = null

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun install(webView: WebView, context: Context) {
        this.webView = webView
        check(
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT),
        ) { "WebView lacks WEB_MESSAGE_LISTENER / DOCUMENT_START_SCRIPT" }

        val js = script ?: context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }.also { script = it }
        // The listener object is injected before the document-start script runs.
        WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, ORIGINS, ::onPostMessage)
        WebViewCompat.addDocumentStartJavaScript(webView, js, ORIGINS)
        Log.i(TAG, "bridge: installed for $ORIGINS")
    }

    /** Forget everything (WebView rebuilt or destroyed). */
    fun reset() {
        webView = null
        state = BridgeState()
        listeners.toList().forEach { it.onBridgeState(state) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame) {
            Log.d(TAG, "bridge: dropped message from sub-frame $sourceOrigin")
            return
        }
        handleMessage(message.data ?: return, SystemClock.elapsedRealtime())
    }

    internal fun handleMessage(raw: String, now: Long) {
        val msg = parseBridgeMessage(raw)
        if (msg == null) {
            Log.w(TAG, "bridge: unparseable message ${raw.take(200)}")
            return
        }
        when (msg.optString("t")) {
            "diag" -> {
                Log.i(TAG, "bridge: diag received")
                diagListener?.invoke(raw)
                return
            }
            "adapt" -> {
                UiAdaptation.onMessage(msg)
                return
            }
            "error" -> Log.w(TAG, "bridge: error ${raw.take(500)}")
            else -> Log.i(TAG, "bridge: ${raw.take(500)}")
        }

        val old = state
        state = old.reduce(msg, now)
        if (shouldStartService(msg, state)) WebViewHolder.startPlaybackServiceIfPossible()
        if (state != old) listeners.toList().forEach { it.onBridgeState(state) }
    }

    /**
     * A new main-frame document replaces the old one without the old one saying goodbye, so everything
     * observed so far is stale (DESIGN §2.7). The installation itself stays. Not called for `pushState`.
     */
    fun onDocumentStarted(url: String) {
        val old = state
        state = BridgeState()
        Log.i(TAG, "bridge: new document $url, state cleared")
        if (state != old) listeners.toList().forEach { it.onBridgeState(state) }
    }

    /** Page-side `window.__osShell.invoke(action, extra)`; resumes a paused WebView first. */
    fun invoke(action: String, extra: JSONObject? = null) {
        val wv = webView
        if (wv == null) {
            Log.w(TAG, "bridge: invoke $action dropped, no WebView")
            return
        }
        WebViewHolder.resumeWebView()
        val js = "window.__osShell&&window.__osShell.invoke(${JSONObject.quote(action)},${extra ?: "null"})"
        wv.evaluateJavascript(js) { result -> Log.i(TAG, "bridge: invoke $action ${extra ?: ""} -> $result") }
    }

    fun requestDiag() {
        val wv = webView
        if (wv == null) {
            Log.w(TAG, "bridge: diag dropped, no WebView")
            diagListener?.invoke("""{"t":"diag","bridgeInstalled":false,"reason":"no WebView"}""")
            return
        }
        WebViewHolder.resumeWebView()
        wv.evaluateJavascript("window.__osShell?window.__osShell.diag():'bridge missing'") {
            Log.i(TAG, "bridge: diag requested -> $it")
            if (it == null || it == "null" || it == "\"bridge missing\"") {
                diagListener?.invoke("""{"t":"diag","bridgeInstalled":false}""")
            }
        }
    }
}

/** The service is started when a track shows up: metadata, or a media element starting to play. */
internal fun shouldStartService(msg: JSONObject, state: BridgeState): Boolean = when (msg.optString("t")) {
    "metadata" -> state.hasTrack
    "media" -> state.isPlaying && msg.optString("ev") in setOf("play", "playing")
    else -> false
}
