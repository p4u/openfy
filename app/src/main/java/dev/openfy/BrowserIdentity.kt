package dev.openfy

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import androidx.core.content.edit
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat

/** DESIGN §2.2: applies one consistent identity to a WebView's UA string, UA-CH and viewport. */
object BrowserIdentity {
    private const val PREFS = "open_spotify"
    private const val KEY_ARM = "identity_arm"

    /** The metadata WebView generates by default; captured once, from a pristine WebView. */
    private var defaultMetadata: UserAgentMetadata? = null

    fun load(context: Context): IdentityArm {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ARM, null)
        return IdentityArm.entries.firstOrNull { it.name == name } ?: IdentityArm.MOBILE
    }

    fun save(context: Context, arm: IdentityArm) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_ARM, arm.name) }
    }

    fun apply(context: Context, settings: WebSettings, arm: IdentityArm) {
        // Read the default metadata before touching the UA string.
        val base = defaultMetadata
            ?: WebSettingsCompat.getUserAgentMetadata(settings).also { defaultMetadata = it }

        val major = UserAgents.chromiumMajor(WebSettings.getDefaultUserAgent(context))
            ?: error("Cannot parse Chromium version from the default user agent")
        settings.userAgentString = UserAgents.build(arm, major)

        // USER_AGENT_METADATA is a hard requirement checked by FeatureGate before any WebView exists.
        if (!UaMetadataFactory.hasWebViewBrand(base)) {
            Log.w(TAG, "identity: no 'Android WebView' brand in the default metadata ${UaMetadataFactory.describeBrands(base)}; brand rewrite is a no-op")
        }
        val metadata = UaMetadataFactory.forArm(base, arm)
        WebSettingsCompat.setUserAgentMetadata(settings, metadata)

        // useWideViewPort honors the page's viewport meta tag like Chrome Mobile; overview mode zooms pages
        // without one (header echo pages, some error pages) out to fit instead of showing a 980 px layout.
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        Log.i(
            TAG,
            "identity: arm=$arm ua=${settings.userAgentString} platform=${metadata.platform} mobile=${metadata.isMobile} " +
                "brands=${UaMetadataFactory.describeBrands(metadata)} defaultBrands=${UaMetadataFactory.describeBrands(base)}",
        )
    }
}
