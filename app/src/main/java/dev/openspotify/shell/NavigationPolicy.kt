package dev.openspotify.shell

/** What the shell does with a main-frame navigation (DESIGN §2.4). */
sealed interface NavDecision {
    data object InShell : NavDecision
    data class Blocked(val reason: String) : NavDecision
    data object External : NavDecision
}

object NavigationPolicy {
    // `www.spotify.com` etc. are covered by the wildcard; listed hosts are matched exactly or as a parent domain.
    private val inShellDomains = listOf(
        "spotify.com",
        // IdPs reached from popups.
        "facebook.com",
        "appleid.apple.com",
    )
    private val blockedSchemes = setOf("spotify", "intent")

    // The authority ends at the first `/`, `\`, `?` or `#` (Chromium treats `\` like `/` in special schemes).
    private val urlStart = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(?://([^/\\?#]*))?""")

    /**
     * Scheme and host, parsed leniently. `java.net.URI` rejects URLs that Chromium accepts (`|`, `^`, `{` in a
     * query), and a rejected URL must not send the user to Chrome. The host is what follows the last `@`.
     */
    private fun schemeAndHost(url: String): Pair<String, String?>? {
        val match = urlStart.find(url) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groups[2]?.value ?: return scheme to null
        val hostAndPort = authority.substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) null else hostAndPort.substringBefore(':') // IPv6: never in-shell
        return scheme to host?.takeIf { it.isNotEmpty() }
    }

    fun classify(url: String): NavDecision {
        val (scheme, host) = schemeAndHost(url.trim()) ?: return NavDecision.External
        return when (scheme) {
            in blockedSchemes -> NavDecision.Blocked("scheme $scheme: is not handled in the shell")
            "javascript" -> NavDecision.Blocked("javascript: navigation")
            "about" -> NavDecision.InShell
            "https" -> if (isInShellHost(host)) NavDecision.InShell else NavDecision.External
            // Cleartext http to a Spotify host would be upgraded by the site; keep it out of the shell.
            else -> NavDecision.External
        }
    }

    fun isInShellHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return inShellDomains.any { h == it || h.endsWith(".$it") }
    }
}

/** DESIGN §2.5: the only permission the shell ever grants. */
object PermissionPolicy {
    const val PROTECTED_MEDIA_ID = "android.webkit.resource.PROTECTED_MEDIA_ID"
    private const val DRM_HOST = "open.spotify.com"

    /** Resources to grant, or an empty list to deny the whole request. */
    fun grantedResources(host: String?, requested: Array<String>): List<String> =
        if (host == DRM_HOST && PROTECTED_MEDIA_ID in requested) listOf(PROTECTED_MEDIA_ID) else emptyList()
}
