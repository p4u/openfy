package dev.openspotify.shell

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Process-wide owner of the single browser WebView (DESIGN §2.1).
 *
 * The WebView is created once with a [MutableContextWrapper] so that it survives Activity destruction;
 * [attach] swaps the wrapper's base context to the Activity and [detach] swaps it back.
 * All methods must be called on the main thread.
 */
object WebViewHolder {
    const val HOME_URL = "https://open.spotify.com/"
    private const val PREFS = "open_spotify"
    private const val KEY_LAST_URL = "last_url"

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wrapper: MutableContextWrapper? = null
    private var webView: WebView? = null
    private var loaded = false
    private var lastUrl: String? = null
    private var webViewPaused = false
    private val popups = LinkedHashMap<WebView, Dialog>()
    private var pageFinishedHook: ((String) -> Unit)? = null

    /** The attached Activity, if any. Needed for popup dialogs. */
    var activity: Activity? = null
        private set

    /** True between the Activity's onStart and onStop. */
    var activityVisible = false

    lateinit var arm: IdentityArm
        private set

    fun init(app: Application) {
        appContext = app
        arm = BrowserIdentity.load(app)
        lastUrl = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_URL, null)
        MediaBridge.addListener(object : MediaBridge.Listener {
            override fun onBridgeState(state: BridgeState) = resumeIfPlaying(state)
        })
    }

    /** The shared WebView, created on first use. */
    fun webView(): WebView = webView ?: create().also { webView = it }

    private fun create(): WebView {
        // First WebView API use: the application and the "unsupported WebView" screen must not touch it.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        WebViewConfig.initCookies()
        val ctx = MutableContextWrapper(activity ?: appContext).also { wrapper = it }
        val wv = WebView(ctx)
        WebViewConfig.configure(appContext, wv, arm, popup = false)
        wv.webViewClient = ShellWebViewClient(isPopup = false)
        wv.webChromeClient = ShellChromeClient()
        // Both must be registered before the first load so the script runs before page JavaScript.
        MediaBridge.install(wv, appContext)
        UiAdaptation.install(wv, appContext) // DESIGN §2.9, only if the ui_adaptation preference is on
        webViewPaused = false
        Log.i(TAG, "webview: created")
        return wv
    }

    fun attach(activity: Activity, container: ViewGroup) {
        this.activity = activity
        val wv = webView()
        wrapper?.baseContext = activity
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        closePopups()
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        wrapper?.baseContext = appContext
        this.activity = null
        activityVisible = false
    }

    /** Loads [url] if given, otherwise the remembered URL (or home) when nothing has been loaded yet. */
    fun ensureLoaded(url: String? = null) {
        when {
            url != null -> load(url)
            !loaded -> load(lastUrl ?: HOME_URL)
        }
    }

    fun load(url: String) {
        loaded = true
        webView().loadUrl(url)
    }

    fun reload() {
        webView().reload()
    }

    fun setArm(newArm: IdentityArm) {
        if (newArm == arm) return
        arm = newArm
        BrowserIdentity.save(appContext, newArm)
        val wv = webView()
        BrowserIdentity.apply(appContext, wv.settings, newArm)
        wv.reload()
    }

    /** DESIGN §2.1 onRenderProcessGone: replace the dead WebView and reload the last URL. Cookies survive. */
    fun rebuild() {
        // Defer: the callback runs inside the dying WebView.
        mainHandler.post {
            val old = webView
            val parent = old?.parent as? ViewGroup
            parent?.removeView(old)
            old?.destroy()
            closePopups()
            webView = null
            UiAdaptation.detach()
            MediaBridge.reset()
            val fresh = webView()
            parent?.addView(fresh, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            load(lastUrl ?: HOME_URL)
            Log.i(TAG, "webview: rebuilt, reloading ${lastUrl ?: HOME_URL}")
        }
    }

    /** Used by [PlaybackService] when a task is removed while paused. */
    fun destroy() {
        closePopups()
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        loaded = false
        UiAdaptation.detach()
        MediaBridge.reset()
        Log.i(TAG, "webview: destroyed")
    }

    // region lifecycle (DESIGN §2.7): the page is assumed hidden in the background; never pauseTimers().

    fun onActivityStart() {
        activityVisible = true
        resumeWebView()
    }

    fun onActivityStop() {
        activityVisible = false
        CookieManager.getInstance().flush()
        if (!MediaBridge.state.isPlaying) {
            webView?.onPause()
            webViewPaused = true
        }
    }

    private fun resumeIfPlaying(state: BridgeState) {
        if (state.isPlaying) resumeWebView()
    }

    /** Called before native-initiated JavaScript so a paused WebView can run it. */
    fun resumeWebView() {
        if (webViewPaused) {
            webView?.onResume()
            webViewPaused = false
        }
    }

    // endregion

    fun onPageFinished(url: String) {
        pageFinishedHook?.let {
            pageFinishedHook = null
            it(url)
        }
    }

    /** Runs [block] once after the next main-frame page finish. */
    fun runAfterNextPageFinished(block: (String) -> Unit) {
        pageFinishedHook = block
    }

    fun rememberUrl(url: String) {
        if (NavigationPolicy.classify(url) != NavDecision.InShell || !url.startsWith("https://")) return
        lastUrl = url
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_LAST_URL, url) }
    }

    /** DESIGN §2.7: the service is started from a visible Activity, when a track first shows up. */
    fun startPlaybackServiceIfPossible() {
        if (PlaybackService.running) return
        val host = activity?.takeIf { activityVisible }
        if (host == null) {
            Log.w(TAG, "service: cannot start from the background; will start when the Activity is visible")
            return
        }
        try {
            ContextCompat.startForegroundService(host, Intent(host, PlaybackService::class.java))
        } catch (e: RuntimeException) {
            Log.e(TAG, "service: start failed", e)
        }
    }

    // region popups

    fun popupOpened(popup: WebView, dialog: Dialog) {
        popups[popup] = dialog
    }

    fun closePopup(popup: WebView) {
        popups[popup]?.dismiss()
    }

    fun popupClosed(popup: WebView) {
        if (popups.remove(popup) != null) {
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
        }
    }

    fun closePopups() {
        popups.values.toList().forEach { it.dismiss() }
    }

    // endregion
}
