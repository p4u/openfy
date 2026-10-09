package dev.openfy

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** DESIGN §2.3: hard requirements, checked at start. */
object FeatureGate {
    const val MIN_CHROMIUM_MAJOR = 120

    /** A human-readable reason the shell cannot run, or null if all requirements are met. */
    fun check(context: Context): String? {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context)
            ?: return "No Android System WebView is installed."
        val major = pkg.versionName?.substringBefore('.')?.toIntOrNull()
        val missing = listOf(
            WebViewFeature.DOCUMENT_START_SCRIPT,
            WebViewFeature.WEB_MESSAGE_LISTENER,
            WebViewFeature.USER_AGENT_METADATA,
        ).filterNot { WebViewFeature.isFeatureSupported(it) }

        return when {
            major == null || major < MIN_CHROMIUM_MAJOR ->
                "WebView ${pkg.packageName} ${pkg.versionName} is too old. Chromium $MIN_CHROMIUM_MAJOR or newer is required."
            missing.isNotEmpty() ->
                "WebView ${pkg.versionName} lacks required features: ${missing.joinToString()}."
            else -> null
        }
    }
}
