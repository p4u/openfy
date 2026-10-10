# openfy — Design (v0.4)

_openfy, formerly open-spotify._

Research project (CS degree): an Android app that is a **browser-like shell** around the
official Spotify Web Player (`https://open.spotify.com`). The user signs in with their own
Spotify account. All content, artwork, layout and styling come from the web player. The shell
presents Chrome-equivalent capability signals to Spotify and adds a native media integration on top.

Inputs: [`research/web-recon.md`](research/web-recon.md),
review [`reviews/design-review-fable.md`](reviews/design-review-fable.md) (applied in v0.2) and the PoC code
review [`reviews/poc-code-review-fable.md`](reviews/poc-code-review-fable.md) (applied in v0.3). v0.3 fixes the
one false premise the first emulator run exposed: Android WebView has **no** Media Session API. v0.4 adds one
deliberate deviation from the v0.3 non-goals, the "UI adaptation" mode (§2.9). Measured results
are in [`poc-results.md`](poc-results.md). The changelog is at the end.

## 1. Goals / non-goals

**Goals**
- G1. Load and use the full web player (browse, search, library, music and podcasts).
- G2. Log in with Spotify email/password. The session persists across app and process restarts.
- G3. Play DRM-protected audio through the web player's own EME/Widevine pipeline.
- G4. Native media integration: notification, lock screen and headset buttons with cover art,
  plus background playback that survives Activity destruction.
- G5. Standard web-platform behaviour and **Chrome-equivalent capability signals** (UA string, UA client
  hints, cookies, EME, Media Session API), with no private APIs. The shell **remains honestly identifiable as a
  WebView host** through `X-Requested-With`, which Android WebView sends on every request and which
  cannot be removed. We do not try to hide it.

**Non-goals (explicit, for ethics/ToS)**
- No ad blocking, no network blocking and no changes to playback, ever. No content modification or DOM rewriting
  of Spotify's UI **except** the one documented, switchable, default-on deviation in §2.9 ("UI adaptation"): it hides
  or dismisses first-party app-install nags and first-party Premium upsell banners, and nothing else.
- No downloading or recording of streams, and no DRM circumvention.
- No attempt to defeat Google's embedded-WebView OAuth policy (`disallowed_useragent`).
- No private Spotify APIs and no Spotify Web API keys. Native code's only network access **towards Spotify** is
  fetching the artwork URL the page itself published through `navigator.mediaSession`. The only other native
  network access is the self-updater (`docs/UPDATER.md`): it talks exclusively to GitHub (`api.github.com` and the
  release-asset hosts) over HTTPS to check for and download new releases of this app, at most once a day, sends no
  identifiers beyond its own version string, and never touches the Spotify session.
- No spoofing beyond the UA string and UA client hints (§2.2). Other JS-visible differences from Chrome
  (`navigator.platform`, `navigator.languages`, a JS-visible Media Session polyfill) are measured and recorded,
  not patched.

## 2. Architecture

```
            ┌───────────── PlaybackService (foreground, mediaPlayback) ─────────────┐
            │  WebViewHolder ── owns ──► BrowserWebView (MutableContextWrapper)     │
            │                              ├─ ShellWebViewClient (nav policy,       │
            │                              │    onRenderProcessGone → rebuild)       │
            │                              ├─ ShellChromeClient (EME grant, windows) │
            │                              └─ doc-start JS media-bridge.js           │
            │                                       │ WebMessageListener (UI thread)  │
            │  MediaBridge ◄────────────────────────┘                                 │
            │     │ state                   ▲ evaluateJavascript(invoke …)           │
            │     ▼                         │                                         │
            │  MediaSessionCompat ── callbacks (play/pause/next/prev/seek)            │
            │  MediaStyle notification (session token)                                │
            └─────────────────────────────────────────────────────────────────────────┘
                        ▲ attach / detach view
            MainActivity (singleTask, configChanges) — just a window for the WebView
```

### 2.1 WebView lifetime (review B1)
- The WebView is created **once**, in an app-scoped `WebViewHolder` (a process singleton used by
  `PlaybackService` and `MainActivity`), with a `MutableContextWrapper(applicationContext)`.
- `MainActivity.onCreate`: `wrapper.baseContext = activity`, then detach the WebView from any old parent
  and `addView()` it. `onDestroy`: `removeView()` and `wrapper.baseContext = applicationContext`.
  The instance stays alive.
