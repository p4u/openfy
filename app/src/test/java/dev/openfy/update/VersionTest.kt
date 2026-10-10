package dev.openfy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun v(s: String) = Version.parse(s) ?: error("cannot parse $s")

    @Test
    fun parsesFinalsAndPreReleases() {
        assertEquals(Version(0, 2, 0, 1), v("0.2.0-rc.1"))
        assertEquals(Version(0, 2, 0, null), v("v0.2.0"))
        assertEquals(Version(1, 20, 3, 12), v("1.20.3-rc.12"))
        assertEquals("0.2.0-rc.1", v("v0.2.0-rc.1").toString())
    }

    @Test
    fun parsesDev() {
        assertEquals(Version.DEV, v("0.0.0-dev"))
        assertTrue(v("0.0.0-dev").dev)
    }

    @Test
    fun rejectsOutsideTheGrammar() {
        listOf(
            "0.2", "0.2.0-beta", "0.2.0-rc.0", "0.2.0-rc.99", "0.2.0-rc", "0.2.0.1", "", "v", "0.2.0-dev",
            "0.2.0 ", "a.b.c", "0.100.0", "0.0.100", "2001.0.0", "99999999999.0.0", "0.2.0-debug",
        ).forEach { assertNull(it, Version.parse(it)) }
    }

    @Test
    fun orderingFollowsTheSpec() {
        // dev < rc.1 < rc.2 < final
        val chain = listOf("0.0.0-dev", "0.2.0-rc.1", "0.2.0-rc.2", "0.2.0", "0.2.1", "0.9.9", "0.10.0", "1.0.0-rc.1", "1.0.0")
        for (i in chain.indices) for (j in chain.indices) {
            assertEquals("${chain[i]} vs ${chain[j]}", i.compareTo(j).coerceIn(-1, 1), v(chain[i]).compareTo(v(chain[j])).coerceIn(-1, 1))
        }
        assertTrue(v("0.9.9") < v("0.10.0"))
        assertTrue(v("1.0.0-rc.1") < v("1.0.0"))
        assertTrue(Version.DEV < v("0.0.0-rc.1"))
    }

    @Test
    fun installedNameFallsBackToDev() {
        assertEquals(v("0.9.0"), Version.parseInstalled("0.9.0"))
        assertEquals(Version.DEV, Version.parseInstalled("0.0.0-dev"))
        assertEquals(Version.DEV, Version.parseInstalled("0.1.0-poc"))
        assertEquals(Version.DEV, Version.parseInstalled("0.9.0-debug"))
        assertNotNull(Version.parseInstalled("0.1.0-poc-debug"))
    }
}
