package dev.openfy.update

import java.net.URI

/** What to do with one HTTP response while following redirects by hand (docs/UPDATER.md §2.2). */
sealed interface RedirectStep {
    /** Not a redirect: the caller handles this response. */
    data object Final : RedirectStep

    data class Follow(val url: String) : RedirectStep

    data class Reject(val reason: String) : RedirectStep
}

/**
 * HTTPS-only, exact-host allow-list for everything the updater fetches. Pure (java.net.URI only).
 *
 * Release builds only ever know the four GitHub hosts. A debug build may additionally allow one loopback origin,
 * `http://127.0.0.1:<port>`, for the emulator end-to-end test; [forBuild] drops it when `isDebug` is false.
 */
class UpdateHosts private constructor(private val testOrigin: Origin?) {
    private data class Origin(val host: String, val port: Int)

    fun isAllowed(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> host in ALLOWED_HOSTS && (uri.port == -1 || uri.port == 443)
            "http" -> testOrigin != null && host == testOrigin.host && uri.port == testOrigin.port
            else -> false
        }
    }

    /** Host for log lines. Never the full URL: signed download URLs carry a token. */
    fun hostForLog(url: String): String = parse(url)?.host ?: "?"

    /** Decides what a response means: [RedirectStep.Final], follow the (checked) `Location`, or reject. */
    fun step(current: String, status: Int, location: String?, hop: Int): RedirectStep {
        if (status !in REDIRECT_STATUSES) {
            return if (status in 300..399) RedirectStep.Reject("unexpected redirect status $status") else RedirectStep.Final
        }
        if (location.isNullOrBlank()) return RedirectStep.Reject("redirect without Location")
        if (hop >= MAX_REDIRECTS) return RedirectStep.Reject("more than $MAX_REDIRECTS redirects")
        val next = try {
            URI(current).resolve(location).toString()
        } catch (_: Exception) {
            return RedirectStep.Reject("unparseable redirect target")
        }
        return if (isAllowed(next)) {
            RedirectStep.Follow(next)
        } else {
            RedirectStep.Reject("redirect to disallowed host ${hostForLog(next)}")
        }
    }

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: Exception) {
        null
    }

    companion object {
        val ALLOWED_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
        const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val TEST_BASE = Regex("""^http://127\.0\.0\.1:(\d{1,5})$""")

        /** The GitHub-only policy. */
        val PRODUCTION = UpdateHosts(null)

        /** [testBase] is honoured only when [isDebug]; it must be exactly `http://127.0.0.1:<port>`. */
        fun forBuild(isDebug: Boolean, testBase: String): UpdateHosts {
            if (!isDebug || testBase.isEmpty()) return PRODUCTION
            val port = TEST_BASE.matchEntire(testBase)?.groupValues?.get(1)?.toIntOrNull()
            require(port != null && port in 1..65535) { "updater test base must be http://127.0.0.1:<port>" }
            return UpdateHosts(Origin("127.0.0.1", port))
        }

        /** Origin of the release asset URLs ("https://github.com"); [testBase] only counts for debug builds. */
        fun downloadBase(isDebug: Boolean, testBase: String): String =
            if (isDebug && testBase.isNotEmpty()) testBase else "https://github.com"

        /** The `releases/latest` URL for [repo] ("owner/name"); [testBase] only counts for debug builds. */
        fun latestUrl(repo: String, isDebug: Boolean, testBase: String): String {
            val base = if (isDebug && testBase.isNotEmpty()) testBase else "https://api.github.com"
            return "$base/repos/$repo/releases/latest"
        }
    }
}
