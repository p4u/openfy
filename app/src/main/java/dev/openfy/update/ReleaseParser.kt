package dev.openfy.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Outcome of reading a `releases/latest` response (docs/UPDATER.md §2.1). */
sealed interface ParseResult {
    data class Ok(val release: Release) : ParseResult

    /** HTTP 404: the repo has no published release yet. Not an error. */
    data object NoRelease : ParseResult

    /** A release exists but is not one we offer (draft, pre-release, no or several APKs, asset/tag mismatch). */
    data class Ignored(val reason: String) : ParseResult

    /** The response or release is malformed or unsafe (no checksum, absurd size, bad JSON). Shown to the user. */
    data class Invalid(val reason: String) : ParseResult
}

/**
 * Pure: GitHub `releases/latest` JSON to [Release].
 *
 * Asset URLs are pinned: the APK and its sidecar must be exactly
 * `<downloadBase>/<repo>/releases/download/<tag>/<asset name>` (owner/repo compared case-insensitively), where
 * [downloadBase] is `https://github.com` (a debug build with a test origin passes that origin instead).
 * Anything else is [ParseResult.Invalid].
 */
object ReleaseParser {
    private val APK_NAME = Regex("""^openfy-(.+)\.apk$""")
    const val MAX_NOTES_CHARS = 4000

    fun parse(status: Int, body: String, repo: String, downloadBase: String): ParseResult {
        if (status == 404) return ParseResult.NoRelease
        if (status != 200) return ParseResult.Invalid("GitHub answered HTTP $status")
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return ParseResult.Invalid("unreadable release JSON")
        }
        return try {
            fromJson(json, repo, downloadBase)
        } catch (e: JSONException) {
            ParseResult.Invalid("malformed release JSON")
        }
    }

    private fun fromJson(json: JSONObject, repo: String, downloadBase: String): ParseResult {
        if (json.optBoolean("draft", false)) return ParseResult.Ignored("release is a draft")
        if (json.optBoolean("prerelease", false)) return ParseResult.Ignored("release is a pre-release")
        val tag = json.optString("tag_name", "")
        val version = Version.parse(tag) ?: return ParseResult.Ignored("tag '$tag' is not a release version")
        val tagVersion = tag.removePrefix("v")

        val assets = json.optJSONArray("assets") ?: JSONArray()
        val all = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        val apks = all.filter { APK_NAME.matches(it.optString("name")) }
        if (apks.size != 1) return ParseResult.Ignored("release has no usable APK (${apks.size} candidates)")
        val apk = apks[0]
        val apkName = apk.getString("name")
        if (APK_NAME.matchEntire(apkName)!!.groupValues[1] != tagVersion) {
            return ParseResult.Ignored("asset/tag mismatch: $apkName vs $tag")
        }

        val url = apk.optString("browser_download_url", "")
        if (url.isEmpty()) return ParseResult.Invalid("APK asset has no download URL")
        val pinned = "$downloadBase/$repo/releases/download/"
        fun pinnedTo(assetUrl: String, assetName: String): Boolean =
            assetUrl.startsWith(pinned, ignoreCase = true) && assetUrl.substring(pinned.length) == "$tag/$assetName"
        if (!pinnedTo(url, apkName)) return ParseResult.Invalid("APK asset is hosted outside the release")
        val size = apk.optLong("size", -1)
        SizeCap.refusal(size)?.let { return ParseResult.Invalid(it) }

        val inline = Sha256.fromDigestField(apk.optString("digest", "").ifEmpty { null })
        val sidecarName = "$apkName.sha256"
        val sidecar = all.firstOrNull { it.optString("name") == sidecarName }
            ?.optString("browser_download_url", "")?.takeIf { it.isNotEmpty() }
        if (sidecar != null && !pinnedTo(sidecar, sidecarName)) {
            return ParseResult.Invalid("checksum asset is hosted outside the release")
        }
        val checksum = when {
            inline != null -> Checksum.Inline(inline)
            sidecar != null -> Checksum.Sidecar(sidecar)
            else -> return ParseResult.Invalid("release has no checksum")
        }

        return ParseResult.Ok(
            Release(
                tag = tag,
                version = version,
                name = json.optString("name", "").ifBlank { tag },
                notes = trimNotes(json.optString("body", "")),
                htmlUrl = json.optString("html_url", ""),
                apkUrl = url,
                apkSize = size,
                checksum = checksum,
            ),
        )
    }

    private fun trimNotes(body: String): String {
        val text = body.trim()
        return if (text.length <= MAX_NOTES_CHARS) text else text.take(MAX_NOTES_CHARS).trimEnd() + "…"
    }
}
