package dev.openfy.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.webkit.CookieManager
import dev.openfy.BuildConfig
import dev.openfy.MediaBridge
import dev.openfy.PlaybackService
import dev.openfy.TAG
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Process singleton (like `WebViewHolder`) that runs the check, download, verification and install, and publishes
 * [UpdateState]. Suspend entry points run in the Activity's `lifecycleScope`; cancelling one deletes the partial file.
 * Everything is logged as `update: ...` under the shared tag. See docs/UPDATER.md.
 */
object UpdateManager {
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState

    private var currentFile: File? = null
    private var updatedToVersion: String? = null

    private val http by lazy { UpdateHttp(UpdateConfig.hosts, UpdateConfig.userAgent) }

    private fun isBusy(): Boolean = when (mutableState.value) {
        UpdateState.Checking, is UpdateState.Downloading, UpdateState.Verifying, UpdateState.Installing,
        is UpdateState.NeedsUserAction -> true
        else -> false
    }

    // region application start

    /** `Application.onCreate`: forget leftovers of a previous process and note whether we were just updated. */
    fun cleanup(context: Context) {
        try {
            File(context.cacheDir, DIR).deleteRecursively()
            Installer.abandonStaleSessions(context)
            val prefs = UpdatePrefs(context)
            val code = BuildConfig.VERSION_CODE.toLong()
            val previous = prefs.lastInstalledCode
            if (previous != code) {
                prefs.lastInstalledCode = code
                if (previous != 0L) updatedToVersion = BuildConfig.VERSION_NAME
            }
        } catch (e: Exception) {
            Log.w(TAG, "update: cleanup failed", e)
        }
    }

    /** Version name to announce once after an update, or null. */
    fun consumeUpdatedVersion(): String? = updatedToVersion.also { updatedToVersion = null }

    // endregion

    // region check

    fun shouldAutoCheck(context: Context): Boolean =
        UpdateDecision.auto(UpdateConfig.isDebug, UpdatePrefs(context).lastCheckMs, System.currentTimeMillis()) ==
            UpdateDecision.Action.Check

