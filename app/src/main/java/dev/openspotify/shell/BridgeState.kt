package dev.openspotify.shell

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Native mirror of what `media-bridge.js` observed (DESIGN §2.6). */
data class BridgeState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** `playbackState` as declared by the page: none, paused or playing. */
    val declaredState: String = "none",
    /** What the media element itself says; null until an element event was seen. */
    val elementPlaying: Boolean? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val rate: Double = 1.0,
    /** Monotonic time ([android.os.SystemClock.elapsedRealtime]) at which [positionMs] was observed. */
    val positionUpdatedAt: Long = 0,
    val handlers: Set<String> = emptySet(),
) {
    val hasTrack: Boolean get() = title != null

    /**
     * A track is "loaded" if the page published metadata **or** a media element is playing (DESIGN §2.7).
     * Metadata can be cleared for a while during a track change or an ad break while audio plays on.
     */
    val isLoaded: Boolean get() = hasTrack || isPlaying

    /** The element is the truth; the declared state is only used before any element event. */
    val isPlaying: Boolean get() = elementPlaying ?: (declaredState == "playing")
}

/** Folds one bridge message into the state. Messages that carry no state (diag, error) return `this`. */
fun BridgeState.reduce(msg: JSONObject, now: Long): BridgeState = when (msg.optString("t")) {
    // First message of every document (the script runs at document start): forget the previous document.
    "install" -> BridgeState()
    "metadata" -> reduceMetadata(msg, now)
    "state" -> copy(declaredState = msg.optString("declared", "none"))
    "position" -> withPosition(msg.finite("position"), msg.finite("duration"), msg.finite("rate"), now)
    "handlers" -> copy(handlers = msg.optJSONArray("actions").toStringSet())
    "media" -> reduceMedia(msg, now)
    else -> this
}

private fun BridgeState.reduceMetadata(msg: JSONObject, now: Long): BridgeState {
    val title = msg.str("title")
    val artist = msg.str("artist")
    if (title == null && artist == null) {
        return copy(
            title = null, artist = null, album = null, artworkUrl = null,
            positionMs = 0, durationMs = 0, positionUpdatedAt = now,
        )
    }
    val changed = title != this.title || artist != this.artist
    return copy(
        title = title ?: "",
        artist = artist,
        album = msg.str("album"),
        artworkUrl = pickArtwork(msg.optJSONArray("artwork")),
        positionMs = if (changed) 0 else positionMs,
        durationMs = if (changed) 0 else durationMs,
        positionUpdatedAt = if (changed) now else positionUpdatedAt,
    )
}

private fun BridgeState.reduceMedia(msg: JSONObject, now: Long): BridgeState {
    val paused = msg.optBoolean("paused", true)
    val ended = msg.optBoolean("ended", false)
    val playing = !paused && !ended
    return copy(elementPlaying = playing)
        .withPosition(msg.finite("pos"), msg.finite("dur"), msg.finite("rate"), now)
}

/** Seconds from the page; NaN and Infinity (serialized as null by JSON.stringify) mean "unknown". */
private fun BridgeState.withPosition(posSec: Double?, durSec: Double?, rate: Double?, now: Long): BridgeState =
    copy(
        positionMs = posSec?.let { (it * 1000).toLong() } ?: positionMs,
        durationMs = durSec?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: durationMs,
        rate = rate?.takeIf { it > 0 } ?: this.rate,
        positionUpdatedAt = if (posSec != null) now else positionUpdatedAt,
    )

private fun JSONObject.finite(name: String): Double? =
    if (isNull(name)) null else optDouble(name).takeIf { it.isFinite() }

private fun JSONObject.str(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONArray?.toStringSet(): Set<String> =
    if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotEmpty) }.toSet()

/** Picks the largest declared artwork; `sizes` is a space separated list like `"96x96 512x512"`. */
fun pickArtwork(artwork: JSONArray?): String? {
    if (artwork == null) return null
    val sizeRe = Regex("""(\d+)x(\d+)""")
    var best: String? = null
    var bestArea = -1L
    for (i in 0 until artwork.length()) {
        val item = artwork.optJSONObject(i) ?: continue
        val src = item.str("src") ?: continue
        val area = sizeRe.findAll(item.optString("sizes"))
            .maxOfOrNull { it.groupValues[1].toLong() * it.groupValues[2].toLong() } ?: 0L
        if (area >= bestArea) {
            best = src
            bestArea = area
        }
    }
    return best
}

/** Parses a raw bridge message; null if it is not a JSON object. */
fun parseBridgeMessage(raw: String): JSONObject? = try {
    JSONObject(raw)
} catch (_: JSONException) {
    null
}
