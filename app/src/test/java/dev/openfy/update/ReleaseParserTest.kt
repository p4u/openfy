package dev.openfy.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    private val fixture: String =
        javaClass.getResourceAsStream("/update/release-latest.json")!!.bufferedReader().use { it.readText() }

    /** The fixture with [edit] applied. */
    private fun variant(edit: JSONObject.() -> Unit): String = JSONObject(fixture).apply(edit).toString()

    private fun JSONObject.asset(i: Int): JSONObject = getJSONArray("assets").getJSONObject(i)

    private fun parse(body: String, status: Int = 200, base: String = "https://github.com") =
        ReleaseParser.parse(status, body, "p4u/openfy", base)

    private fun ok(result: ParseResult): Release = (result as? ParseResult.Ok ?: error("expected Ok, got $result")).release

    @Test
    fun fixtureParses() {
        val r = ok(parse(fixture))
        assertEquals("v0.2.0", r.tag)
        assertEquals(Version(0, 2, 0, null), r.version)
        assertEquals("0.2.0", r.versionName)
        assertEquals("openfy 0.2.0", r.name)
        assertEquals("https://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk", r.apkUrl)
        assertEquals(4821337L, r.apkSize)
        assertEquals(Checksum.Inline("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"), r.checksum)
        assertTrue(r.notes.startsWith("## What's new"))
    }

    @Test
    fun digestIsPreferredOverSidecar() {
        assertTrue(ok(parse(fixture)).checksum is Checksum.Inline)
    }

    @Test
    fun missingDigestFallsBackToSidecar() {
        val r = ok(parse(variant { asset(0).remove("digest") }))
        assertEquals(
            Checksum.Sidecar("https://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk.sha256"),
            r.checksum,
        )
    }

    @Test
    fun nullOrMalformedDigestFallsBackToSidecar() {
        assertTrue(ok(parse(variant { asset(0).put("digest", JSONObject.NULL) })).checksum is Checksum.Sidecar)
        assertTrue(ok(parse(variant { asset(0).put("digest", "md5:abcd") })).checksum is Checksum.Sidecar)
        assertTrue(ok(parse(variant { asset(0).put("digest", "sha256:abcd") })).checksum is Checksum.Sidecar)
    }

    @Test
    fun noDigestAndNoSidecarIsRefused() {
        val body = variant {
            asset(0).remove("digest")
            put("assets", JSONArray().put(asset(0)))
        }
        assertEquals(ParseResult.Invalid("release has no checksum"), parse(body))
    }

    @Test
    fun missingApkAssetIsIgnored() {
        val body = variant { put("assets", JSONArray().put(asset(1))) } // only the .sha256
        assertTrue(parse(body) is ParseResult.Ignored)
        assertTrue(parse(variant { put("assets", JSONArray()) }) is ParseResult.Ignored)
        assertTrue(parse(variant { remove("assets") }) is ParseResult.Ignored)
    }

    @Test
    fun twoApksAreRejected() {
        val second = JSONObject(JSONObject(fixture).asset(0).toString()).put("name", "openfy-0.2.0-extra.apk")
        val body = variant { getJSONArray("assets").put(second) }
        assertTrue(parse(body) is ParseResult.Ignored)
    }

    @Test
    fun draftAndPreReleaseAreIgnored() {
        assertTrue(parse(variant { put("draft", true) }) is ParseResult.Ignored)
        assertTrue(parse(variant { put("prerelease", true) }) is ParseResult.Ignored)
    }

    @Test
    fun assetVersionMustEqualTag() {
        val body = variant { asset(0).put("name", "openfy-0.3.0.apk") }
        assertTrue(parse(body) is ParseResult.Ignored)
        assertTrue(parse(variant { put("tag_name", "v0.2.1") }) is ParseResult.Ignored)
    }

    @Test
    fun badTagIsIgnored() {
        assertTrue(parse(variant { put("tag_name", "nightly") }) is ParseResult.Ignored)
        assertTrue(parse(variant { put("tag_name", "v0.2.0-beta") }) is ParseResult.Ignored)
    }

    @Test
    fun oversizedOrEmptyApkIsRefused() {
        assertTrue(parse(variant { asset(0).put("size", SizeCap.MAX_APK_BYTES + 1) }) is ParseResult.Invalid)
        assertTrue(parse(variant { asset(0).put("size", 0) }) is ParseResult.Invalid)
        assertTrue(parse(variant { asset(0).remove("size") }) is ParseResult.Invalid)
        assertTrue(parse(variant { asset(0).put("size", SizeCap.MAX_APK_BYTES) }) is ParseResult.Ok)
    }

    @Test
    fun notFoundIsNoReleaseNotAnError() {
        val body = """{"message":"Not Found","documentation_url":"https://docs.github.com/rest","status":"404"}"""
        assertEquals(ParseResult.NoRelease, parse(body, 404))
    }

    @Test
    fun otherStatusesAndGarbageAreInvalid() {
        assertTrue(parse("{}", 403) is ParseResult.Invalid)
        assertTrue(parse("<html>", 200) is ParseResult.Invalid)
        assertTrue(parse("[]", 200) is ParseResult.Invalid)
    }

    private val apkUrl = "https://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk"

    @Test
    fun apkUrlMustBeInTheConfiguredRepository() {
        listOf(
            "https://github.com/evil/openfy/releases/download/v0.2.0/openfy-0.2.0.apk",
            "https://github.com/p4u/other/releases/download/v0.2.0/openfy-0.2.0.apk",
            "https://github.com/p4u/openfy-evil/releases/download/v0.2.0/openfy-0.2.0.apk",
            "https://github.com/p4u/openfy/releases/download/v0.1.0/openfy-0.2.0.apk",
            "https://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk?x=1",
            "https://github.com/p4u/openfy/releases/download/v0.2.0/other.apk",
            "https://github.com.evil.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk",
            "https://evil.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk",
            "http://github.com/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk",
            "https://github.com/p4u/openfy/archive/v0.2.0.apk",
        ).forEach { url ->
            assertEquals(url, ParseResult.Invalid("APK asset is hosted outside the release"), parse(variant { asset(0).put("browser_download_url", url) }))
        }
    }

    @Test
    fun repositoryMatchIsCaseInsensitive() {
        val url = "https://github.com/P4U/OpenFy/releases/download/v0.2.0/openfy-0.2.0.apk"
        assertEquals(url, ok(parse(variant { asset(0).put("browser_download_url", url) })).apkUrl)
    }

    @Test
    fun sidecarUrlMustBeInTheConfiguredRepository() {
        val body = variant {
            asset(0).remove("digest")
            asset(1).put("browser_download_url", "https://github.com/evil/openfy/releases/download/v0.2.0/openfy-0.2.0.apk.sha256")
        }
        assertEquals(ParseResult.Invalid("checksum asset is hosted outside the release"), parse(body))
        val swapped = variant { asset(1).put("browser_download_url", "$apkUrl.sha256.txt") }
        assertTrue(parse(swapped) is ParseResult.Invalid)
    }

    @Test
    fun debugTestOriginIsPinnedTheSameWay() {
        val base = "http://127.0.0.1:8765"
        val local = "$base/p4u/openfy/releases/download/v0.2.0/openfy-0.2.0.apk"
        val body = variant {
            asset(0).put("browser_download_url", local)
            asset(1).put("browser_download_url", "$local.sha256")
        }
        assertEquals(local, ok(parse(body, base = base)).apkUrl)
        // The same release is refused by a production parser, and github.com URLs are refused by the test one.
        assertTrue(parse(body) is ParseResult.Invalid)
        assertTrue(parse(fixture, base = base) is ParseResult.Invalid)
    }

    @Test
    fun longNotesAreCut() {
        val r = ok(parse(variant { put("body", "x".repeat(5000)) }))
        assertEquals(ReleaseParser.MAX_NOTES_CHARS + 1, r.notes.length)
        assertTrue(r.notes.endsWith("…"))
    }
}
