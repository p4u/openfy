package dev.openfy.update

import android.util.Log
import dev.openfy.TAG
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The updater's only HTTP code (docs/UPDATER.md §2.2): `HttpURLConnection`, redirects followed by hand and every
 * hop checked against [UpdateHosts]. No `Authorization` header is ever sent. Blocking; call on `Dispatchers.IO`.
 */
internal class UpdateHttp(private val hosts: UpdateHosts, private val userAgent: String) {

    /** GET [url] and run [block] on the final response (status < 300 or an error status other than a redirect). */
    fun <T> get(
        url: String,
        readTimeoutMs: Int,
        accept: String,
        block: (status: Int, conn: HttpURLConnection) -> T,
    ): T {
        var current = url
        var hop = 0
        while (true) {
            if (!hosts.isAllowed(current)) {
                throw UpdateException("Refused to contact host ${hosts.hostForLog(current)} (not an allowed update host).")
            }
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", accept)
                if (hop == 0 && current.startsWith("https://api.github.com/")) {
                    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                val status = conn.responseCode
                when (val step = hosts.step(current, status, conn.getHeaderField("Location"), hop)) {
                    RedirectStep.Final -> return block(status, conn)
                    is RedirectStep.Follow -> {
                        Log.i(TAG, "update: redirect $status to ${hosts.hostForLog(step.url)}")
                        current = step.url
                        hop++
                    }
                    is RedirectStep.Reject -> {
                        Log.w(TAG, "update: ${step.reason}")
                        throw UpdateException("Update server sent an unsafe redirect: ${step.reason}.")
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Small text response, capped at [cap] bytes. Returns status and body. [what] names the response in the
     * overflow message. An oversized error body is dropped (empty) so the HTTP status still reaches the caller.
     */
    fun getText(url: String, cap: Long, accept: String, what: String): Pair<Int, String> =
        get(url, READ_TIMEOUT_MS, accept) { status, conn ->
            val ok = status in 200..299
            val stream = if (ok) conn.inputStream else conn.errorStream
            val body = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                try {
                    copyBounded(input, out, cap, what = what)
                    out.toString(Charsets.UTF_8.name())
                } catch (e: UpdateException) {
                    if (ok) throw e
                    ""
                }
            } ?: ""
            status to body
        }

    /**
     * Downloads [url] into [dest] (replaced), never more than [expectedSize] bytes, and returns the SHA-256 hex of
     * exactly the bytes written. [onProgress] may throw to cancel.
     */
    fun download(url: String, dest: File, expectedSize: Long, onProgress: (Long) -> Unit): String =
        get(url, DOWNLOAD_READ_TIMEOUT_MS, "application/octet-stream") { status, conn ->
            if (status != 200) throw UpdateException("Download failed: HTTP $status.")
            val declared = conn.contentLengthLong
            if (declared >= 0 && declared != expectedSize) {
                throw UpdateException("Download size $declared does not match the release's $expectedSize.")
            }
            dest.parentFile?.mkdirs()
            val md: MessageDigest = Sha256.newDigest()
            val written = try {
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out -> copyBounded(input, out, expectedSize, md, onProgress = onProgress) }
                }
            } catch (e: IOException) {
                dest.delete()
                throw e
            }
            if (written != expectedSize || dest.length() != expectedSize) {
                dest.delete()
                throw UpdateException("Download ended after $written of $expectedSize bytes.")
            }
            Sha256.hex(md.digest())
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    }
}
