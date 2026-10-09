package dev.openfy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UiAdaptScriptTest {
    private val template = "(function(){ var RULES_JSON = ${UiAdaptScript.PLACEHOLDER}; })();"

    @Test
    fun placeholderIsReplacedByTheRules() {
        val out = UiAdaptScript.inject(template, """{"version":1,"rules":[]}""")
        assertEquals("""(function(){ var RULES_JSON = {"version":1,"rules":[]}; })();""", out)
        assertFalse(out.contains("__OS_ADAPT_RULES__"))
    }

    @Test
    fun dollarAndBackslashInRulesSurvive() {
        // Selectors contain `$=`; a regex-based replace would eat `$` or `\`.
        val rules = """{"rules":[{"selectors":["[data-testid$=\"-hook\"]"]}]}"""
        val out = UiAdaptScript.inject(template, rules)
        assertTrue(out.contains("""[data-testid$=\"-hook\"]"""))
    }

    @Test
    fun templateWithoutOrWithTwoPlaceholdersIsRejected() {
        for (bad in listOf("var x = null;", "$template $template")) {
            try {
                UiAdaptScript.inject(bad, "{}")
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun rulesThatAreNotAJsonObjectAreRejected() {
        for (bad in listOf("", "not json", "[1,2]")) {
            try {
                UiAdaptScript.inject(template, bad)
                fail("expected IllegalArgumentException for '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}

class AdaptMessageTest {
    private fun parse(json: String) = AdaptMessage.parse(JSONObject(json))

    @Test
    fun parsesDismissed() {
        val m = parse("""{"t":"adapt","rule":"get-app-popover","action":"dismissed","bundle":"mobile-web-player.fe7b9205.js","sel":1}""")!!
        assertEquals("get-app-popover", m.rule)
        assertEquals("dismissed", m.action)
        assertEquals("mobile-web-player.fe7b9205.js", m.bundle)
        assertEquals(1, m.sel)
        assertNull(m.reason)
    }

    @Test
    fun parsesFailureWithReasonAndNullBundle() {
        val m = parse("""{"t":"adapt","rule":"r","action":"dismiss-failed","bundle":null,"reason":"no-dismiss-button"}""")!!
        assertNull(m.bundle)
        assertEquals("no-dismiss-button", m.reason)
    }

    @Test
    fun parsesInstalled() {
        val m = parse("""{"t":"adapt","rule":null,"action":"installed","bundle":null,"rules":["a","b"],"rejected":[{"id":"x","error":"e"}]}""")!!
        assertNull(m.rule)
        assertEquals(listOf("a", "b"), m.rules)
        assertEquals(1, m.rejected)
    }

    @Test
    fun rejectsUnknownActionsAndRulelessActions() {
        assertNull(parse("""{"t":"adapt","rule":"r","action":"blocked"}"""))
        assertNull(parse("""{"t":"adapt","rule":null,"action":"hidden"}"""))
        assertNull(parse("""{"t":"media","rule":"r","action":"hidden"}"""))
    }
}

class RuleSurvivalTest {
    private val b1 = "mobile-web-player.aaaa1111.js"
    private val b2 = "mobile-web-player.bbbb2222.js"

    @Test
    fun recordsPerBundleAndRule() {
        val s = RuleSurvival.fromJson(null)
        assertTrue(s.record(b1, "open-app-button", "hidden"))
        assertFalse(s.record(b1, "open-app-button", "hidden"))
        assertTrue(s.record(b1, "get-app-popover", "dismissed"))
        assertTrue(s.record(b2, "open-app-button", "hidden"))
        assertEquals(setOf("open-app-button", "get-app-popover"), s.matchedRules(b1))
        assertEquals(setOf("open-app-button"), s.matchedRules(b2))
        assertEquals(2, s.counts(b1, "open-app-button")!!.hidden)
    }

    @Test
    fun failuresCountButAreNotMissingFromMatched() {
        val s = RuleSurvival.fromJson(null)
        s.record(b1, "get-app-popover", "dismiss-failed")
        assertEquals(1, s.counts(b1, "get-app-popover")!!.failed)
    }

    @Test
    fun nullBundleIsRecordedAsUnknown() {
        val s = RuleSurvival.fromJson(null)
        s.record(null, "r", "hidden")
        assertEquals(listOf(RuleSurvival.UNKNOWN), s.bundleKeys())
        assertEquals(setOf("r"), s.matchedRules(null))
    }

    @Test
    fun unknownActionsAreIgnored() {
        val s = RuleSurvival.fromJson(null)
        assertFalse(s.record(b1, "r", "installed"))
        assertTrue(s.matchedRules(b1).isEmpty())
    }

    @Test
    fun roundTripsThroughJson() {
        val s = RuleSurvival.fromJson(null)
        s.record(b1, "a", "hidden")
        s.record(b1, "b", "dismissed")
        s.record(b2, "a", "dismiss-failed")
        val back = RuleSurvival.fromJson(s.toJson().toString())
        assertEquals(s.bundleKeys().sorted(), back.bundleKeys().sorted()) // org.json on the JVM does not keep key order
        assertEquals(RuleSurvival.Counts(hidden = 1), back.counts(b1, "a"))
        assertEquals(RuleSurvival.Counts(dismissed = 1), back.counts(b1, "b"))
        assertEquals(RuleSurvival.Counts(failed = 1), back.counts(b2, "a"))
    }

    @Test
    fun corruptJsonStartsOver() {
        assertTrue(RuleSurvival.fromJson("{not json").bundleKeys().isEmpty())
    }

    @Test
    fun keepsOnlyTheNewestBundles() {
        val s = RuleSurvival.fromJson(null)
        for (i in 1..RuleSurvival.MAX_BUNDLES + 5) s.record("bundle-$i.js", "r", "hidden")
        assertEquals(RuleSurvival.MAX_BUNDLES, s.bundleKeys().size)
        assertFalse("bundle-1.js" in s.bundleKeys())
        assertTrue("bundle-${RuleSurvival.MAX_BUNDLES + 5}.js" in s.bundleKeys())
    }
}
