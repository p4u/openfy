package dev.openfy.update

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import dev.openfy.BuildConfig

/** Build-time configuration of the updater, resolved once. */
object UpdateConfig {
    val isDebug: Boolean get() = BuildConfig.DEBUG

    /** Host policy; the loopback test origin only exists in debug builds that were built with one. */
    val hosts: UpdateHosts by lazy { UpdateHosts.forBuild(BuildConfig.DEBUG, BuildConfig.UPDATER_TEST_BASE) }

    val latestUrl: String
        get() = UpdateHosts.latestUrl(BuildConfig.UPDATER_REPO, BuildConfig.DEBUG, BuildConfig.UPDATER_TEST_BASE)

    val userAgent: String get() = "openfy/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"

    val installedVersion: Version get() = Version.parseInstalled(BuildConfig.VERSION_NAME)
}

/** SharedPreferences `openfy_updater` (docs/UPDATER.md §3.2, §3.8). */
class UpdatePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastCheckMs: Long
        get() = prefs.getLong(LAST_CHECK, 0L)
        set(value) = prefs.edit { putLong(LAST_CHECK, value) }

    var skippedTag: String?
        get() = prefs.getString(SKIPPED_TAG, null)
        set(value) = prefs.edit { putString(SKIPPED_TAG, value) }

    /** 0 until the first run of a build that knows about this key. */
    var lastInstalledCode: Long
        get() = prefs.getLong(LAST_INSTALLED_CODE, 0L)
        set(value) = prefs.edit { putLong(LAST_INSTALLED_CODE, value) }

    private companion object {
        const val FILE = "openfy_updater"
        const val LAST_CHECK = "last_check_ms"
        const val SKIPPED_TAG = "skipped_tag"
        const val LAST_INSTALLED_CODE = "last_installed_code"
    }
}
