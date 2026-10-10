package dev.openfy.update

/** Where the expected SHA-256 of the APK comes from (docs/UPDATER.md §2.3-1). */
sealed interface Checksum {
    /** The `digest` field of the API asset, lower-case hex. */
    data class Inline(val hex: String) : Checksum

    /** The sibling `<apk>.sha256` asset; fetched (4 KiB cap) before the download. */
    data class Sidecar(val url: String) : Checksum
}

/** A published release that carries exactly one usable APK. */
data class Release(
    val tag: String,
    val version: Version,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksum: Checksum,
) {
    /** `versionName` the APK must declare: the tag without its leading `v`. */
    val versionName: String get() = tag.removePrefix("v")
}
