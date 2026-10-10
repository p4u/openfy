package dev.openfy.update

/** What the updater needs to know about an installed app or an APK file. */
data class PackageFacts(val packageName: String, val versionCode: Long, val versionName: String?)

/** Pure: package identity, version and signer checks run before any PackageInstaller session (UPDATER.md §2.3-2/3). */
object PreInstallCheck {
    /**
     * Returns null if [archivePath] may be installed over [installed], else the reason to show the user.
     * [archive] is the APK's own manifest data (null if unparseable). Debug builds carry AGP's `-debug` suffix in
     * their versionName; it is ignored there only.
     */
    fun refusal(
        installed: PackageFacts,
        archive: PackageFacts?,
        signers: SignerSource,
        archivePath: String,
        expectedVersionName: String,
        isDebug: Boolean,
    ): String? {
        if (archive == null) return "The downloaded file is not a valid APK."
        if (archive.packageName != installed.packageName) {
            return "The downloaded APK is for ${archive.packageName}, not ${installed.packageName}."
        }
        if (archive.versionCode <= installed.versionCode) {
            return "The downloaded APK (versionCode ${archive.versionCode}) is not newer than the installed one " +
                "(${installed.versionCode}). Downgrades are refused."
        }
        val name = archive.versionName?.let { if (isDebug) Version.stripDebugSuffix(it) else it }
        if (name != expectedVersionName) {
            return "The downloaded APK says version $name, the release says $expectedVersionName."
        }
        return when (val v = SignerCheck.verdict(signers.installed(), signers.archive(archivePath))) {
            SignerCheck.Verdict.Match -> null
            is SignerCheck.Verdict.Refused -> v.reason
        }
    }
}
