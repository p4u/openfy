package dev.openfy.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Download size cap (docs/UPDATER.md §2.3-4). */
object SizeCap {
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    const val MAX_JSON_BYTES = 1L * 1024 * 1024
    const val MAX_CHECKSUM_BYTES = 4L * 1024

    /** Reason to refuse a declared APK size, or null if it is acceptable. */
    fun refusal(declaredSize: Long): String? = when {
        declaredSize <= 0 -> "release declares no APK size"
        declaredSize > MAX_APK_BYTES -> "APK is larger than the ${MAX_APK_BYTES / (1024 * 1024)} MiB cap"
        else -> null
    }
}

/** A failure the user should see (message is shown as is). */
class UpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Copies [input] to [output], feeding [digest] with exactly the bytes written, and aborts as soon as more than
 * [limit] bytes arrive. Returns the byte count. [onProgress] gets the running total and may throw to cancel.
 */
fun copyBounded(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    digest: MessageDigest? = null,
    onProgress: (Long) -> Unit = {},
): Long {
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw UpdateException("download exceeds the expected size ($limit bytes)")
        digest?.update(buf, 0, n)
        output.write(buf, 0, n)
        onProgress(total)
    }
    return total
}

/** Streaming SHA-256 helpers (docs/UPDATER.md §2.3-1). */
object Sha256 {
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b.toInt() and 0xff))
    }

    /** Streaming digest of a file (never reads it whole into memory). */
    fun hexOf(file: File): String {
        val md = newDigest()
        file.inputStream().use { copyBounded(it, DiscardingOutputStream, Long.MAX_VALUE, md) }
        return hex(md.digest())
    }

    /** `"sha256:<hex>"` from the GitHub API, as lower-case hex; null if absent or malformed. */
    fun fromDigestField(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        return digest.removePrefix("sha256:").takeIf { HEX64.matches(it) }?.lowercase()
    }

    /** First whitespace-separated token of a `sha256sum` style file, as lower-case hex; null if not a SHA-256. */
    fun fromChecksumFile(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { HEX64.matches(it) }?.lowercase()
}

/** `OutputStream.nullOutputStream()` needs API 33; minSdk is 26. */
private object DiscardingOutputStream : OutputStream() {
    override fun write(b: Int) = Unit

    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
