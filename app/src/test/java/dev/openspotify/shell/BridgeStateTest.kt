package dev.openspotify.shell

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStateTest {
    private fun BridgeState.apply(json: String, now: Long = 0) = reduce(JSONObject(json), now)

    private val metadata = """
        {"t":"metadata","title":"Song","artist":"Artist","album":"Album",
         "artwork":[{"src":"https://i.scdn.co/small","sizes":"64x64","type":"image/jpeg"},
                    {"src":"https://i.scdn.co/big","sizes":"640x640","type":"image/jpeg"},
                    {"src":"https://i.scdn.co/mid","sizes":"300x300"}]}
    """.trimIndent()

    @Test
    fun metadataPicksLargestArtwork() {
        val s = BridgeState().apply(metadata)
        assertEquals("Song", s.title)
        assertEquals("Artist", s.artist)
        assertEquals("Album", s.album)
        assertEquals("https://i.scdn.co/big", s.artworkUrl)
        assertTrue(s.hasTrack)
    }

    @Test
    fun emptyMetadataClearsTheTrack() {
        val s = BridgeState().apply(metadata).apply("""{"t":"metadata","title":"","artist":"","album":"","artwork":[]}""")
        assertFalse(s.hasTrack)
        assertNull(s.artworkUrl)
    }

    @Test
    fun newTrackResetsPositionButRepeatedMetadataKeepsIt() {
        var s = BridgeState().apply(metadata).apply("""{"t":"position","position":42.5,"duration":200,"rate":1}""", 10)
        assertEquals(42_500, s.positionMs)
        assertEquals(200_000, s.durationMs)
        s = s.apply(metadata)
        assertEquals(42_500, s.positionMs)
        s = s.apply(metadata.replace("Song", "Other"), 99)
        assertEquals(0, s.positionMs)
        assertEquals(0, s.durationMs)
    }

    @Test
    fun declaredStateIsUsedUntilElementEventsArrive() {
        var s = BridgeState().apply("""{"t":"state","declared":"playing"}""")
        assertTrue(s.isPlaying)
        s = s.apply("""{"t":"media","ev":"pause","paused":true,"ended":false,"pos":1.0,"dur":10.0,"rate":1}""")
        assertFalse(s.isPlaying) // element wins over the stale declared state
        s = s.apply("""{"t":"media","ev":"playing","paused":false,"ended":false,"pos":1.0,"dur":10.0,"rate":1}""")
        assertTrue(s.isPlaying)
        s = s.apply("""{"t":"media","ev":"ended","paused":false,"ended":true,"pos":10.0,"dur":10.0,"rate":1}""")
        assertFalse(s.isPlaying)
    }

    @Test
    fun mediaEventUpdatesPositionAndIgnoresNonFiniteValues() {
        var s = BridgeState().apply(
            """{"t":"media","ev":"timeupdate","paused":false,"ended":false,"pos":12.25,"dur":180.0,"rate":1.5}""",
            now = 500,
        )
        assertEquals(12_250, s.positionMs)
        assertEquals(180_000, s.durationMs)
        assertEquals(1.5, s.rate, 0.0)
        assertEquals(500, s.positionUpdatedAt)
        // JSON.stringify(NaN/Infinity) yields null.
        s = s.apply("""{"t":"media","ev":"durationchange","paused":false,"ended":false,"pos":null,"dur":null,"rate":1}""", 900)
        assertEquals(12_250, s.positionMs)
        assertEquals(180_000, s.durationMs)
        assertEquals(500, s.positionUpdatedAt)
    }

    @Test
    fun handlersReplaceTheSet() {
        var s = BridgeState().apply("""{"t":"handlers","actions":["nexttrack","previoustrack","seekto"]}""")
        assertEquals(setOf("nexttrack", "previoustrack", "seekto"), s.handlers)
        s = s.apply("""{"t":"handlers","actions":["nexttrack"]}""")
        assertEquals(setOf("nexttrack"), s.handlers)
    }

    @Test
    fun errorsAndDiagDoNotChangeState() {
        val s = BridgeState().apply(metadata)
        assertEquals(s, s.apply("""{"t":"error","where":"play","name":"NotAllowedError","message":"x"}"""))
        assertEquals(s, s.apply("""{"t":"diag","ua":"x"}"""))
        assertEquals(s, s.apply("""{"t":"unknown"}"""))
    }

    @Test
    fun trackIsLoadedWithMetadataOrWithAPlayingElement() {
        assertFalse(BridgeState().isLoaded)
        assertTrue(BridgeState().apply(metadata).isLoaded)
        // Metadata cleared (track boundary, ad break) while audio plays on: still loaded.
        val playing = BridgeState().apply(metadata)
            .apply("""{"t":"media","ev":"playing","paused":false,"ended":false,"pos":1.0,"dur":10.0,"rate":1}""")
        val cleared = playing.apply("""{"t":"metadata","title":"","artist":"","album":"","artwork":[]}""")
        assertFalse(cleared.hasTrack)
        assertTrue(cleared.isLoaded)
        // ...and not loaded once the element stops as well.
        val stopped = cleared.apply("""{"t":"media","ev":"pause","paused":true,"ended":false,"pos":1.0,"dur":10.0,"rate":1}""")
        assertFalse(stopped.isLoaded)
    }

    @Test
    fun installMessageOfANewDocumentClearsTheOldState() {
        val old = BridgeState().apply(metadata)
            .apply("""{"t":"handlers","actions":["nexttrack"]}""")
            .apply("""{"t":"media","ev":"playing","paused":false,"ended":false,"pos":5.0,"dur":10.0,"rate":1}""", 7)
        assertTrue(old.isPlaying)
        val fresh = old.apply("""{"t":"install","session":"polyfill","wrapped":[],"hooks":["play"],"elements":0}""")
        assertEquals(BridgeState(), fresh)
        assertFalse(fresh.isLoaded)
    }

    @Test
    fun pagehideStopsTheElementWithoutTouchingPosition() {
        val s = BridgeState()
            .apply("""{"t":"media","ev":"timeupdate","paused":false,"ended":false,"pos":12.0,"dur":100.0,"rate":1}""", 5)
            .apply("""{"t":"media","ev":"pagehide","paused":true,"ended":false}""", 9)
        assertFalse(s.isPlaying)
        assertEquals(12_000, s.positionMs)
        assertEquals(5, s.positionUpdatedAt)
    }

    @Test
    fun serviceStartsOnMetadataOrWhenAnElementStartsPlaying() {
        val withTrack = BridgeState().apply(metadata)
        val playing = withTrack.apply("""{"t":"media","ev":"playing","paused":false,"ended":false,"pos":0.0,"dur":10.0,"rate":1}""")
        fun msg(json: String) = JSONObject(json)
        assertTrue(shouldStartService(msg("""{"t":"metadata"}"""), withTrack))
        assertFalse(shouldStartService(msg("""{"t":"metadata"}"""), BridgeState()))
        assertTrue(shouldStartService(msg("""{"t":"media","ev":"playing"}"""), playing))
        assertFalse(shouldStartService(msg("""{"t":"media","ev":"timeupdate"}"""), playing))
        assertFalse(shouldStartService(msg("""{"t":"media","ev":"pause"}"""), withTrack))
        assertFalse(shouldStartService(msg("""{"t":"state"}"""), playing))
    }

    @Test
    fun parseRejectsGarbage() {
        assertNull(parseBridgeMessage("not json"))
        assertEquals("state", parseBridgeMessage("""{"t":"state"}""")?.getString("t"))
    }

    @Test
    fun artworkWithoutSizesFallsBackToLast() {
        val arr = JSONArray("""[{"src":"https://a/1"},{"src":"https://a/2"}]""")
        assertEquals("https://a/2", pickArtwork(arr))
        assertNull(pickArtwork(null))
    }
}
