package dev.openspotify.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private fun classify(url: String) = NavigationPolicy.classify(url)

    @Test
    fun spotifyHostsStayInShell() {
        listOf(
            "https://open.spotify.com/track/123",
            "https://accounts.spotify.com/en/login?continue=x",
            "https://challenge.spotify.com/c",
            "https://www.spotify.com/account",
            "https://support.spotify.com/",
            "https://OPEN.SPOTIFY.COM/",
        ).forEach { assertEquals(it, NavDecision.InShell, classify(it)) }
    }

    @Test
    fun identityProvidersStayInShell() {
        assertEquals(NavDecision.InShell, classify("https://www.facebook.com/v2.0/dialog/oauth"))
        assertEquals(NavDecision.InShell, classify("https://m.facebook.com/login"))
        assertEquals(NavDecision.InShell, classify("https://appleid.apple.com/auth/authorize"))
    }

    @Test
    fun lookalikeHostsAreExternal() {
        assertEquals(NavDecision.External, classify("https://evilspotify.com/"))
        assertEquals(NavDecision.External, classify("https://spotify.com.evil.example/"))
        assertEquals(NavDecision.External, classify("https://open.spotify.com@evil.example/"))
        assertEquals(NavDecision.External, classify("https://www.apple.com/"))
    }

    @Test
    fun assetHostsAreNotTopLevelDestinations() {
        assertEquals(NavDecision.External, classify("https://i.scdn.co/image/abc"))
    }

    @Test
    fun spotifyAndIntentSchemesAreBlocked() {
        assertTrue(classify("spotify:track:123") is NavDecision.Blocked)
        assertTrue(classify("intent://open#Intent;scheme=spotify;end") is NavDecision.Blocked)
        assertTrue(classify("javascript:alert(1)") is NavDecision.Blocked)
    }

    @Test
    fun otherSchemesAndCleartextGoToTheSystem() {
        assertEquals(NavDecision.External, classify("mailto:a@b.c"))
        assertEquals(NavDecision.External, classify("http://open.spotify.com/"))
        assertEquals(NavDecision.External, classify("not a url"))
    }

    @Test
    fun urlsThatJavaNetUriRejectStillClassifyByHost() {
        // `|`, `^`, `{` in a query: java.net.URI throws, Chromium accepts.
        assertEquals(NavDecision.InShell, classify("https://open.spotify.com/search/a|b"))
        assertEquals(NavDecision.InShell, classify("https://open.spotify.com/search?q=a^b&x={y}"))
        assertEquals(NavDecision.InShell, classify("https://open.spotify.com:443/track/1"))
        assertEquals(NavDecision.External, classify("https://evil.example/?next=|https://open.spotify.com/"))
        assertEquals(NavDecision.External, classify("https://evil.example/{open.spotify.com}"))
    }

    @Test
    fun authorityTricksDoNotReachTheShell() {
        assertEquals(NavDecision.External, classify("https://open.spotify.com@evil.example/|x"))
        // Chromium treats a backslash like a slash, so the host here is evil.example.
        assertEquals(NavDecision.External, classify("https://evil.example\\@open.spotify.com/"))
        assertEquals(NavDecision.External, classify("https://[::1]/"))
        assertEquals(NavDecision.External, classify("https:///open.spotify.com/"))
        assertEquals(NavDecision.External, classify("https:open.spotify.com"))
    }

    @Test
    fun aboutBlankIsAllowedForPopups() {
        assertEquals(NavDecision.InShell, classify("about:blank"))
    }

    @Test
    fun permissionPolicyGrantsOnlyProtectedMediaForSpotify() {
        val drm = PermissionPolicy.PROTECTED_MEDIA_ID
        assertEquals(listOf(drm), PermissionPolicy.grantedResources("open.spotify.com", arrayOf(drm)))
        // Only the DRM resource is ever granted, even if more is requested.
        assertEquals(
            listOf(drm),
            PermissionPolicy.grantedResources("open.spotify.com", arrayOf("android.webkit.resource.VIDEO_CAPTURE", drm)),
        )
        assertTrue(PermissionPolicy.grantedResources("open.spotify.com", arrayOf("android.webkit.resource.AUDIO_CAPTURE")).isEmpty())
        assertTrue(PermissionPolicy.grantedResources("evil.example", arrayOf(drm)).isEmpty())
        assertTrue(PermissionPolicy.grantedResources(null, arrayOf(drm)).isEmpty())
        assertTrue(PermissionPolicy.grantedResources("accounts.spotify.com", arrayOf(drm)).isEmpty())
    }
}
