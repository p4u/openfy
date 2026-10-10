package dev.openfy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun release(tag: String) = Release(
        tag = tag,
        version = Version.parse(tag)!!,
        name = "openfy ${tag.removePrefix("v")}",
        notes = "",
        htmlUrl = "",
        apkUrl = "https://github.com/p4u/openfy/releases/download/$tag/openfy-${tag.removePrefix("v")}.apk",
        apkSize = 1000,
        checksum = Checksum.Inline("0".repeat(64)),
    )

    private fun v(s: String) = Version.parse(s)!!

    // cadence

    @Test
    fun debugBuildsNeverCheckAutomatically() {
        assertEquals(UpdateDecision.Action.Skip, UpdateDecision.auto(true, 0, now))
    }

    @Test
    fun releaseChecksAtMostOncePer24Hours() {
        assertEquals(UpdateDecision.Action.Check, UpdateDecision.auto(false, 0, now))
        assertEquals(UpdateDecision.Action.Skip, UpdateDecision.auto(false, now - 23 * hour, now))
        assertEquals(UpdateDecision.Action.Skip, UpdateDecision.auto(false, now - 24 * hour + 1, now))
        assertEquals(UpdateDecision.Action.Check, UpdateDecision.auto(false, now - 24 * hour, now))
        assertEquals(UpdateDecision.Action.Check, UpdateDecision.auto(false, now - 100 * hour, now))
    }

    @Test
    fun clockGoingBackwardsCountsAsDue() {
        assertEquals(UpdateDecision.Action.Check, UpdateDecision.auto(false, now + hour, now))
    }

    // outcome

    @Test
    fun newerReleaseIsOffered() {
        val out = UpdateDecision.outcome(v("0.1.0"), release("v0.2.0"), null, manual = false)
        assertEquals(UpdateDecision.Outcome.Offer(release("v0.2.0")), out)
    }

    @Test
    fun sameVersionIsUpToDate() {
        assertEquals(UpdateDecision.Outcome.UpToDate, UpdateDecision.outcome(v("0.2.0"), release("v0.2.0"), null, true))
    }

    @Test
    fun olderReleaseIsNotOffered() {
        assertEquals(UpdateDecision.Outcome.UpToDate, UpdateDecision.outcome(v("0.2.0"), release("v0.1.9"), null, true))
        assertEquals(UpdateDecision.Outcome.UpToDate, UpdateDecision.outcome(v("0.2.0"), release("v0.2.0-rc.3"), null, false))
    }

    @Test
    fun skippedTagSilencesAutomaticChecksOnly() {
        assertEquals(UpdateDecision.Outcome.Skipped, UpdateDecision.outcome(v("0.1.0"), release("v0.2.0"), "v0.2.0", manual = false))
        assertTrue(UpdateDecision.outcome(v("0.1.0"), release("v0.2.0"), "v0.2.0", manual = true) is UpdateDecision.Outcome.Offer)
        // A later release is offered again.
        assertTrue(UpdateDecision.outcome(v("0.1.0"), release("v0.2.1"), "v0.2.0", manual = false) is UpdateDecision.Outcome.Offer)
    }

    @Test
    fun devBuildAlwaysSeesAnOffer() {
        assertTrue(UpdateDecision.outcome(Version.DEV, release("v0.0.1"), null, false) is UpdateDecision.Outcome.Offer)
    }

    @Test
    fun releaseCandidateUpgradesToFinal() {
        assertTrue(UpdateDecision.outcome(v("0.2.0-rc.2"), release("v0.2.0"), null, false) is UpdateDecision.Outcome.Offer)
    }
}
