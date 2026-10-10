package dev.openfy.update

/** Pure: when to check and what to do with the answer (docs/UPDATER.md §3.2). */
object UpdateDecision {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    enum class Action { Check, Skip }

    sealed interface Outcome {
        data class Offer(val release: Release) : Outcome

        data object UpToDate : Outcome

        /** A newer release exists but the user chose "Skip this version" (automatic checks only). */
        data object Skipped : Outcome
    }

    /** Automatic check: never in debug builds, at most once per 24 h (a clock that went backwards counts as due). */
    fun auto(isDebug: Boolean, lastCheckMs: Long, now: Long): Action = when {
        isDebug -> Action.Skip
        now < lastCheckMs || now - lastCheckMs >= CHECK_INTERVAL_MS -> Action.Check
        else -> Action.Skip
    }

    /** Strictly newer only. A manual check ignores `skipped_tag`. */
    fun outcome(installed: Version, release: Release, skippedTag: String?, manual: Boolean): Outcome = when {
        release.version <= installed -> Outcome.UpToDate
        !manual && release.tag == skippedTag -> Outcome.Skipped
        else -> Outcome.Offer(release)
    }
}
