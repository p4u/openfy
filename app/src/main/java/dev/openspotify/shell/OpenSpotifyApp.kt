package dev.openspotify.shell

import android.app.Application
import android.util.Log
import androidx.webkit.WebViewCompat

class OpenSpotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WebViewHolder.init(this)
        val pkg = WebViewCompat.getCurrentWebViewPackage(this)
        Log.i(TAG, "startup: webview=${pkg?.packageName} ${pkg?.versionName} sdk=${android.os.Build.VERSION.SDK_INT}")
        DrmDiagnostics.log()
    }
}
