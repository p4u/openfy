package dev.openspotify.shell

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

/**
 * Native end of `ui-adapt.js` (DESIGN §2.9): the "UI adaptation" mode, on by default, switchable from the
 * debug menu. The script has its own document-start handle so that switching it off can really remove it.
 * Everything here runs on the main thread.
 *
 * This object only injects the script and records what it reports. It never inspects or blocks a request and
 * it does not touch playback.
 */
object UiAdaptation {
    private const val PREFS = "open_spotify"
    private const val KEY_ENABLED = "ui_adaptation"
    private const val KEY_SURVIVAL = "adapt_survival"
    private const val SCRIPT_ASSET = "ui-adapt.js"
    private const val RULES_ASSET = "ui-rules.json"
    private val ORIGINS = setOf("https://open.spotify.com")

    private var appContext: Context? = null
    private var webView: WebView? = null
    private var handle: ScriptHandler? = null
    private var script: String? = null
    private var survival: RuleSurvival? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    /** Called for every new main WebView, before its first load (like [MediaBridge.install]). */
    fun install(webView: WebView, context: Context) {
        appContext = context.applicationContext
        this.webView = webView
        handle = null
        if (!isEnabled(context)) {
            Log.i(TAG, "adapt: off (ui_adaptation=false), script not injected")
            return
        }
        inject()
    }

    /** The WebView is gone (destroyed). */
    fun detach() {
        webView = null
        handle = null
    }

    /** Debug menu: persist the choice, remove or re-add the script, reload so it takes effect. */
    fun setEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
        handle?.remove()
        handle = null
        if (enabled) inject()
        Log.i(TAG, "adapt: ${if (enabled) "on" else "off"}, reloading")
        WebViewHolder.reload()
    }

    private fun inject() {
        val wv = webView ?: return
        val ctx = appContext ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "adapt: DOCUMENT_START_SCRIPT unsupported, running unadapted") // FeatureGate already requires it
            return
        }
        val js = script ?: buildScript(ctx)?.also { script = it } ?: return
        handle = WebViewCompat.addDocumentStartJavaScript(wv, js, ORIGINS)
        Log.i(TAG, "adapt: script registered for $ORIGINS")
    }

    private fun buildScript(ctx: Context): String? = try {
        val template = ctx.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }
        val rules = ctx.assets.open(RULES_ASSET).bufferedReader().use { it.readText() }
        UiAdaptScript.inject(template, rules)
    } catch (e: Exception) {
        // A broken asset must not take the browser down; the shell simply runs unadapted.
        Log.e(TAG, "adapt: cannot prepare script, running unadapted", e)
        null
    }

    /** `t:"adapt"` messages from the page (routed by [MediaBridge]). */
    fun onMessage(msg: JSONObject) {
        val m = AdaptMessage.parse(msg)
        if (m == null) {
            Log.w(TAG, "adapt: unparseable ${msg.toString().take(300)}")
            return
        }
        if (m.action == "installed") {
            Log.i(TAG, "adapt: installed rules=${m.rules} rejected=${m.rejected}")
            return
        }
        Log.i(TAG, "adapt: rule=${m.rule} action=${m.action} bundle=${m.bundle} sel=${m.sel}" + (m.reason?.let { " reason=$it" } ?: ""))
        val rule = m.rule ?: return
        val s = survival ?: loadSurvival().also { survival = it }
        if (s.record(m.bundle, rule, m.action)) {
            Log.i(TAG, "adapt: survival ${s.describe(m.bundle)}")
        }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit { putString(KEY_SURVIVAL, s.toJson().toString()) }
    }

    /** The persisted "rule survival across deploys" record as pretty JSON (for the Diagnostics dialog). */
    fun survivalJson(): String = (survival ?: loadSurvival().also { survival = it }).toJson().toString(2)

    private fun loadSurvival(): RuleSurvival {
        val raw = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_SURVIVAL, null)
        return RuleSurvival.fromJson(raw)
    }
}

/** Builds the injected script: the rules JSON replaces the one placeholder in `ui-adapt.js`. */
object UiAdaptScript {
    const val PLACEHOLDER = "/*__OS_ADAPT_RULES__*/null"

