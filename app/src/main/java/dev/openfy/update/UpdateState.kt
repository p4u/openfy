package dev.openfy.update

import android.content.Intent

/** What the updater is doing; collected by `MainActivity` (docs/UPDATER.md §3.7). */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** A newer release; the user decides (Install / Later / Skip this version). */
    data class Available(val release: Release) : UpdateState

    /** Install was chosen but "install unknown apps" has to be granted first. Rendered as nothing. */
    data class AwaitingPermission(val release: Release) : UpdateState

    data class Downloading(val release: Release, val bytes: Long, val total: Long) : UpdateState

    data object Verifying : UpdateState

    data object Installing : UpdateState

    /** The system wants the user to confirm; the Activity launches [confirm] (a receiver must not). */
    data class NeedsUserAction(val confirm: Intent) : UpdateState

    /** Result of a manual check that needs no decision ("up to date", "no release yet"). Shown as a toast. */
    data class Notice(val text: String) : UpdateState

    /** Shown in a dialog. Errors are never silent. */
    data class Failed(val message: String) : UpdateState
}
