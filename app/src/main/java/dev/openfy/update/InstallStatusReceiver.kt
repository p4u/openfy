package dev.openfy.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import dev.openfy.TAG

/**
 * Receives the PackageInstaller status (docs/UPDATER.md §3.7). Manifest-declared, not exported: the PendingIntent is
 * sent as this app. It never starts an Activity itself; the confirm intent goes to [UpdateManager], and
 * `MainActivity` launches it.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        Log.i(TAG, "update: install status=$status session=$sessionId message=$message")
        if (!UpdateManager.isActiveSession(sessionId)) {
            Log.i(TAG, "update: ignoring a status for session $sessionId (not the active one)")
            return
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    UpdateManager.onInstallFailed(PackageInstaller.STATUS_FAILURE, "the system sent no confirmation screen")
                } else {
                    UpdateManager.onUserActionRequired(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.onInstallSucceeded()
            else -> UpdateManager.onInstallFailed(status, message)
        }
    }

    companion object {
        const val ACTION = "dev.openfy.action.INSTALL_STATUS"
    }
}