    /** @throws IllegalArgumentException if the template has not exactly one placeholder or the rules are not a JSON object. */
    fun inject(template: String, rulesJson: String): String {
        val count = template.windowed(PLACEHOLDER.length).count { it == PLACEHOLDER }
        require(count == 1) { "ui-adapt.js must contain the rules placeholder exactly once (found $count)" }
        val trimmed = rulesJson.trim()
        try {
            JSONObject(trimmed)
        } catch (e: JSONException) {
            throw IllegalArgumentException("ui-rules.json is not a JSON object", e)
        }
        // A JSON object literal is a JavaScript expression; indexOf/substring keeps '$' and '\' in the rules intact.
        val at = template.indexOf(PLACEHOLDER)
        return template.substring(0, at) + trimmed + template.substring(at + PLACEHOLDER.length)
    }
}

/** One `t:"adapt"` message (see the header of `ui-adapt.js`). */
data class AdaptMessage(
    val rule: String?,
    val action: String,
    val bundle: String?,
    val sel: Int?,
    val reason: String?,
    val rules: List<String>,
    val rejected: Int,
) {
    companion object {
        private val ACTIONS = setOf("hidden", "dismissed", "dismiss-failed", "installed")

        fun parse(msg: JSONObject): AdaptMessage? {
            if (msg.optString("t") != "adapt") return null
            val action = msg.optString("action")
            if (action !in ACTIONS) return null
            val rule = if (msg.isNull("rule")) null else msg.optString("rule").takeIf { it.isNotEmpty() }
            if (action != "installed" && rule == null) return null
            val rules = msg.optJSONArray("rules")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            return AdaptMessage(
                rule = rule,
                action = action,
                bundle = if (msg.isNull("bundle")) null else msg.optString("bundle").takeIf { it.isNotEmpty() },
                sel = if (msg.has("sel")) msg.optInt("sel") else null,
                reason = if (msg.has("reason")) msg.optString("reason") else null,
                rules = rules,
                rejected = msg.optJSONArray("rejected")?.length() ?: 0,
            )
        }
    }
}

/**
 * "Rule survival across deploys": per web-player bundle hash, how often each rule matched
 * (hidden), dismissed a popup or failed to. The newest [MAX_BUNDLES] bundles are kept.
 */
class RuleSurvival private constructor(private val bundles: LinkedHashMap<String, MutableMap<String, Counts>>) {
    data class Counts(val hidden: Int = 0, val dismissed: Int = 0, val failed: Int = 0) {
        val matched: Boolean get() = hidden + dismissed + failed > 0
    }

    /** @return true if this (bundle, rule) pair had no record before. */
    fun record(bundle: String?, rule: String, action: String): Boolean {
        val key = bundle ?: UNKNOWN
        val rules = bundles.getOrPut(key) {
            while (bundles.size >= MAX_BUNDLES) bundles.remove(bundles.keys.first())
            linkedMapOf()
        }
        val old = rules[rule]
        val c = old ?: Counts()
        rules[rule] = when (action) {
            "hidden" -> c.copy(hidden = c.hidden + 1)
            "dismissed" -> c.copy(dismissed = c.dismissed + 1)
            "dismiss-failed" -> c.copy(failed = c.failed + 1)
            else -> return false
        }
        return old == null
    }

    fun matchedRules(bundle: String?): Set<String> =
        bundles[bundle ?: UNKNOWN]?.filterValues { it.matched }?.keys ?: emptySet()

    fun bundleKeys(): List<String> = bundles.keys.toList()

    fun counts(bundle: String?, rule: String): Counts? = bundles[bundle ?: UNKNOWN]?.get(rule)

    fun describe(bundle: String?) = "bundle=${bundle ?: UNKNOWN} matched=${matchedRules(bundle).sorted()}"

    fun toJson(): JSONObject = JSONObject().apply {
        bundles.forEach { (bundle, rules) ->
            put(
                bundle,
                JSONObject().also { o ->
                    rules.forEach { (rule, c) ->
                        o.put(rule, JSONObject().put("hidden", c.hidden).put("dismissed", c.dismissed).put("failed", c.failed))
                    }
                },
            )
        }
    }

    companion object {
        const val UNKNOWN = "unknown"
        const val MAX_BUNDLES = 20

        fun fromJson(raw: String?): RuleSurvival {
            val result = RuleSurvival(linkedMapOf())
            val root = try {
                JSONObject(raw ?: return result)
            } catch (_: JSONException) {
                return result // corrupt record: start over rather than crash
            }
            for (bundle in root.keys()) {
                val o = root.optJSONObject(bundle) ?: continue
                val rules = linkedMapOf<String, Counts>()
                for (rule in o.keys()) {
                    val c = o.optJSONObject(rule) ?: continue
                    rules[rule] = Counts(c.optInt("hidden"), c.optInt("dismissed"), c.optInt("failed"))
                }
                result.bundles[bundle] = rules
            }
            return result
        }
    }
}
