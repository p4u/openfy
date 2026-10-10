package dev.openfy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHostsTest {
    private val prod = UpdateHosts.PRODUCTION

    @Test
    fun allowsTheFourGithubHosts() {
        listOf(
            "https://api.github.com/repos/p4u/openfy/releases/latest",
            "https://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/1?X-Amz-Signature=abc",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1?sp=r",
            "https://github.com:443/x",
        ).forEach { assertTrue(it, prod.isAllowed(it)) }
    }

    @Test
    fun hostMatchIsCaseInsensitive() {
        assertTrue(prod.isAllowed("https://GITHUB.COM/p4u/openfy"))
        assertTrue(prod.isAllowed("HTTPS://Api.GitHub.com/x"))
    }

    @Test
    fun rejectsHttp() {
        assertFalse(prod.isAllowed("http://github.com/p4u/openfy"))
        assertFalse(prod.isAllowed("http://api.github.com/x"))
    }

    @Test
    fun rejectsLookalikeAndUnlistedHosts() {
        listOf(
            "https://github.com.evil.com/x",
            "https://api.github.com.evil.com/x",
            "https://evilgithub.com/x",
            "https://evil.com/github.com",
            "https://githubusercontent.com/x",
            "https://raw.githubusercontent.com/x",
            "https://avatars.githubusercontent.com/x",
            "https://sub.github.com/x",
            "https://uploads.github.com/x",
        ).forEach { assertFalse(it, prod.isAllowed(it)) }
    }

    @Test
    fun rejectsOtherPortsAndUserinfo() {
        assertFalse(prod.isAllowed("https://api.github.com:8443/x"))
        assertFalse(prod.isAllowed("https://github.com:8443/x"))
        assertFalse(prod.isAllowed("https://github.com@evil.com/x"))
        assertFalse(prod.isAllowed("https://user:pw@github.com/x"))
        assertFalse(prod.isAllowed("https://evil.com@github.com/x"))
    }

    @Test
    fun rejectsOtherSchemesAndGarbage() {
        listOf("ftp://github.com/x", "file:///etc/passwd", "//github.com/x", "github.com/x", "", "https://", "https://exa mple.com")
            .forEach { assertFalse(it, prod.isAllowed(it)) }
    }

    // region redirects

    private val api = "https://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk"

    @Test
    fun redirectToAllowedHostIsFollowed() {
        val step = prod.step(api, 302, "https://release-assets.githubusercontent.com/a?b=c", 0)
        assertEquals(RedirectStep.Follow("https://release-assets.githubusercontent.com/a?b=c"), step)
    }

    @Test
    fun relativeLocationIsResolvedAgainstTheCurrentUrl() {
        assertEquals(RedirectStep.Follow("https://github.com/other/path"), prod.step(api, 301, "/other/path", 0))
    }

    @Test
    fun allRedirectStatusesAreFollowed() {
        listOf(301, 302, 303, 307, 308).forEach {
            assertTrue("$it", prod.step(api, it, "https://objects.githubusercontent.com/x", 0) is RedirectStep.Follow)
        }
    }

    @Test
    fun redirectToDisallowedTargetIsRejected() {
        for (loc in listOf(
            "https://evil.com/x", "http://objects.githubusercontent.com/x", "https://github.com.evil.com/x",
            "https://api.github.com:8443/x", "https://github.com@evil.com/x",
        )) {
            assertTrue(loc, prod.step(api, 302, loc, 0) is RedirectStep.Reject)
        }
    }

    @Test
    fun redirectReasonNamesTheHostOnly() {
        val step = prod.step(api, 302, "https://evil.com/secret?token=abc", 0) as RedirectStep.Reject
        assertTrue(step.reason, "evil.com" in step.reason)
        assertFalse(step.reason, "token" in step.reason)
    }

    @Test
    fun redirectLoopIsCapped() {
        assertTrue(prod.step(api, 302, "https://github.com/x", UpdateHosts.MAX_REDIRECTS - 1) is RedirectStep.Follow)
        assertTrue(prod.step(api, 302, "https://github.com/x", UpdateHosts.MAX_REDIRECTS) is RedirectStep.Reject)
    }

    @Test
    fun missingLocationAndOddStatusesAreRejected() {
        assertTrue(prod.step(api, 302, null, 0) is RedirectStep.Reject)
        assertTrue(prod.step(api, 302, "", 0) is RedirectStep.Reject)
        assertTrue(prod.step(api, 300, "https://github.com/x", 0) is RedirectStep.Reject)
        assertTrue(prod.step(api, 304, null, 0) is RedirectStep.Reject)
    }

    @Test
    fun nonRedirectResponsesAreFinal() {
        listOf(200, 204, 404, 403, 500).forEach { assertEquals(RedirectStep.Final, prod.step(api, it, null, 0)) }
    }

    // endregion

    // region test origin (debug only)

    private val testBase = "http://127.0.0.1:8765"

    @Test
    fun debugBuildAcceptsExactlyTheLoopbackOrigin() {
        val h = UpdateHosts.forBuild(isDebug = true, testBase = testBase)
        assertTrue(h.isAllowed("http://127.0.0.1:8765/repos/p4u/openfy/releases/latest"))
        assertTrue(h.isAllowed("http://127.0.0.1:8765/openfy-0.9.1.apk"))
        assertFalse(h.isAllowed("http://127.0.0.1:8766/x"))
        assertFalse(h.isAllowed("http://127.0.0.1/x"))
        assertFalse(h.isAllowed("http://localhost:8765/x"))
        assertFalse(h.isAllowed("http://127.0.0.2:8765/x"))
        assertFalse(h.isAllowed("https://127.0.0.1:8765/x"))
        assertFalse(h.isAllowed("http://evil.com/x"))
        assertTrue(h.isAllowed("https://github.com/x"))
    }

    @Test
    fun releaseBuildRejectsHttpEvenWithATestBase() {
        val h = UpdateHosts.forBuild(isDebug = false, testBase = testBase)
        assertFalse(h.isAllowed("http://127.0.0.1:8765/x"))
        assertFalse(h.isAllowed("http://github.com/x"))
        assertTrue(h === UpdateHosts.PRODUCTION)
    }

    @Test
    fun releaseBuildIgnoresTestBaseForTheApiUrl() {
        assertEquals(
            "https://api.github.com/repos/p4u/openfy/releases/latest",
            UpdateHosts.latestUrl("p4u/openfy", isDebug = false, testBase = testBase),
        )
        assertEquals(
            "http://127.0.0.1:8765/repos/p4u/openfy/releases/latest",
            UpdateHosts.latestUrl("p4u/openfy", isDebug = true, testBase = testBase),
        )
        assertEquals(
            "https://api.github.com/repos/p4u/openfy/releases/latest",
            UpdateHosts.latestUrl("p4u/openfy", isDebug = true, testBase = ""),
        )
    }

    @Test
    fun emptyTestBaseMeansProduction() {
        assertTrue(UpdateHosts.forBuild(isDebug = true, testBase = "") === UpdateHosts.PRODUCTION)
    }

    @Test
    fun testBaseMustBeLoopbackHttp() {
        listOf("http://evil.com:80", "https://127.0.0.1:8765", "http://127.0.0.1", "http://127.0.0.1:8765/path", "http://localhost:8765")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { UpdateHosts.forBuild(true, it) } }
    }

    // endregion
}
