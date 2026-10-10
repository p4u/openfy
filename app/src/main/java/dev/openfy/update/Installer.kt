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

    /**
     * Copies [file] into a new session and commits it; the outcome arrives at [InstallStatusReceiver].
     * The bytes are hashed again while they are copied and must equal [expectedSha256] (and the length the file had
     * when the copy started) before the session is committed, so the installer receives exactly the verified bytes.
     * [onSession] gets the session id as soon as it exists, before the commit can produce any status.
     */
    fun commit(context: Context, file: File, expectedSha256: String, onSession: (Int) -> Unit = {}) {
        val pi = context.packageManager.packageInstaller
        val length = file.length()
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(length)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val sessionId = try {
            pi.createSession(params)
        } catch (e: IOException) {
            throw UpdateException("Could not create an install session: ${e.message}", e)
        }
        onSession(sessionId)
        try {
            pi.openSession(sessionId).use { session ->
                val digest = Sha256.newDigest()
                val copied = session.openWrite(APK_NAME_IN_SESSION, 0, length).use { out ->
                    val n = file.inputStream().use { copyBounded(it, out, length, digest, what = "APK file") }
                    session.fsync(out)
                    n
                }
                if (copied != length || Sha256.hex(digest.digest()) != expectedSha256) {
                    throw UpdateException("The APK changed on disk between verification and install.")
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

    /**
     * Abandons sessions left behind by a previous process (docs/UPDATER.md §3.6). With [keepCommitted] (API 29+),
     * sessions that were already committed are left alone: the system confirm sheet may still be up and the user's
     * tap would otherwise fail. Below API 29 there is no way to tell, so everything is abandoned.
     */
    fun abandonStaleSessions(context: Context, keepCommitted: Boolean) {
        val pi = context.packageManager.packageInstaller
        for (s in pi.mySessions) {
            if (keepCommitted && Build.VERSION.SDK_INT >= 29 && s.isCommitted) {
                Log.i(TAG, "update: keeping committed session ${s.sessionId}")
                continue
            }
            Log.i(TAG, "update: abandoning stale session ${s.sessionId}")
            runCatching { pi.abandonSession(s.sessionId) }
        }
    }

    fun abandon(context: Context, sessionId: Int) {
        runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
    }
}
