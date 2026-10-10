package dev.openfy.update

import java.security.MessageDigest

/** Signing certificates (DER bytes) of the installed app and of a downloaded APK. Implemented over PackageManager. */
interface SignerSource {
    fun installed(): List<ByteArray>

    /** Null if the archive cannot be parsed or carries no signature information. */
    fun archive(path: String): List<ByteArray>?
}

/** Pure: same-signer check as set equality of SHA-256(cert DER) (docs/UPDATER.md §2.3-3). No key rotation. */
object SignerCheck {
    sealed interface Verdict {
        data object Match : Verdict

        data class Refused(val reason: String) : Verdict
    }

    fun digests(certs: List<ByteArray>): Set<String> =
        certs.map { Sha256.hex(MessageDigest.getInstance("SHA-256").digest(it)) }.toSet()

    fun verdict(installed: List<ByteArray>, archive: List<ByteArray>?): Verdict {
        if (archive == null) return Verdict.Refused("The downloaded file is not a readable APK.")
        val installedSet = digests(installed)
        val archiveSet = digests(archive)
        if (installedSet.isEmpty()) return Verdict.Refused("The installed app has no readable signature.")
        if (archiveSet.isEmpty()) return Verdict.Refused("The downloaded APK is not signed.")
        return if (installedSet == archiveSet) {
            Verdict.Match
        } else {
            Verdict.Refused(SIGNER_MISMATCH)
        }
    }

    const val SIGNER_MISMATCH =
        "The downloaded APK is signed with a different key than the installed app. " +
            "Uninstall and install the release APK from GitHub instead."
}