- Manifest: `launchMode="singleTask"`,
  `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|keyboardHidden|density"`.
- `PlaybackService.onTaskRemoved()`: keep running if playing, otherwise stop the service and destroy the WebView.
- `ShellWebViewClient.onRenderProcessGone()`: destroy the dead WebView, build a new one, reload the last URL,
  and return `true`. Cookies survive, so the user stays logged in.
- Renderer priority stays at the default `RENDERER_PRIORITY_IMPORTANT`.

### 2.2 Browser identity (review B2, B3, M6)
Two profiles. Both are consistent across the UA string, UA-CH and viewport.

**Arm A — Chrome Mobile (default until H1 decides):**
- Parse the Chromium **major** version from `WebSettings.getDefaultUserAgent()` and do not spoof it.
- UA: `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<major>.0.0.0 Mobile Safari/537.36`
  (Chrome's reduced UA).
- UA-CH: `WebSettingsCompat.getUserAgentMetadata()`. Copy it and replace the brand `"Android WebView"`
  with `"Google Chrome"` in `brandVersionList` and `fullVersionList`. Keep the grease entry and versions
  exactly as generated. Keep `platform="Android"` and `mobile=true`. Then `setUserAgentMetadata()`.

**Arm B — Chrome "Request desktop site":**
- UA: `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<major>.0.0.0 Safari/537.36`
- UA-CH: same brand substitution, `platform="Linux"`, `mobile=false`, `model=""`, `platformVersion=""`.
- `useWideViewPort=true`, `loadWithOverviewMode=true` (both arms; Arm A needs it for pages without a viewport
  tag, review m1).
- `formFactors` cannot be set through the public androidx.webkit API (`@RestrictTo`), so Arm B reports
  `"Mobile"` where Chrome's "Request desktop site" reports `"Desktop"`. Accepted and recorded in H6 (review m2).

The arm can be switched from a debug menu. Switching reloads the page. The startup log records the UA, platform,
`mobile`, and both the default and the rewritten brand lists, and warns if the default list has no
`"Android WebView"` entry (the rewrite would be a no-op, review m3).

`X-Requested-With: <package>` is still sent and cannot be removed: the Chromium removal was cancelled,
and `setRequestedWithHeaderOriginAllowList` is a no-op since androidx.webkit 1.15. This is documented, not hidden.

### 2.3 WebView configuration
- JS, DOM storage and database on. `mediaPlaybackRequiresUserGesture = true` (the browser default).
  Switch it to `false` only if H4 shows `NotAllowedError` on native-initiated play.
- `CookieManager.setAcceptCookie(true)`, `setAcceptThirdPartyCookies(true)` (only matters for cross-site
  iframes such as Facebook). Call `flush()` on Activity stop.
- Multiple windows: `setSupportMultipleWindows(true)` and `javaScriptCanOpenWindowsAutomatically = true`.
  `onCreateWindow` creates a popup WebView (same settings and identity) in a dialog, delivered via
  `WebViewTransport`. `onCloseWindow` dismisses it.
- Dark background `#121212`.
- `setWebContentsDebuggingEnabled(true)` in debug builds. Both this and the `CookieManager` setup run when the
  first WebView is created, not in `Application.onCreate`, so the "unsupported WebView" screen can show (review m10).
- Default JS dialogs (`alert`/`confirm`/`prompt`) are cancelled while no Activity is attached, because the
  default implementation would crash on the application context (review m12).
- Hard requirements, checked at start with a clear error screen if missing:
  `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`, `USER_AGENT_METADATA`, and WebView/Chromium major ≥ 120.

### 2.4 Navigation policy (main-frame only, review m3)
- In-shell: `open.spotify.com`, `accounts.spotify.com`, `challenge.spotify.com`, `www.spotify.com`,
  `*.spotify.com`, plus IdP hosts for popups (`*.facebook.com`, `appleid.apple.com`).
- Host matching uses a lenient scheme/authority parser rather than `java.net.URI`, which rejects characters
  Chromium accepts (`|`, `^`, `{`) and would send a legitimate Spotify URL to the system browser (review m8).
  The authority ends at `/`, `\`, `?` or `#`, and the host is what follows the last `@`.
- `spotify:` and `intent:` schemes are blocked and logged.
- Everything else opens in the system browser. This includes `accounts.google.com`: Google sign-in opens in the
  system browser and does not complete in the shell (see §2.8).
- Back: `OnBackPressedCallback` → `goBack()` if possible, otherwise move the task to back (playback continues).

### 2.5 DRM (review m1)
- `onPermissionRequest`: grant only `RESOURCE_PROTECTED_MEDIA_ID`, and only for origin host
  `open.spotify.com`. Deny everything else.
- The first playback triggers per-origin Widevine provisioning. L3 is sufficient.
- Diagnostics (debug log only): `MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)` and the `securityLevel` property.

### 2.6 Media bridge (review M2, M4; PoC review BLOCKER-1, MAJOR-1)
**Android WebView does not expose the Media Session API to pages at all.** Chromium's WebView delegate appends
`switches::kDisableMediaSessionAPI` (`android_webview/lib/aw_main_delegate.cc`), so on WebView 124 none of
`navigator.mediaSession`, `MediaSession` or `MediaMetadata` exists (confirmed on the emulator, `poc-results.md`).
Spotify's player feature-detects exactly these (`"mediaSession" in navigator && "MediaMetadata" in window`) and
otherwise publishes nothing. WebView also does not wire the API to the system (Chromium issue 40765779); it only
requests audio focus (`AudioFocusDelegate`). The bridge therefore has to *provide* the API, not just observe it.

`media-bridge.js`, injected with `WebViewCompat.addDocumentStartJavaScript(js, {"https://open.spotify.com"})`
(main world, runs before page JS), is organised in four parts:
1. `if (window !== window.top) return;` and an idempotency guard on `window.__osShell`.
2. **Media Session provider.** If `!('mediaSession' in navigator) && typeof MediaSession === 'undefined'`,
   install a **polyfill** of the standard API: `MediaMetadata` (accessors on the prototype, WebIDL string
   conversion, artwork URLs resolved against `document.baseURI` and frozen, session binding so later attribute
   changes are reported), `MediaSession` (no public constructor, `metadata`, `playbackState` with enum-ignoring
   setter, `setActionHandler` validated against Chrome 124's `MediaSessionAction` enum with `null` unregistering,
   `setPositionState` with Chrome's validation, `setMicrophoneActive`/`setCameraActive` no-ops), and a
   `[SameObject]` getter `Navigator.prototype.mediaSession`. Errors are `TypeError`s with Chrome's wording. If the
   API is native (a future WebView), instead **wrap** `MediaSession.prototype` using the original descriptors and
   always call the original first. Either way the same messages go to native.
   This is a user-space polyfill of a missing web platform feature. It is **not** DOM, CSS or content
   modification, it does not alter what Spotify's servers see, and it hides nothing. It is JS-detectable and we
   leave it detectable: polyfilled functions have source text instead of `[native code]`, the
   `Navigator.prototype.mediaSession` getter is a plain function, and stack frames show the script URL. A
   `Function.prototype.toString` override would be a further layer of spoofing with its own tells; it is
   deliberately not done, and the thesis states this.
3. **Media element observation.** Spotify's element is not in the document tree (detached, `new Audio()`), and
   media events neither bubble nor cross shadow roots, so listeners on `document` never see it. The primary
   mechanism is therefore hooks on `HTMLMediaElement.prototype` (`play`, `pause`, `load` and the `src`/`srcObject`
   setters) that register the element and attach direct listeners for `play`, `playing`, `pause`, `ended`,
   `durationchange` and `timeupdate` (throttled to 1 Hz). Capture-phase listeners on `document` stay as a backup,
   with de-duplication. Elements are held through `WeakRef`s. A `pagehide` listener reports "paused" so native
   never keeps a stale playing state. The hooks always call the original and are JS-visible in the same way.
4. Post JSON to native through the `OpenfyBridge` object injected by
   `WebViewCompat.addWebMessageListener(..., {"https://open.spotify.com"})`. Native drops messages with
   `!isMainFrame`. The first message of every document is `install`
   (`{session: "polyfill"|"wrapped-native"|"missing", wrapped, hooks, elements}`); native treats it as "new
   document, forget everything observed so far".

`window.__osShell.invoke(action, extra)` calls the registered handler with a `MediaSessionActionDetails`-shaped
object (`{action}`, or `{action:'seekto', seekTime, fastSeek:false}`; extra members such as `seekOffset` pass
through). Fallback for play/pause when there's no handler: the discovered media element (`!paused` for pause, last
paused for play). A `NotAllowedError` from `play()` is logged back to native.
`window.__osShell.diag()` reports `session` (`"polyfill" | "wrapped-native" | "missing"`), the installed wrapper
and hook names, the observed media element count (`elements`), the registered handler names, UA data and Widevine
capability. `console.error` reports a failing `post()` in debug builds.

Native side: `onPostMessage` runs on the UI thread. Parse the message and forward it to `MediaBridge`.
Native → page: `evaluateJavascript("window.__osShell&&window.__osShell.invoke('nexttrack')")`. If there is no
WebView or the script is missing, the call is logged and dropped, never silent (review m5).
**Native never requests audio focus.** WebView already does.

### 2.7 Background playback (review M1, M5)
- Assume the page is **hidden** in the background (`visibilityState="hidden"`). Chromium keeps
  audio-only playback running, aligns timers to 1 s, and applies intensive throttling only to pages that have
  been silent for at least 30 s. Never call `pauseTimers()`. `webView.onPause()` is only called when the app
  is backgrounded and not playing.
- Measure first with Chrome as the control (H0). A visibility-shim is a last resort, used only if the page
  self-pauses when hidden, and recorded as a deviation.
- `PlaybackService` lifecycle: it is started from the visible Activity when the bridge first reports
  metadata or a media element starts playing, and it **stays foreground while a track is loaded** (playing or
  paused). "Track loaded" means metadata present **or** a media element playing: metadata can be cleared at a
  track boundary or ad break while audio plays on. It leaves the foreground only on an explicit close action, on
  `onTaskRemoved` while paused, after 30 min paused, or when nothing has been loaded for 60 s (the log line
  carries the state that triggered it). While nothing is loaded the session and notification show idle instead of
  the old track.
- A **new main-frame document** (full navigation, reload, arm switch, renderer rebuild) clears the observed state:
  native does so on `onPageStarted` and again on the page's `install` message. `pushState` navigations do not.
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS` (runtime on 33+),
  service `foregroundServiceType="mediaPlayback"`.
- `MediaSessionCompat` (behind a narrow `NativeMediaSession` interface so we can swap it for Media3 later):
  metadata including the artwork bitmap fetched from the published `i.scdn.co` URL, `PlaybackStateCompat`
  with position, speed and actions. Metadata (and the artwork bitmap) is only re-sent to the session when it
  changes; position and state update at 1 Hz. `MediaStyle` notification with `setMediaSession(token)`.
  `MediaButtonReceiver` handles headset buttons.

### 2.8 Login (review M3)
- **Supported:** email/password (including the email-code flow) at `accounts.spotify.com` in the WebView.
- **Expected not to complete (documented limitations):** Google (the popup navigates to `accounts.google.com`,
  which is outside the in-shell allow-list, so it opens in the system browser and the OAuth redirect lands there;
  the `403 disallowed_useragent` Google would return to an embedded WebView is therefore not observed in the
  shell, review m9) and passkeys
  (WebView WebAuthn defaults to `NONE`, and `FOR_APP` would need Spotify's assetlinks to list our package).
- **Must be tested:** Facebook and Apple popups (§2.3 multiple windows).
- Magic links in email open in the system browser. Mitigation: a debug "Open Spotify link…" entry that
  loads a pasted `accounts.spotify.com` / `open.spotify.com` URL in the shell.

### 2.9 UI adaptation (deliberate deviation)
**This deliberately departs from the v0.3 non-goal "no content modification".** It is recorded as a decision, not an
accident, and the thesis should say so.

**Rationale.** Inside a dedicated shell two kinds of first-party promotion have no function: the web player's
"get the app" prompts (the user is already in an app, and the "app" they are sent to is the one that would replace
this shell) and the Premium upsell banner for a user who has chosen not to buy it. Removing them changes the
*presentation* of Spotify's own UI for the person using the shell; it does not change what Spotify's servers see
or what the user is entitled to. A switch lets the research compare adapted and unadapted behaviour.

**Scope (closed list).**
- Tier 1, app-install nags: the "Open App" header button (`button[data-testid="download-button"]`), and popovers or
  dialogs that ask the user to get/open the app. Observed: the mobile bundle's "Enjoy Your Library / Not now / Get App"
  popover shown when an anonymous user taps the "Your Library" tab.
- Tier 2, first-party Premium upsell banners: `[data-testid="premium-banner"]` inside
  `[data-testid="home-banner"]`.
- **Out of scope and never to be implemented:** audio ads, third-party or display ads, any network request
  blocking (no `shouldInterceptRequest`), any change to playback or to the media bridge, hiding the
  third-party consent dialog (OneTrust: the user's privacy choice is never touched, the dialog fallback rule explicitly
  excludes it), and the sign-up upsell dialog (`upsell-dialog`, "Start listening with a free Spotify account"): it is the
  page's functional answer to a logged-out user tapping a feature, with its own "Log in" path, not a nag.

**Mechanism.** `ui-adapt.js` is a second document-start script, injected for `https://open.spotify.com` only, with its
own `addDocumentStartJavaScript` handle so that the debug toggle can `remove()` it and reload. Rules live in
`ui-rules.json` (`id`, `tier`, `action` `hide|dismiss`, ordered `selectors`: `data-testid` first, then semantic
fallbacks; `dismissSelectors` for `dismiss`) and are templated into the script by native code before injection.
Selectors never use hashed class names: `parseRules` rejects a rule whose selector has a class that is not lowercase
words joined by dashes, or any `[class...]` attribute selector, and drops it alone; the shipped file is validated by the host test.
- `hide`: a constructable `CSSStyleSheet` in `document.adoptedStyleSheets`, one `display:none !important` rule per
  selector (one invalid selector must not void the others). It works before `documentElement` exists and does not touch
  the DOM tree.
- `dismiss`: a `MutationObserver` (childList, subtree) started once `documentElement` exists, coalesced to at most one
  pass per animation frame. A matching popup is dismissed by **clicking its own dismiss button**, at most once per
  element instance (`WeakSet`). If it has no such button nothing is hidden: hiding with CSS could leave a backdrop or a
  scroll lock behind. The failure is reported (`dismiss-failed`) once per element and the next pass tries again, since
  popovers render in two steps.
- The observed "Get App" popover is a tippy.js popover with no `role`, no `aria-*`, no `data-testid` on its buttons, no
  backdrop and no scroll lock (§2.9 evidence in `poc-results.md` H8). Its primary button is `buttonPrimary` and its
  dismiss button `buttonTertiary` (Encore `data-encore-id`s, not classes). To avoid dismissing some other popover that
  has the same two buttons, the rule also requires the primary button's label to be one of Spotify's own
  "Get App" strings (`labels`, the 76 distinct values of `action-trigger.button.get-app` and `mwp.nav.get.app` from the
  70 locale files of the mobile bundle). **This is text matching and it is unavoidable:** the primary button has no
  testid or aria-label. The dismiss button itself is matched structurally, not by text. Not retrievable: `ar`,
  `ar-EG`, `th`, `vi` (no locale file served); in those languages the popover is reported as `dismiss-failed`
  (`reason: "label-not-listed"`) and left alone.
- Telemetry through the existing `OpenfyBridge`: `{t:"adapt", rule, action:"hidden"|"dismissed"|"dismiss-failed", bundle}`
  plus `sel` (index of the selector that matched) and `reason` for failures, and one `installed` message per document.
  `bundle` is the hashed `mobile-web-player.*.js` / `web-player.*.js` file name, so Spotify deploys can be told apart.
  `hidden` means a hide selector matched an element and is sent once per rule per page load (the check runs on the
  observer, at most once per second). `__osAdapt.stats()` (also `__osShell.diag().adapt`) returns per-rule match counts,
  the bundle and the rules that never matched.
- Native: preference `ui_adaptation` (default **true**), debug-menu item "UI adaptation: on/off" (persist, remove or
  re-add the script, reload). `UiAdaptation` logs `adapt:` lines and stores in `SharedPreferences`, per bundle hash,
  how often each rule hid/dismissed/failed (`RuleSurvival`, newest 20 bundles): the "rule survival across deploys"
  record.

**Failure modes.** (1) Spotify renames a testid or restructures a component: the rule stops matching, the nag
reappears (harmless), and `neverMatched` plus the per-bundle record show it. (2) A rule is too broad and hides something useful:
selectors are testid-first, the `dismiss` path only clicks a dismiss button, the popover rule is label-gated, and the
toggle removes everything. (3) Dismissing clicks a button the page also uses for something else: bounded by
once-per-element and by the scoping to the popup root. (4) Spotify detects the modification: it is a CSS rule plus a click
the page's own button handles; nothing is spoofed, and the script is visible in DevTools like any user script.
(5) `:has()` selectors need Chromium 105+; the shell requires 120. (6) Locale gaps (above).

**Measurement.** Per run record the bundle hash, `adapt:` lines, and `__osAdapt.stats()`. Compare adapted and
unadapted runs (H8): the elements are present when the toggle is off, absent when on, a Library-tab tap leaves no
popover, the page stays scrollable and clickable (`getComputedStyle(document.body).overflow`, a navigation after
the dismissal), and the media bridge is unchanged (`diag().session === "polyfill"`).

## 3. Tech stack
Kotlin, minSdk 26, target/compileSdk 35, AGP 8.7.x, Gradle 8.14.3, JDK 17, androidx.webkit (latest),
androidx.media (MediaSessionCompat), androidx.core, androidx.activity. No other dependencies. Single module `:app`.
Permission `REQUEST_INSTALL_PACKAGES` for the self-updater.

## 4. PoC: hypotheses (replaces v0.1 §4)

| # | Hypothesis | Pass criterion | Observable |
|---|---|---|---|
| H0 (control) | Chrome on the same device plays `open.spotify.com` in mobile and/or desktop mode and keeps playing with Home + screen off | Playback continues for 30 min in at least one mode | manual, note which mode |
| H1 | The web player is usable in the shell with Arm A or Arm B | Home, search, library and playback work in at least one arm; pick the default | screenshots per arm |
| H2 | Email/password login works and persists across force-stop; FB/Apple complete or fail cleanly; Google opens in the system browser and does not complete in the shell; passkeys unavailable | Logged in after relaunch; each IdP outcome recorded | manual + logcat |
| H3 | Widevine playback works after the permission grant | Track and podcast play; no "protected content" error | logcat `EME granted`, `AudioFocusDelegate` focus line |
| H4 | The bridge (polyfill + element observation) sees metadata, state, position and handlers; native can invoke them | Notification shows title/artist/art; play/pause/next/prev/seek work from the notification and a headset button, including after a 10 min pause | logcat `bridge:` events |
| H5 | Background playback survives the realistic cliffs | 30 min screen-off with ≥5 auto track changes; 10 min paused then resume; rotation; recents-swipe while playing | manual + logcat timestamps |
| H6 | Network identity equals Chrome's except `X-Requested-With` | Diff of a header-echo page and `userAgentData.getHighEntropyValues` vs Chrome on the same device | echo output |
| H7 | WebView survives Activity destruction and renderer crashes | Playback survives rotation/recents-swipe; after a forced renderer kill the shell rebuilds and is still logged in | manual |
| H8 | UI adaptation (§2.9) removes only the intended first-party promotion and breaks nothing | ON: Open App button and premium banner not displayed, Library-tab popover auto-dismissed, page scrollable and clickable, bridge unchanged; OFF: all reappear | screenshots, `adapt:` lines, `__osAdapt.stats()` |

Record for every run: device, Android version, WebView package version, UA arm, `POST_NOTIFICATIONS` state.

**Automatable in the emulator without an account:** H1 (anonymous render), H6, H7 (renderer kill and
rotation), bridge installation (`diag()` reports `session: "polyfill"` and `'mediaSession' in navigator` is true),
the native half of H4 (a synthetic `MediaMetadata`/handlers set from the page, then `dumpsys media_session`,
`dumpsys notification` and media key events; `elements >= 1` once any media element has played), DRM capability
(`requestMediaKeySystemAccess('com.widevine.alpha')` resolves), and a short H5 background run. When a real Chrome
of the same version is on the image, it is the H6 control. **Needs a human with an account:** H0, H2, H3
playback, H4 with real Spotify metadata, H5 (30 min, track changes).
H4 may only be passable in the arm whose bundle actually calls the Media Session API; record per arm which
registrations and metadata arrive. The JS conformance test (`node --test tools/test/*.test.mjs`) covers the
polyfill on the host.

## 5. Known risks
1. The mobile web player may push users to the app or limit features. It also is a **different bundle**
   (`mobile-web-player.*.js`, "Open App" header) from the desktop one (`web-player.*.js`), so H1 must compare
   feature coverage, not only layout, and H4 may behave differently per arm. Mitigation: Arm B, decided by H1.
2. Spotify could start gating on `X-Requested-With`. It doesn't today (recon: same document as Chrome Mobile).
3. The page may self-pause when hidden. Measure in Chrome first (H0). The shim is a last resort.
4. The bridge depends only on the standard Media Session API, which it provides itself on WebView, so Spotify DOM
   churn doesn't matter. If Spotify stopped feature-detecting that API or started probing for native-only
   behaviour, the polyfill's detectability (§2.6) becomes relevant.
5. ToS: personal research use, no stream access, no ad or network blocking, and content modification limited to the
   first-party promotion removed by §2.9 (switchable). State this in the thesis.
6. `androidx.media` is in maintenance. Media3 `MediaSessionService` + `SimpleBasePlayer` is the planned
   upgrade, configured not to request audio focus.
7. The updater's trust root is the release signing key held in GitHub environment secrets (UPDATER.md §3.1).

## 6. Alternatives considered
- **Custom Tabs / TWA:** runs the site in the user's real Chrome, where Google login, passkeys, Widevine,
  background playback and the media notification all work with zero code. This meets the *user* goal but not the
  *research* goal: there is no injection point, no bridge, no native media integration of our own, and no
  lifecycle control. A TWA also needs Spotify's Digital Asset Links. Chrome is used as the **control arm** instead.
- **GeckoView:** a full browser engine with its own MediaSession delegate, but Widevine support on Android
  GeckoView is limited and the app becomes much larger. Rejected for the PoC.

## Changelog
- Self-updater (`docs/UPDATER.md`, package `dev.openfy.update`): checks the GitHub Releases of this repo, verifies
  SHA-256, package, version and signer, installs through `PackageInstaller`. Non-goal wording, §3 and §5 amended.
- v0.4: UI adaptation mode (§2.9, H8), a **deliberate deviation** from the v0.3 non-goal "no content modification",
  on by default with a debug-menu toggle. It hides the "Open App" header button and the first-party Premium banner
  (CSS in an adopted stylesheet) and dismisses the "get the app" popover by clicking its own "Not now" button; it
  never blocks audio or third-party ads, never touches the network or playback, and never touches the consent
  dialog. Rules are declarative (`ui-rules.json`, hashed class names rejected), telemetry is `t:"adapt"` with the
  Spotify bundle hash, and per-bundle rule survival is persisted. Non-goals amended accordingly.
- v0.3: PoC code review applied. WebView has no Media Session API (`kDisableMediaSessionAPI`): §2.6 now provides
  a spec-conformant `MediaMetadata`/`MediaSession`/`navigator.mediaSession` polyfill at document start when absent
  and wraps the native API when present, states that this is a JS-detectable polyfill and not content modification,
  and reports `session: "polyfill"|"wrapped-native"|"missing"` and the observed element count in `diag()`
  (BLOCKER-1). Media elements are discovered through `HTMLMediaElement.prototype` hooks because Spotify's element
  is detached; document capture is only a backup (MAJOR-1). "Track loaded" is metadata or a playing element, the
  idle grace is 60 s, and the session/notification show idle instead of a stale track (MAJOR-2, MAJOR-4). A new
  main-frame document clears the observed state (MAJOR-3). Minor: overview mode in both arms (m1), formFactors
  residual documented (m2), brand-rewrite logging (m3, m4, m14), no silent drops (m5, m7), install report (m6),
  lenient navigation host parsing (m8), Google sign-in opens externally (m9), WebView API use moved out of
  `Application.onCreate` (m10), metadata cached at 1 Hz (m11), JS dialogs cancelled without an Activity (m12),
  notification-permission wording corrected after measurement (m13). H4 and §5 risk 1 note the two bundles.
- v0.2: WebView owned outside the Activity (B1); Chrome-reduced UA + UA-CH brand rewrite and two-arm
  identity (B2, M6); `X-Requested-With` documented as irremovable, G5 reframed (B3); page assumed hidden in
  background + Chrome control (M1); no native audio focus (M2); passkeys removed, magic-link path (M3);
  prototype-level wrapping, frame guard, media-element capture, action details (M4); FGS kept while a track is
  loaded (M5); revised hypotheses H0–H7 (M7); minor fixes m1–m9.
- v0.1: initial draft.
