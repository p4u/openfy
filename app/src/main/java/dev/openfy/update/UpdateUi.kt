package dev.openfy.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.openfy.BuildConfig
import dev.openfy.MediaBridge
import dev.openfy.R
import dev.openfy.TAG
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The updater's windows (docs/UPDATER.md §3.3): the offer dialog, the permission explanation, download progress,
 * toasts and error dialogs. Create it in `Activity.onCreate` (it registers an activity-result launcher).
 */
class UpdateUi(private val activity: AppCompatActivity) {
    private var dialog: AlertDialog? = null
    private var shown: UpdateState? = null
    private var progressBar: ProgressBar? = null
    private var installJob: Job? = null
    private var pendingRelease: Release? = null

    private val unknownSources =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The Activity may have been recreated while Settings was open: the field is gone, the state is not.
            val release = pendingRelease
                ?: (UpdateManager.state.value as? UpdateState.AwaitingPermission)?.release
                ?: return@registerForActivityResult
            pendingRelease = null
            if (activity.packageManager.canRequestPackageInstalls()) {
                startInstall(release)
            } else {
                Log.w(TAG, "update: install-unknown-apps permission not granted")
                UpdateManager.permissionDenied()
            }
        }

    /** Starts collecting [UpdateManager.state] while the Activity is started. */
    fun bind() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect(::render)
            }
        }
    }

    /** Debug menu entry and the automatic check. */
    fun check(manual: Boolean) {
        activity.lifecycleScope.launch {
            if (!UpdateManager.check(activity, manual) && manual) {
                Toast.makeText(activity, "An update is already in progress.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun onStart() {
        if (!UpdateConfig.isDebug && UpdateManager.shouldAutoCheck(activity)) check(manual = false)
        UpdateManager.consumeUpdatedVersion()?.let {
            Toast.makeText(activity, "Updated to $it", Toast.LENGTH_LONG).show()
        }
    }

    private fun render(state: UpdateState) {
        if (state === shown && dialog?.isShowing == true) return
        shown = state
        when (state) {
            is UpdateState.Available -> showOffer(state.release)
            is UpdateState.Downloading -> showProgress(state)
            is UpdateState.Failed -> showError(state.message)
            is UpdateState.Notice -> {
                closeDialog()
                Toast.makeText(activity, state.text, Toast.LENGTH_LONG).show()
                UpdateManager.dismiss()
            }
            is UpdateState.NeedsUserAction -> {
                closeDialog()
                Log.i(TAG, "update: launching the system install confirmation")
                try {
                    activity.startActivity(state.confirm)
                } catch (e: ActivityNotFoundException) {
                    UpdateManager.confirmScreenUnavailable(activity, e)
                } catch (e: SecurityException) {
                    UpdateManager.confirmScreenUnavailable(activity, e)
                }
            }
            UpdateState.Idle, UpdateState.Verifying, UpdateState.Installing -> closeDialog()
            is UpdateState.AwaitingPermission -> awaitingPermission(state.release)
            UpdateState.Checking -> Unit
        }
    }

    /**
     * The state can outlive its dialog (Activity recreated, dialog dismissed with the window), so it is rendered
     * rather than assumed: granted means go on, otherwise explain again. While Settings is open [pendingRelease]
     * is set and the activity-result callback decides.
     */
    private fun awaitingPermission(release: Release) {
        if (pendingRelease != null) return
        if (activity.packageManager.canRequestPackageInstalls()) {
            startInstall(release)
        } else {
            showPermissionDialog(release)
        }
    }

    private fun showOffer(release: Release) {
        val message = buildString {
            append(release.name)
            if (release.notes.isNotEmpty()) append("\n\n").append(release.notes)
            append("\n\nSize: ").append("%.1f MB".format(release.apkSize / 1_000_000.0))
            if (MediaBridge.state.isPlaying) append("\nPlayback will stop while the update installs.")
            if (BuildConfig.DEBUG) append("\nDebug build: the installer will refuse this APK (different signing key).")
        }
        show(
            AlertDialog.Builder(activity)
                .setTitle("Update available: ${release.versionName}")
                .setMessage(message)
                .setPositiveButton(R.string.update_install) { _, _ -> onInstallChosen(release) }
                .setNegativeButton(R.string.update_later) { _, _ -> UpdateManager.dismiss() }
                .setNeutralButton(R.string.update_skip) { _, _ -> UpdateManager.skip(activity, release) }
                .setOnCancelListener { UpdateManager.dismiss() },
        )
    }

    private fun onInstallChosen(release: Release) {
        if (activity.packageManager.canRequestPackageInstalls()) {
            startInstall(release)
            return
        }
        UpdateManager.awaitPermission(release) // render() shows the explanation
    }

    private fun showPermissionDialog(release: Release) {
        show(
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    pendingRelease = release
                    unknownSources.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                    )
                }
                .setNegativeButton(R.string.action_cancel) { _, _ -> UpdateManager.dismiss() }
                .setOnCancelListener { UpdateManager.dismiss() },
        )
    }

    private fun startInstall(release: Release) {
        installJob = activity.lifecycleScope.launch { UpdateManager.install(activity, release) }
    }

    private fun showProgress(state: UpdateState.Downloading) {
        val bar = progressBar
        if (dialog?.isShowing == true && bar != null) {
            bar.progress = (state.bytes * 100 / state.total).toInt()
            return
        }
        val newBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (state.bytes * 100 / state.total).toInt()
            setPadding(PAD, PAD, PAD, PAD)
        }
        progressBar = newBar
        show(
            AlertDialog.Builder(activity)
                .setTitle("Downloading ${state.release.versionName}")
                .setView(newBar)
                .setCancelable(false)
                .setNegativeButton(R.string.action_cancel) { _, _ -> installJob?.cancel() },
        )
    }

    private fun showError(message: String) {
        show(
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_close) { _, _ -> UpdateManager.dismiss() }
                .setOnCancelListener { UpdateManager.dismiss() },
        )
    }

    private fun show(builder: AlertDialog.Builder) {
        closeDialog()
        dialog = builder.show()
    }

    private fun closeDialog() {
        progressBar = null
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    /** Activity.onDestroy: the install coroutine dies with the lifecycleScope; drop the window. */
    fun onDestroy() {
        closeDialog()
    }

    private companion object {
        const val PAD = 48
    }
}
