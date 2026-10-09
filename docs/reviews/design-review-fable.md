# Design review: open-spotify DESIGN.md v0.1

Reviewer: senior technical review (Fable). Date: 2026-10-10.
Inputs: `docs/DESIGN.md` (v0.1) and `docs/research/web-recon.md`.
Scope: technical correctness and feasibility on Android API 26–35 with current Android System WebView and androidx.webkit. Claims marked [verified] were checked against the androidx/Android reference docs, the Android Developers blog, Chromium source or the Chromium/Google issue trackers on the review date. Claims marked [reasoned] are from Chromium architecture knowledge and should be confirmed by the PoC.

Overall verdict: the core idea (observe the page's own `navigator.mediaSession` through a document-start script, mirror it into a native MediaSession, keep the renderer alive with a foreground service) is sound and is the right architecture for the stated goals. Three things in the design are wrong as written and must be corrected before the PoC: (1) the WebView is owned by the Activity, which makes background playback impossible in practice; (2) the "browser identity" section rests on two claims that are no longer true (WebView UA is not reduced on API ≤ 35, and `X-Requested-With` cannot be disabled anymore); (3) the design does not know that the page *will* be hidden in the background no matter what the Activity does, so the mitigation strategy is aimed at the wrong lever.

---

## BLOCKER findings

### B1. WebView lifetime is bound to `MainActivity`; G4/H5 cannot pass as designed

§2 puts `BrowserWebView` inside `MainActivity`. An Activity is destroyed on rotation, on "swipe from recents", on low memory, and whenever the system decides. Destroying the Activity destroys the WebView and the page, and with it the audio pipeline. A `mediaPlayback` foreground service keeps the *process* alive, but it does not keep the Activity alive. The design's "must not call `webView.onPause()`" rule is therefore necessary but nowhere near sufficient.

Correction:
- Own the WebView outside the Activity. Create it once in an app-scoped holder (an `Application`-level singleton or, better, the `PlaybackService` itself) using `MutableContextWrapper(applicationContext)`. When an Activity starts, set the wrapper's base context to the Activity and `addView()` the WebView; on `onDestroy()` remove the view, swap the base back to the application context, and keep the instance alive. WebView needs an Activity context for dialogs, file choosers and `onCreateWindow`, which is why the wrapper is swapped instead of using the application context permanently.
- Add `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|keyboardHidden"` and `android:launchMode="singleTask"` to the Activity so it is not recreated for the common cases.
- Implement `Service.onTaskRemoved()` (swipe from recents): keep playing if `playbackState == playing`, otherwise stop the service and destroy the WebView.
- Keep the default renderer policy (`RENDERER_PRIORITY_IMPORTANT`, not waived when invisible — this is already the documented default [verified]) and implement `WebViewClient.onRenderProcessGone()` (API 26) to rebuild the WebView instead of crashing.
- Add a PoC hypothesis for this (see §Revised hypotheses, H7).

### B2. §2.1 "Browser identity" is wrong on API 26–35: UA string and UA client hints

Two separate problems:

(a) **UA string.** The design says dropping `; wv` and `Version/4.0` from the default WebView UA gives a string byte-identical to Chrome Mobile. That is only true once WebView ships the reduced UA, and Google's announcement states the reduced default WebView UA begins with Android 17 (the blog was originally announced for 16, then updated) [verified: android-developers.googleblog.com/2024/12/user-agent-reduction-on-android-webview.html; Chromium `aw_content_browser_client.cc` builds the full `(Linux; Android <ver>; <model> Build/<id>; wv)` string unless the reduction switch is set]. On every target of this project (API 26–35) the default WebView UA is e.g.
`Mozilla/5.0 (Linux; Android 14; Pixel 7 Build/UP1A.231005.007; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.200 Mobile Safari/537.36`
whereas Chrome Mobile sends the reduced
`Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36`.
Stripping two tokens leaves the real Android version, device model, build ID and full Chrome patch version in the string. That is not Chrome's UA and is a trivially detectable WebView signature.

Correction: build the Chrome-reduced UA explicitly. Parse the Chrome major version out of `WebSettings.getDefaultUserAgent(context)` and set
`Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<major>.0.0.0 Mobile Safari/537.36`.
Do not spoof the major version; it must match the real Chromium so feature detection stays consistent.

(b) **User-Agent Client Hints.** WebView has sent UA-CH since WebView 116 [verified]. The default brand list is `"Android WebView";v="131", "Chromium";v="131", "<grease>"` in both the `Sec-CH-UA` header and `navigator.userAgentData.brands` — Chrome sends `"Google Chrome"` instead of `"Android WebView"`. This is independent of the UA string, so the design as written leaks "Android WebView" on every request. Furthermore, once `setUserAgentString()` is used with a string that does not *contain* the default UA, WebView only generates the low-entropy hints (`Sec-CH-UA`, `-Mobile`, `-Platform`) unless metadata is supplied [verified: `WebSettingsCompat.setUserAgentMetadata` javadoc].

Correction: add to §2.1, guarded by `WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)`:
1. `val md = WebSettingsCompat.getUserAgentMetadata(settings)`
2. copy `md.brandVersionList` replacing the brand string `"Android WebView"` with `"Google Chrome"` (keep the grease entry and the versions exactly as Chromium generated them; the grease brand is derived from the major version and must not be hand-written);
3. keep `platform = "Android"`, `mobile = true`, `platformVersion`, `model`, `architecture`, `bitness`, `fullVersionList` (same substitution), `formFactors`;
4. `WebSettingsCompat.setUserAgentMetadata(settings, rebuilt)`.
If the feature is unsupported (old WebView), treat the device as unsupported (see MINOR m6).

### B3. `X-Requested-With` cannot be removed; G5/H6 are unachievable as stated

`WebSettingsCompat.setRequestedWithHeaderOriginAllowList()` is deprecated in androidx.webkit 1.15.0 and documented as "no longer does anything": the Chromium origin trial to remove the header ended, the removal was **cancelled**, and the header "will again be sent on all requests by default" [verified: androidx.webkit release notes 1.15.0; chromestatus feature 5160086884843520 "suspended"]. Current WebView sends `X-Requested-With: <your.package.name>` on every navigation, XHR/fetch, WebSocket handshake and service-worker request. There is no public API to suppress it. `shouldInterceptRequest` cannot strip request headers; re-issuing requests yourself breaks cookies, streaming, MSE and EME, and is out of scope.

Correction (reframe, do not work around):
- Rewrite G5 to what is achievable: "standard web platform behaviour and Chrome-equivalent capability signals (UA, UA-CH, cookies, EME, MediaSession); no private APIs". State explicitly that the shell is identifiable as a WebView host through `X-Requested-With`, and that the project does not attempt to hide that (an honest statement that also protects the thesis ethically: Google's `disallowed_useragent` policy for OAuth in embedded webviews is enforced partly through this header and the `wv` token; defeating it would be a policy circumvention, which the design already lists as a non-goal in spirit).
- Rewrite H6 (see §Revised hypotheses). The measurement should be a diff of headers and `navigator.userAgentData` between the shell and Chrome on the same device, with the expected residual difference being exactly `X-Requested-With`.
- Mention in §5 Known risks that Spotify *could* block on this header; the recon showed the WebView UA receives the same document as Chrome Mobile, so today it does not.

---

## MAJOR findings

### M1. The page will be `hidden` in the background whatever the Activity does; design the bridge for that instead of trying to avoid it

§2.6 and §5.3 assume that if the Activity avoids `onPause()`, the document stays visible. It does not. WebView computes content visibility as (view visible) AND (window visible) AND (not paused) [reasoned, Chromium `AwContents.updateWebContentsVisibility` / `AwContents::IsVisible`]. When the Activity stops, the window visibility becomes `GONE`, WebContents is hidden, `document.visibilityState` becomes `"hidden"` and `visibilitychange` fires. A WebView detached from any window (B1) is hidden as well. So in every realistic background configuration the page is hidden.

What Chromium does to a hidden page [verified for Chrome; applies to WebView since it is Blink/content behaviour]:
- Audio-only media keeps playing. Video elements with audio keep audio; background *video* may be paused for video-only content.
- Timers are aligned to 1 s. "Intensive" throttling (one wake-up per minute) starts after 5 min hidden, but only for pages that have been *silent for at least 30 s* [verified: developer.chrome.com/blog/timer-throttling-in-chrome-88]. A playing track exempts the page; a long paused interval does not.
- Web developers have reported WebView JS stopping after ~5 minutes in background; that is this throttling plus page freezing on silent pages, not a WebView bug.

Correction:
- Replace "keep the document visible" with "assume hidden". Calling `webView.onPause()` while *not* playing is fine and saves battery; never call `pauseTimers()` (global) [verified: WebView javadoc].
- Run the control experiment first: play in Chrome for Android on the same device, press Home and turn the screen off. If Chrome keeps playing, the Spotify web player does not self-pause on `visibilitychange`, and the only remaining risks are throttling and process priority. If Chrome also stops, it is site behaviour and the shell can only match it.
- Define H5 to cover the throttling cliffs: ≥ 30 min screen-off, ≥ 5 automatic track transitions, and a 10 min paused interval followed by resume from the notification (the paused interval tests intensive throttling on a silent page).
- If and only if self-pausing on hidden is observed, the PoC may try a document-start shim that overrides the `document.visibilityState`/`hidden` getters and swallows `visibilitychange` for the main frame. Record it as a deviation from browser behaviour, not as UI/content modification. Do not use overlay windows (`SYSTEM_ALERT_WINDOW`) to keep the view "visible".

### M2. WebView itself requests Android audio focus for web media; native code must not

Chromium's content layer (`org.chromium.content.browser.AudioFocusDelegate`, used by `MediaSessionImpl::AddPlayer`) calls `AudioManager.requestAudioFocus()` for any web media playback. This is in `content/`, so it applies to WebView as well as Chrome [verified: issuetracker.google.com/issues/40946745 logcat shows the request from `AudioFocusDelegate` and reports the effect in WebView apps]. Consequences for the design:
- Do not request audio focus from `PlaybackService`. `MediaSessionCompat` alone does not; a Media3 `ExoPlayer` would, and it would take focus away from the WebView and pause the music. If Media3 is used (m7), configure the player without audio-focus handling.
- Transient loss (phone call, navigation prompt), ducking and resume are already handled by Chromium; the native side only mirrors state.
- The design's confirmation that WebView has no media notification/lock-screen integration is correct: Chromium issue 40765779 ("MediaSessionAPI support on Android WebView") is still an open P2 feature request; the only part WebView wires up is audio focus [verified]. So the bridge is required, as the design says.
- Useful observable for H3/H5: logcat line `MediaFocusControl ... requestAudioFocus() ... clientId=...org.chromium.content.browser.AudioFocusDelegate ... callingPack=<your package>`.

### M3. §2.7 login: passkeys will not work in the WebView; magic-link flow needs a path back into the shell

- WebAuthn in WebView defaults to `WEB_AUTHENTICATION_SUPPORT_NONE`. `WEB_AUTHENTICATION_SUPPORT_FOR_APP` requires a Digital Asset Links association between the relying party domain (`spotify.com`) and *your* package; `FOR_BROWSER` is limited to privileged apps [verified: `WebSettingsCompat` javadoc; developer.android.com/identity/sign-in/credential-manager-webview; passkeys.dev Android reference]. Spotify will not publish your package in its assetlinks.json, so `navigator.credentials.get({publicKey})` will reject in the shell. Correction: remove "passkeys" from §2.7; list it with Google sign-in as a known limitation; email/password is the only path the design can promise. Facebook and Apple popups are plausible but must be tested (H2 sub-cases).
- The design relies on "email magic link" for Google-created accounts. The link in the email opens in the system browser, not in the shell. Correction: either add an `https` intent filter for `accounts.spotify.com` (it will not auto-verify; on API 31+ the user must enable "Open supported links" manually) or add a simple "paste a Spotify link" entry point. Note this in the PoC runbook.
- Google OAuth: even with the UA/UA-CH rewritten, Google can still see `X-Requested-With`. State in the design that the shell does not try to pass Google's embedded-webview check and that `403 disallowed_useragent` is the expected, documented outcome.

### M4. Media bridge details (answers §6, question 1) — the approach is sound; fix these specifics

The timing worry in §6 is unfounded: `addDocumentStartJavaScript` runs "before any of the page's JavaScript code" in every frame whose origin matches, and "the injected object from `addWebMessageListener` will be injected first" [verified: `WebViewCompat` javadoc]. Both run in the page's main world (the newer `addJavaScriptOnEvent`/`JavaScriptExecutionWorld` API exists for isolated worlds; do not use it here, the wrapper must live in the main world to be observable by page code). Specific corrections:

1. Wrap on `MediaSession.prototype`, not on the instance: `Object.getOwnPropertyDescriptor(MediaSession.prototype, 'metadata')`, then `Object.defineProperty` with a setter that calls the original. `navigator.mediaSession` is `[SameObject]` and its accessors live on the prototype, so a page that caches the instance still goes through the wrapper. Same for `playbackState`, `setActionHandler`, `setPositionState`. Record `setActionHandler(action, null)` as unregistration.
2. Guard against frames: the script is injected into every same-origin frame. Either `if (window !== window.top) return;` or tag messages with `isMainFrame` (the listener already receives it) and ignore sub-frames natively.
3. `document.body` and `document.documentElement` are null at document start. Only touch `navigator`, `window` and `MediaSession.prototype` synchronously; defer DOM work to `DOMContentLoaded`.
4. `playbackState` as declared by the page is not the truth; browsers combine it with the actual media element state. Add capture-phase listeners on `document` for `play`, `pause`, `playing`, `ended`, `durationchange` and a throttled `timeupdate` (these events do not bubble, but capture at the document works) and report both the declared state and the element state. This also gives a reliable position source when the page does not call `setPositionState` often.
5. When invoking a handler from native, pass a `MediaSessionActionDetails`-shaped object: `handler({action: 'nexttrack'})`, `handler({action: 'seekto', seekTime, fastSeek: false})`. Spotify registers `nexttrack`/`previoustrack`/seek (recon §3) and may not register `play`/`pause`; for those, the browser's default behaviour is to act on the media element, so the `<audio>/<video>` fallback in the design is correct. Choose the element by `!paused` for pause, and the most recently paused one for play. A tertiary fallback of clicking the page's own play button is acceptable since it is literally what a user does; keep it out of the core path.
6. User activation: `evaluateJavascript` carries no user activation. After the user has interacted with the document once, Chromium's sticky activation lets later `play()` calls succeed, so notification "play" works in the normal case. After a renderer restart or a cold restart with no interaction, `play()` may reject with `NotAllowedError`. Log it. Only if it bites, set `mediaPlaybackRequiresUserGesture = false` (a behavioural, not a header-level, difference from Chrome). Keep `true` as the default, as the design says.
7. Make `WebViewFeature.DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER` and `USER_AGENT_METADATA` hard requirements; there is no good fallback (`onPageStarted` + `evaluateJavascript` is too late for a wrapper).
8. `WebMessageListener.onPostMessage` delivers on the UI thread; keep it light and hand off to the service.

### M5. Foreground service lifecycle in §2.6 will throw on API 31+ in a common path

"Starts foreground when playing, drops foreground when paused" means `startForeground()` is called again on resume. If the resume is triggered while the app is in the background and not by a notification tap (Bluetooth/wired headset button → `MediaSessionCompat.Callback.onPlay`), it is not in the documented exemption list and raises `ForegroundServiceStartNotAllowedException` on API 31+ [verified: restrictions-bg-start doc lists the exemptions; media buttons are not among them].

Correction:
- Stay in the foreground for as long as the page has a media session with metadata (i.e., a track is loaded), regardless of play/pause. Leave the foreground only on an explicit stop (notification "close", `onTaskRemoved` while paused, or an idle timeout, e.g. 30 min paused).
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (API 34), `foregroundServiceType="mediaPlayback"`, and `POST_NOTIFICATIONS` requested at runtime on API 33+; without it the media notification is not shown even though the service runs.
- `NotificationCompat.MediaStyle` must be attached to the session token (`setMediaSession`) for it to appear in the system media player area on API 33+.
- Start the service from the Activity (visible) the first time the bridge reports metadata, not the first time it reports "playing"; this guarantees the first start is from a visible state.

### M6. Desktop UA: do not go straight to desktop; decide it with a control experiment on day 1

Community reports say the mobile web player is crippled and that "Request desktop site" fixes it; the recon could not verify this. The right move is a two-arm H1 with Chrome as control, not a design-time guess:
- Arm A (mobile): shell with the corrected Chrome Mobile identity (B2).
- Arm B (desktop): mimic exactly what Chrome's "Request desktop site" sends, not an arbitrary desktop UA: UA `Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<major>.0.0.0 Safari/537.36`, UA-CH `platform="Linux"`, `mobile=false`, `model=""`, `platformVersion=""`, plus `useWideViewPort=true`, `loadWithOverviewMode=true` and a sensible initial scale. A desktop UA with Android client hints and a 400 px viewport is an inconsistency that is easier to flag than either consistent profile.
- Control: Chrome on the same device, mobile and desktop mode, same account.
If Arm A is unusable for playback, Arm B becomes the default and the design should say so. Keep the toggle either way.

### M7. H5 and H3 observables are too weak; add the control group and renderer/throttling cases

The hypothesis table is right in shape but misses what will actually break. See §Revised hypotheses below for concrete pass criteria. Most important additions: a Chrome control arm (separates site behaviour from WebView behaviour), the Activity-destroyed case (B1), the 5-minute throttling cliff (M1), renderer crash recovery, and the bridge-without-user-activation case (M4.6).

---

## MINOR findings

### m1. §2.4 DRM is correct; add provisioning and diagnostics notes
Granting `RESOURCE_PROTECTED_MEDIA_ID` in `onPermissionRequest` is sufficient; the default `WebChromeClient` denies. Two notes: (a) Chromium grants per WebView, not per origin [verified: issues.chromium.org/424995167 comment], so the origin check only gates the first request — still worth doing; (b) WebView uses per-origin Widevine provisioning with a random origin ID stored in app prefs (`MediaDrmStorageImpl` in `aw_content_browser_client.cc`), so the first playback for `open.spotify.com` needs a provisioning round trip to Google's server, and clearing WebView data forces re-provisioning. L3 is enough: desktop Chrome plays Spotify with the software (L3-equivalent) CDM. Add native diagnostics for the thesis only: `MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)` and `MediaDrm.getPropertyString("securityLevel")`. Failure observable: Spotify's "Playback of protected content is not enabled" message.

### m2. Cookie claim in recon §6/risk 7 is wrong (harmless)
`open.spotify.com` and `accounts.spotify.com` are same-site (eTLD+1 `spotify.com`); `SameSite=None` cookies between them are first-party and need no third-party cookie setting. `setAcceptThirdPartyCookies(true)` only matters for cross-site iframes (Facebook). Keep it (Chrome Android still allows third-party cookies by default) but drop the justification.

### m3. §2.3 navigation policy
`*.scdn.co` / `*.spotifycdn.com` are asset hosts and should never be top-level navigations; keep them out of the allow-list and let `shouldOverrideUrlLoading` only govern main-frame navigations (sub-resources are not routed through it). Add `www.spotify.com` (support/legal pages, account settings) to the allow-list or they will open externally and lose the session. Use `OnBackPressedCallback` rather than overriding `onBackPressed()` (deprecated on API 33+).

### m4. Multiple windows
`onCreateWindow` requires `setSupportMultipleWindows(true)` and `javaScriptCanOpenWindowsAutomatically` as appropriate, a second WebView delivered via `WebView.WebViewTransport`, and `onCloseWindow`. `window.opener.postMessage` from the popup back to the player works across WebViews in the same process. State this explicitly; it is the only way the Facebook popup can complete.

### m5. `MediaSessionCompat` vs Media3
`androidx.media` is in maintenance. Media3's `MediaSessionService` + a `SimpleBasePlayer` subclass backed by the bridge gives you the notification, FGS handling, media buttons, position extrapolation, Android Auto/Wear/Assistant integration, and avoids M5 entirely. Acceptable to keep `MediaSessionCompat` for the PoC; keep the service interface narrow so the swap is cheap. If Media3 is used, do not let it request audio focus (M2).

### m6. WebView version gate
API 26 devices can run very old WebViews. Required features: `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`, `USER_AGENT_METADATA` (WebView ≥ 116 for UA-CH). Read `WebViewCompat.getCurrentWebViewPackage()` and refuse to run with a clear message below a stated minimum (suggest Chromium 120). Record the WebView version in every PoC result.

### m7. Other detectable differences (answers §6, question 3)
Beyond UA/UA-CH/`X-Requested-With`: `Notification.permission === "denied"` and no Push in WebView; no `navigator.getInstalledRelatedApps`; no Picture-in-Picture; WebAuthn absent (M3); `window.open` behaviour; no `beforeinstallprompt`; `Accept-Language` gets `,en-US` appended when absent [verified in `aw_content_browser_client.cc`]. None of these are headers Spotify's server sees on the main document; they are JS feature probes. The practical measurement (H6) is: load a page that echoes request headers (your own endpoint or httpbin) and dumps `navigator.userAgentData.getHighEntropyValues([...])`, `navigator.platform`, `maxTouchPoints`, screen metrics; run it in the shell and in Chrome on the same device; diff.

### m8. Trusted Web Activity / Custom Tabs (answers §6 and "simpler alternatives")
A Custom Tab (or TWA, which needs Digital Asset Links from Spotify and would otherwise show the toolbar) runs the site in the user's real Chrome: Google sign-in, passkeys (AuthTab), Widevine, background playback and Chrome's own media notification all work with zero code. It fails the research goal, not the user goal: no script injection, no bridge, no native media integration written by you, no control over lifecycle. Add an "Alternatives considered" section that says exactly this, and use Chrome on the device as the control arm of every hypothesis.

### m9. Wording
"`Version/x.y`" is always `Version/4.0` in WebView. `request.origin.host` on `PermissionRequest.getOrigin()` (a `Uri`) is correct. `CookieManager.flush()` on pause is fine. The dark background note is fine.

---

## Revised PoC hypotheses (replace §4)

| # | Hypothesis | Pass criterion | Observable |
|---|---|---|---|
| H0 (control) | Chrome on the same device plays `open.spotify.com` in mobile mode and in "desktop site" mode, and keeps playing with Home + screen off | Playback continues 30 min in at least one mode | manual, note which mode |
| H1 | The web player is usable in the shell with the corrected Chrome Mobile identity (Arm A) or the Chrome-RDS desktop profile (Arm B) | Home, search, library and playback work in at least one arm; pick the default | screenshots per arm |
| H2 | Email/password login works and persists across force-stop; Facebook/Apple popups complete or fail cleanly; Google fails with `disallowed_useragent`; passkeys are unavailable | Logged in after relaunch; each IdP outcome recorded | manual + logcat |
| H3 | Widevine playback works after the permission grant | Track and podcast play; no "protected content" error | logcat `EME granted`, `AudioFocusDelegate` focus line, audio |
| H4 | The bridge sees metadata, state, position and handlers, and native can invoke them | Notification shows title/artist/art; next/prev/play/pause/seek from notification and from a headset button work, including after a 10 min pause | logcat `bridge:` events |
| H5 | Background playback survives the realistic cliffs | 30 min screen-off with ≥ 5 auto track changes; 10 min paused then resume from notification; rotation while playing; swipe from recents while playing | manual + logcat timestamps |
| H6 | The shell's network identity equals Chrome's except `X-Requested-With` | Header echo and `userAgentData` diff versus Chrome on the same device shows only `X-Requested-With` as the extra | echo page output |
| H7 | WebView survives Activity destruction and renderer crashes | Playback continues through rotation and recents-swipe; after a forced renderer kill (`chrome://inspect` or `kill`) the shell rebuilds and the user is still logged in | manual |

Record for every run: device, Android version, WebView package version, UA arm, and whether `POST_NOTIFICATIONS` was granted.

## Answers to §6 open questions

1. Wrapping at document start is robust (ordering is guaranteed by the API contract); wrap the prototype, handle sub-frames and null handlers, and add media-element event capture as a second source of truth (M4).
2. The page will be hidden in the background; assume it, measure Spotify's behaviour in Chrome first, and keep the visibility shim as a last resort (M1). The real levers are: WebView owned outside the Activity (B1), foreground service kept up while a track is loaded (M5), default renderer priority plus `onRenderProcessGone` (B1), and no native audio-focus requests (M2).
3. Yes: the full WebView UA on API ≤ 35, the `"Android WebView"` UA-CH brand, and `X-Requested-With` (B2, B3); plus JS-level feature gaps (m7). The first two are fixable; the third is not and should be documented rather than hidden.
