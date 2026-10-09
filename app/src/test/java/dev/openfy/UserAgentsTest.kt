package dev.openfy

import androidx.webkit.UserAgentMetadata.BrandVersion
import androidx.webkit.UserAgentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {
    private val sampleDefaultUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UP1A.231005.007; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Mobile Safari/537.36"

    @Test
    fun parsesChromiumMajor() {
        assertEquals(131, UserAgents.chromiumMajor(sampleDefaultUa))
        assertNull(UserAgents.chromiumMajor("curl/8.0"))
    }

    @Test
    fun mobileArmUsesChromeReducedUa() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36",
            UserAgents.build(IdentityArm.MOBILE, 131),
        )
    }

    @Test
    fun desktopArmUsesChromeRequestDesktopSiteUa() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36",
            UserAgents.build(IdentityArm.DESKTOP, 131),
        )
    }

    @Test
    fun neitherArmLeaksWebViewTokens() {
        for (arm in IdentityArm.entries) {
            val ua = UserAgents.build(arm, 131)
            assertFalse(ua, "; wv)" in ua)
            assertFalse(ua, "Version/" in ua)
            assertFalse(ua, "Build/" in ua)
        }
    }

    private fun brand(name: String, major: String, full: String) =
        BrandVersion.Builder().setBrand(name).setMajorVersion(major).setFullVersion(full).build()

    private val base = UserAgentMetadata.Builder()
        .setBrandVersionList(
            listOf(
                brand("Not_A Brand", "24", "24.0.0.0"),
                brand("Chromium", "131", "131.0.6778.200"),
                brand("Android WebView", "131", "131.0.6778.200"),
            ),
        )
        .setPlatform("Android")
        .setPlatformVersion("14.0.0")
        .setModel("Pixel 7")
        .setMobile(true)
        .build()

    @Test
    fun brandRewriteReplacesOnlyWebViewBrandAndKeepsVersions() {
        val md = UaMetadataFactory.forArm(base, IdentityArm.MOBILE)
        assertEquals(
            listOf(
                brand("Not_A Brand", "24", "24.0.0.0"),
                brand("Chromium", "131", "131.0.6778.200"),
                brand("Google Chrome", "131", "131.0.6778.200"),
            ),
            md.brandVersionList,
        )
        assertEquals("Android", md.platform)
        assertTrue(md.isMobile)
        assertEquals("Pixel 7", md.model)
        assertEquals("14.0.0", md.platformVersion)
    }

    @Test
    fun desktopArmChangesPlatformFields() {
        val md = UaMetadataFactory.forArm(base, IdentityArm.DESKTOP)
        assertEquals("Linux", md.platform)
        assertFalse(md.isMobile)
        assertEquals("", md.model)
        assertEquals("", md.platformVersion)
        assertTrue(md.brandVersionList.any { it.brand == "Google Chrome" })
        assertFalse(md.brandVersionList.any { it.brand == "Android WebView" })
    }

    @Test
    fun detectsWhetherTheWebViewBrandIsPresent() {
        assertTrue(UaMetadataFactory.hasWebViewBrand(base))
        val rewritten = UaMetadataFactory.forArm(base, IdentityArm.MOBILE)
        assertFalse(UaMetadataFactory.hasWebViewBrand(rewritten))
        val empty = UserAgentMetadata.Builder().setBrandVersionList(emptyList()).build()
        assertFalse(UaMetadataFactory.hasWebViewBrand(empty))
    }

    @Test
    fun describesBrandsForTheStartupLog() {
        assertEquals(
            "[Not_A Brand/24, Chromium/131, Google Chrome/131]",
            UaMetadataFactory.describeBrands(UaMetadataFactory.forArm(base, IdentityArm.MOBILE)),
        )
    }
}
