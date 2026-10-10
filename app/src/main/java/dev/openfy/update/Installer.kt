package dev.openfy.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dev.openfy.TAG
import java.io.File
import java.io.IOException

/** PackageInstaller session API (docs/UPDATER.md §3.6). Blocking I/O: call on `Dispatchers.IO`. */
internal object Installer {
    private const val APK_NAME_IN_SESSION = "openfy.apk"

    /** Copies [file] into a new session and commits it; the outcome arrives at [InstallStatusReceiver]. */
    fun commit(context: Context, file: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val sessionId = try {
            pi.createSession(params)
        } catch (e: IOException) {
            throw UpdateException("Could not create an install session: ${e.message}", e)
        }
        try {
            pi.openSession(sessionId).use { session ->
                session.openWrite(APK_NAME_IN_SESSION, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusIntentSender(context, sessionId))
            }
            Log.i(TAG, "update: session $sessionId committed")
        } catch (e: Exception) {
            runCatching { pi.abandonSession(sessionId) }
            throw if (e is UpdateException) e else UpdateException("Could not hand the APK to the installer: ${e.message}", e)
        }
    }

    /**
     * Explicit component, so a mutable PendingIntent is allowed on Android 14+ (targetSdk 34+ throws for implicit
     * ones). The system fills the status extras in, hence FLAG_MUTABLE on 31+.
     */
    private fun statusIntentSender(context: Context, sessionId: Int): android.content.IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java)
            .setAction(InstallStatusReceiver.ACTION)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    /** Abandons sessions left behind by a previous process (docs/UPDATER.md §3.6). */
    fun abandonStaleSessions(context: Context) {
        val pi = context.packageManager.packageInstaller
        for (s in pi.mySessions) {
            Log.i(TAG, "update: abandoning stale session ${s.sessionId}")
            runCatching { pi.abandonSession(s.sessionId) }
        }
    }
}
