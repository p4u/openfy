package dev.openspotify.shell

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/** Just a window for the shared WebView (DESIGN §2.1) plus a tiny debug menu. */
class MainActivity : AppCompatActivity() {
    private lateinit var webContainer: FrameLayout
    private var gateOk = false
    private var showDiagDialog = false
    private val handler = Handler(Looper.getMainLooper())

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "permission: POST_NOTIFICATIONS granted=$granted")
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val wv = WebViewHolder.webView()
            if (wv.canGoBack()) wv.goBack() else moveTaskToBack(true) // playback continues
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val problem = FeatureGate.check(this)
        if (problem != null) {
            showUnsupported(problem)
            return
        }
        gateOk = true

        val root = FrameLayout(this).apply { setBackgroundColor(WebViewConfig.BACKGROUND) }
        webContainer = FrameLayout(this)
        root.addView(webContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(buildOverflowButton(), overflowParams())
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        requestNotificationPermission()

        WebViewHolder.attach(this, webContainer)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (gateOk) handleIntent(intent)
    }

    /** Extras for adb automation: `osp_url` loads a URL, `osp_diag=true` runs diagnostics after the page loads. */
    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        val wantsDiag = intent.getBooleanExtra(EXTRA_DIAG, false)
        if (wantsDiag) {
            WebViewHolder.runAfterNextPageFinished {
                // Let the SPA settle; the diag itself is asynchronous.
                handler.postDelayed({ MediaBridge.requestDiag() }, DIAG_DELAY_MS)
            }
        }
        WebViewHolder.ensureLoaded(url)
    }

    override fun onStart() {
        super.onStart()
        if (!gateOk) return
        WebViewHolder.onActivityStart()
        MediaBridge.diagListener = ::onDiag
        if (MediaBridge.state.hasTrack) WebViewHolder.startPlaybackServiceIfPossible()
    }

    override fun onStop() {
        if (gateOk) {
            MediaBridge.diagListener = null
            WebViewHolder.onActivityStop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (gateOk) WebViewHolder.detach(this)
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // region debug menu

    private fun buildOverflowButton() = ImageButton(this).apply {
        setImageResource(R.drawable.ic_more_vert)
        setBackgroundResource(R.drawable.overflow_button_bg)
        contentDescription = getString(R.string.menu_content_description)
        alpha = 0.5f
        setOnClickListener { showMenu(it) }
    }

    private fun overflowParams(): FrameLayout.LayoutParams {
        // Left edge, vertically centred: the top bar and the bottom nav/now-playing bar belong to the page.
        val size = dp(32)
        return FrameLayout.LayoutParams(size, size, Gravity.START or Gravity.CENTER_VERTICAL).apply {
            setMargins(dp(2), 0, 0, 0)
        }
    }

    private fun showMenu(anchor: View) {
        val arm = WebViewHolder.arm
        val other = IdentityArm.entries.first { it != arm }
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_IDENTITY, 0, "Identity: ${arm.label} (switch to ${other.label})")
            menu.add(0, MENU_OPEN_LINK, 1, R.string.menu_open_link)
            menu.add(0, MENU_DIAG, 2, R.string.menu_diagnostics)
            menu.add(0, MENU_RELOAD, 3, R.string.menu_reload)
            val adaptOn = UiAdaptation.isEnabled(this@MainActivity)
            menu.add(0, MENU_ADAPT, 4, "UI adaptation: ${if (adaptOn) "on" else "off"} (switch ${if (adaptOn) "off" else "on"})")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_IDENTITY -> WebViewHolder.setArm(other)
                    MENU_OPEN_LINK -> promptForLink()
                    MENU_DIAG -> {
                        showDiagDialog = true
                        MediaBridge.requestDiag()
                    }
                    MENU_RELOAD -> WebViewHolder.reload()
                    MENU_ADAPT -> UiAdaptation.setEnabled(!UiAdaptation.isEnabled(this@MainActivity))
                }
                true
            }
            show()
        }
    }

    /** DESIGN §2.8: magic links from email open in the system browser; this is the way back in. */
    private fun promptForLink() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
        val input = EditText(this).apply {
            setHint(R.string.open_link_hint)
            setSingleLine()
            setText(clip?.takeIf { it.startsWith("https://") })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_link_title)
            .setView(input)
            .setPositiveButton(R.string.action_open) { _, _ ->
                val url = input.text.toString().trim()
                if (url.startsWith("https://") && NavigationPolicy.classify(url) == NavDecision.InShell) {
                    WebViewHolder.load(url)
                } else {
                    Toast.makeText(this, R.string.open_link_rejected, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onDiag(raw: String) {
        // logcat truncates long lines.
        raw.chunked(3500).forEachIndexed { i, part -> Log.i(TAG, "diag[$i]: $part") }
        if (!showDiagDialog) return
        showDiagDialog = false
        val pretty = try {
            JSONObject(raw).put("adaptSurvival", JSONObject(UiAdaptation.survivalJson())).toString(2)
        } catch (_: Exception) {
            raw
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_diagnostics)
            .setMessage(pretty)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    // endregion

    private fun showUnsupported(message: String) {
        Log.e(TAG, "gate: $message")
        val text = TextView(this).apply {
            text = "${getString(R.string.unsupported_title)}\n\n$message"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(WebViewConfig.BACKGROUND)
        }
        setContentView(text)
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    private companion object {
        const val EXTRA_URL = "osp_url"
        const val EXTRA_DIAG = "osp_diag"
        const val DIAG_DELAY_MS = 3_000L
        const val MENU_IDENTITY = 1
        const val MENU_OPEN_LINK = 2
        const val MENU_DIAG = 3
        const val MENU_RELOAD = 4
        const val MENU_ADAPT = 5
    }
}