    /** [manual] checks always report a result; automatic ones only surface an offer (failures are logged). */
    suspend fun check(context: Context, manual: Boolean) {
        if (isBusy()) {
            Log.i(TAG, "update: check ignored, busy (${mutableState.value::class.simpleName})")
            return
        }
        val prefs = UpdatePrefs(context)
        mutableState.value = UpdateState.Checking
        Log.i(TAG, "update: check start manual=$manual")
        try {
            val (status, body) = withContext(Dispatchers.IO) {
                http.getText(UpdateConfig.latestUrl, SizeCap.MAX_JSON_BYTES, "application/vnd.github+json")
            }
            prefs.lastCheckMs = System.currentTimeMillis()
            when (val parsed = ReleaseParser.parse(status, body)) {
                ParseResult.NoRelease -> quiet(manual, "No release has been published yet.")
                is ParseResult.Ignored -> quiet(manual, "No update available (${parsed.reason}).")
                is ParseResult.Invalid -> fail(manual, "Update check failed: ${parsed.reason}.")
                is ParseResult.Ok -> afterParse(parsed.release, prefs, manual)
            }
        } catch (e: CancellationException) {
            mutableState.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            prefs.lastCheckMs = System.currentTimeMillis() // a flaky network must not hammer GitHub
            Log.w(TAG, "update: check failed", e)
            fail(manual, "Update check failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun afterParse(release: Release, prefs: UpdatePrefs, manual: Boolean) {
        val installed = UpdateConfig.installedVersion
        when (val outcome = UpdateDecision.outcome(installed, release, prefs.skippedTag, manual)) {
            is UpdateDecision.Outcome.Offer -> {
                Log.i(TAG, "update: offering ${release.tag} (installed $installed)")
                mutableState.value = UpdateState.Available(outcome.release)
            }
            UpdateDecision.Outcome.UpToDate -> {
                Log.i(TAG, "update: up to date (installed $installed, latest ${release.tag})")
                quiet(manual, "You have the latest version (${BuildConfig.VERSION_NAME}).")
            }
            UpdateDecision.Outcome.Skipped -> {
                Log.i(TAG, "update: ${release.tag} was skipped by the user")
                mutableState.value = UpdateState.Idle
            }
        }
    }

    private fun quiet(manual: Boolean, text: String) {
        Log.i(TAG, "update: $text")
        mutableState.value = if (manual) UpdateState.Notice(text) else UpdateState.Idle
    }

    private fun fail(show: Boolean, message: String) {
        Log.w(TAG, "update: $message")
        mutableState.value = if (show) UpdateState.Failed(message) else UpdateState.Idle
    }

    // endregion

    // region user decisions

    fun skip(context: Context, release: Release) {
        UpdatePrefs(context).skippedTag = release.tag
        Log.i(TAG, "update: skipping ${release.tag}")
        mutableState.value = UpdateState.Idle
    }

    /** The user closed a result (Later, toast, error dialog). */
    fun dismiss() {
        if (!isBusy()) mutableState.value = UpdateState.Idle
    }

    fun awaitPermission(release: Release) {
        mutableState.value = UpdateState.AwaitingPermission(release)
    }

    fun permissionDenied() {
        fail(true, "Permission to install apps was not granted, so the update cannot be installed.")
    }

    // endregion

    // region download, verify, install

    /** Downloads [release], verifies it and hands it to PackageInstaller. Call after "install unknown apps" is granted. */
    suspend fun install(context: Context, release: Release) {
        if (isBusy()) return
        val dir = File(context.cacheDir, DIR)
        val file = File(dir, "openfy-${release.versionName}.apk")
        currentFile = file
        mutableState.value = UpdateState.Downloading(release, 0, release.apkSize)
        try {
            val expected = expectedSha256(release)
            val actual = withContext(Dispatchers.IO) { download(release, file) }
            mutableState.value = UpdateState.Verifying
            if (actual != expected) {
                Log.w(TAG, "update: sha256 mismatch expected=$expected actual=$actual")
                throw UpdateException("The download is corrupt or was tampered with (SHA-256 mismatch).")
            }
            val refusal = withContext(Dispatchers.IO) { preInstallRefusal(context, release, file) }
            if (refusal != null) throw UpdateException(refusal)

            prepareProcessForRestart(context)
            mutableState.value = UpdateState.Installing
            withContext(Dispatchers.IO) { Installer.commit(context, file) }
        } catch (e: CancellationException) {
            Log.i(TAG, "update: cancelled")
            withContext(NonCancellable) { discardFile() }
            mutableState.value = UpdateState.Idle
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update: install failed", e)
            discardFile()
            mutableState.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun expectedSha256(release: Release): String = when (val c = release.checksum) {
        is Checksum.Inline -> c.hex
        is Checksum.Sidecar -> withContext(Dispatchers.IO) {
            val (status, text) = http.getText(c.url, SizeCap.MAX_CHECKSUM_BYTES, "text/plain")
            if (status != 200) throw UpdateException("Could not fetch the checksum file (HTTP $status).")
            Sha256.fromChecksumFile(text) ?: throw UpdateException("The checksum file does not contain a SHA-256.")
        }
    }

    private suspend fun download(release: Release, file: File): String {
        val job = currentCoroutineContext()
        var lastPct = -1
        Log.i(TAG, "update: downloading ${release.tag} from ${UpdateConfig.hosts.hostForLog(release.apkUrl)}, ${release.apkSize} bytes")
        return http.download(release.apkUrl, file, release.apkSize) { bytes ->
            job.ensureActive()
            val pct = (bytes * 100 / release.apkSize).toInt()
            if (pct != lastPct) {
                lastPct = pct
                mutableState.value = UpdateState.Downloading(release, bytes, release.apkSize)
            }
        }
    }

    private fun preInstallRefusal(context: Context, release: Release, file: File): String? {
        val signers = PackageSigners(context)
        return PreInstallCheck.refusal(
            installed = signers.installedFacts(),
            archive = signers.archiveFacts(file.path),
            signers = signers,
            archivePath = file.path,
            expectedVersionName = release.versionName,
            isDebug = UpdateConfig.isDebug,
        )
    }

    /** The install kills the process: persist the login, stop playback and the service first (docs/UPDATER.md §3.5). */
    private fun prepareProcessForRestart(context: Context) {
        CookieManager.getInstance().flush()
        if (MediaBridge.state.isPlaying) {
            Log.i(TAG, "update: pausing playback before install")
            MediaBridge.invoke("pause")
            if (PlaybackService.running) {
                context.startService(Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_CLOSE))
            }
        }
    }

    private fun discardFile() {
        currentFile?.delete()
        currentFile = null
    }

    // endregion

    // region PackageInstaller status (from InstallStatusReceiver, main thread)

    fun onUserActionRequired(confirm: Intent) {
        mutableState.value = UpdateState.NeedsUserAction(confirm)
    }

    /** The Activity launched the confirm screen. */
    fun userActionLaunched() {
        if (mutableState.value is UpdateState.NeedsUserAction) mutableState.value = UpdateState.Installing
    }

    fun onInstallSucceeded() {
        Log.i(TAG, "update: install succeeded; the process is about to be replaced")
    }

    fun onInstallFailed(status: Int, message: String?) {
        discardFile()
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Log.i(TAG, "update: install cancelled by the user")
            mutableState.value = UpdateState.Idle
            return
        }
        mutableState.value = UpdateState.Failed("The system refused the install: ${message ?: "status $status"}.")
    }

    // endregion

    private const val DIR = "updates"
}
