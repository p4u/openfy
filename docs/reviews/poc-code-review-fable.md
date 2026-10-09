# PoC code review: open-spotify implementation vs DESIGN.md v0.2

Reviewer: Fable (same reviewer as `design-review-fable.md`). Date: 2026-10-10.
Scope: `app/src/main/java/dev/openspotify/shell/*.kt`, `app/src/main/assets/media-bridge.js`,
`AndroidManifest.xml`, Gradle files, `README.md`. The code builds, 23 unit tests pass, lint is clean, and it
has not yet run on a device. This review is static: it looks for things that will break hypotheses H1–H7
when it does run.

What was checked against artifacts rather than from memory:
- `androidx.webkit:webkit:1.15.0` (`~/.gradle/caches/.../webkit-1.15.0.aar`, `javap -p -v`):
  `UserAgentMetadata.Builder(UserAgentMetadata)` and `BrandVersion.Builder(BrandVersion)` copy constructors
  exist; there is one `brandVersionList` (brand, majorVersion, fullVersion) that feeds both `Sec-CH-UA` and
  `Sec-CH-UA-Full-Version-List`; `setFormFactors`/`getFormFactors` carry
  `@RestrictTo(LIBRARY_GROUP)`. The implementer's "formFactors not settable" note is correct.
- `androidx.media:media:1.7.1` `MediaButtonReceiver`: notification actions are `getBroadcast` PendingIntents
  to the receiver; `onReceive` resolves the one service with a `MEDIA_BUTTON` intent filter and calls
  `ContextCompat.startForegroundService()` on every press, catching `ForegroundServiceStartNotAllowedException`.
- `WebSettingsCompat.setUserAgentMetadata` javadoc: with a UA override that does not contain the default UA,
  only low-entropy hints are generated unless metadata is set. The code sets it, so high-entropy hints exist.

Empirical input received during the review (emulator, Android 15 x86_64 Play image, WebView 124.0.6367.219,
Arm A, anonymous, inspected over CDP on the live page):
- `"mediaSession" in navigator === false`, `typeof MediaSession === "undefined"`,
  `typeof MediaMetadata === "undefined"`. The bridge logged `NoMediaSession: MediaSession.prototype missing`
  (the error path works; nothing was hidden).
- `document.querySelectorAll("audio,video").length === 0`: Spotify's media element is not in the document tree.
- Served bundle is `mobile-web-player.*.js`; the page shows an "Open App" header button.
- Widevine `requestMediaKeySystemAccess` resolves (L3), EME permission granted, UA-CH rewrite works (brands
  contain `"Google Chrome"`, `platform="Android"`, `mobile=true`).

Verdict: the implementation follows v0.2 closely and the risky parts (UA-CH rewrite, WebView ownership,
renderer rebuild, FGS type and start rules, EME grant) are done correctly and are now partly confirmed on the
emulator (H6 brand rewrite, DRM capability). But the central premise of §2.6 is false on WebView: there is no
`navigator.mediaSession` to observe, so Spotify's own guard (`"mediaSession" in navigator && "MediaMetadata"
in window`) skips the API entirely and H4 cannot even start. That is BLOCKER-1 and it is a design defect
(mine, M4), not an implementation defect. The fix is to *provide* a spec-conformant Media Session API at
document start, which the bridge then owns outright. Three further MAJOR issues would produce wrong results in
H4/H5 and should be fixed before the first device run; all are small.

---

## BLOCKER

### BLOCKER-1. WebView has no Media Session API; the bridge must provide `MediaMetadata`, `MediaSession` and `navigator.mediaSession` at document start
**File:** `app/src/main/assets/media-bridge.js:38` (`var MS = window.MediaSession && window.MediaSession.prototype`),
`:83-116` (wrap-or-report), `:114-116` (`NoMediaSession` error path); `docs/DESIGN.md` §2.6 premise.

**Scenario (confirmed).** Android WebView ships Chromium with the Blink Media Session API disabled
[verified: `android_webview/lib/aw_main_delegate.cc` (e.g. tag 133.0.6940.0) contains
`cl->AppendSwitch(switches::kDisableMediaSessionAPI);` with the comment "WebView does not support MediaSession
API since there's no UI for media metadata and controls"; this is the other half of Chromium issue 40765779,
which the design cites only for the missing system integration]. On
WebView 124 none of `navigator.mediaSession`, `MediaSession`, `MediaMetadata` exist. Spotify's web player
feature-detects exactly these and falls back to doing nothing, so no metadata, playback state, position or
action handlers are ever published. The current script wraps nothing, reports `NoMediaSession`, and H4 fails
before the first track. The two-source design (declared state + element events) collapses to zero sources,
because the element is also detached (MAJOR-1).

**Fix.** Keep the existing wrap path for the day WebView ships the API natively, and add a polyfill path used
only when the API is absent. Both paths post the same messages. The polyfill must be spec-conformant enough
that Spotify's code (written against Chrome) behaves identically; the reference is the Media Session spec
(w3c.github.io/mediasession) and Chrome 124's IDL. Install order inside the document-start script:
`if (!('mediaSession' in navigator) && typeof MediaSession === 'undefined')` → install polyfill; else → wrap.

Specification of the polyfill (all in the main world, before any page script):

1. **`MediaMetadata` interface object.** A function `MediaMetadata(init = {})` that throws
   `TypeError("Failed to construct 'MediaMetadata': Please use the 'new' operator, this DOM object constructor
   cannot be called as a function.")` when `new.target` is undefined. `init` is a dictionary: `title`,
   `artist`, `album` are `DOMString` with default `""` (WebIDL conversion: `String(v)`, so `null` → `"null"`,
   missing → default); `artwork` is `sequence<MediaImage>` with default `[]`. Attributes `title`, `artist`,
   `album`, `artwork` are **accessors on `MediaMetadata.prototype`** (`enumerable: true, configurable: true`,
   getter and setter), not own data properties. `MediaMetadata.prototype[Symbol.toStringTag] === "MediaMetadata"`
   (non-writable, non-enumerable, configurable). `MediaMetadata.length === 0`. Internal slots live in a
   `WeakMap` keyed by instance; accessors brand-check `this` and throw `TypeError("Illegal invocation")`.
   **Artwork conversion** (spec "convert artwork algorithm"): for each entry, `src` is required
   (`TypeError("Failed to construct 'MediaMetadata': required member src is undefined.")`), resolved against
   `document.baseURI` with `new URL(src, document.baseURI).href` and `TypeError("... 'src' is not a valid URL.")`
   on failure; `sizes` and `type` are `DOMString` default `""`; each resulting `{src, sizes, type}` is
   `Object.freeze`d and the array itself is frozen (`FrozenArray<MediaImage>`). The getter returns the same
   frozen array until the next set. **Session binding:** when a `MediaMetadata` is assigned to a session, record
   the session in the metadata's slot; any later attribute set on a bound metadata re-posts `metadata` to native
   (Blink does `MediaMetadata::NotifySession`). This also removes the README limitation "property changes after
   assignment are not observed".
2. **`MediaSession` interface object.** `window.MediaSession` is a function whose call and construct both throw
   `TypeError("Illegal constructor")`. `MediaSession.prototype[Symbol.toStringTag] === "MediaSession"`.
   Attributes (accessors on the prototype, enumerable, configurable, brand-checked):
   - `metadata`: nullable. Setter: `null` or `undefined` → `null`; any value that is not a polyfill
     `MediaMetadata` instance → `TypeError("Failed to set the 'metadata' property on 'MediaSession': Failed to
     convert value to 'MediaMetadata'.")`. On set: unbind the old metadata, bind the new one, post `metadata`
     (title, artist, album, artwork; or the "cleared" shape for `null`).
   - `playbackState`: enum `"none" | "paused" | "playing"`, default `"none"`. WebIDL enum **attribute** setters
     ignore invalid values silently (no throw); valid values are stored and posted as `state`.
   Methods (data properties on the prototype, `writable/enumerable/configurable: true`):
   - `setActionHandler(action, handler)` (`length === 2`). `action` is converted with `String(action)` and
     validated against Chrome 124's `MediaSessionAction` enum: `play, pause, seekbackward, seekforward,
     previoustrack, nexttrack, skipad, stop, seekto, togglemicrophone, togglecamera, hangup, previousslide,
     nextslide, enterpictureinpicture`. Invalid → `TypeError("Failed to execute 'setActionHandler' on
     'MediaSession': The provided value 'X' is not a valid enum value of type MediaSessionAction.")`. `handler`
     is a nullable callback: `null` (or `undefined`) removes the handler; a non-callable non-null value →
     `TypeError("... parameter 2 is not of type 'Function'.")`. Store in the existing `handlers` map and post
     `handlers`.
   - `setPositionState(state = {})` (`length === 0`). If `state` is `undefined` or has none of the three members
     → clear position state (post `position` with nulls). Otherwise: `duration` is required
     (`TypeError("... required member duration is undefined.")`); `duration` is `unrestricted double` (NaN and
     +Infinity accepted by conversion, then NaN rejected: `TypeError("... The provided duration cannot be NaN.")`),
     must not be negative (`"The provided duration cannot be less than zero."`); `position` (default `0`) and
     `playbackRate` (default `1`) are `double` (non-finite → `TypeError`), `position < 0` → `"The provided
     position cannot be less than zero."`, `position > duration` → `"The provided position cannot be greater
     than the duration."`, `playbackRate === 0` → `"The provided playbackRate cannot be equal to zero."`. Store a
     frozen copy, record `performance.now()`, post `position`.
   - `setMicrophoneActive(active)` and `setCameraActive(active)` (`length === 1`): present in Chrome 124
     (shipped M104); no-ops that coerce to boolean. Spotify does not call them, but a missing method would be a
     detectable difference from Chrome.
3. **`Navigator.prototype.mediaSession`.** An accessor on `Navigator.prototype` (getter only, `enumerable:
   true, configurable: true`). `[SameObject]`: lazily create one `MediaSession` per `Navigator` and keep it in a
   `WeakMap`; brand-check `this instanceof Navigator`, else `TypeError("Illegal invocation")`. After this,
   `"mediaSession" in navigator === true`, `navigator.hasOwnProperty("mediaSession") === false`,
   `navigator.mediaSession === navigator.mediaSession`, exactly as in Chrome.
4. **Default actions.** In Chrome, actions without a handler fall through to UA defaults (play/pause/stop/seek on
   the active element). The polyfill does not need to emulate that in-page; native already does it through
   `invoke()`'s fallback, which needs MAJOR-1's element discovery to work.
5. **`invoke()`** stays as implemented (details object `{action}` / `{action:'seekto', seekTime, fastSeek:false}`;
   add `seekOffset` for `seekbackward`/`seekforward` if native ever sends them).
6. **Diagnostics.** Replace `sessionWrapped` with `session: "polyfill" | "wrapped-native" | "missing"` in `diag()`
   and post an `install` message with the same value plus `elements: <count of discovered media elements>`;
   change the README's pass signal accordingly. Cite `aw_main_delegate.cc` at the tag matching the WebView
   version used in each run.

**Detectability (record in the thesis, do not mask).** The polyfill is JS-visible: `Function.prototype.toString`
on any polyfilled function returns source instead of `function setActionHandler() { [native code] }`;
`Object.getOwnPropertyDescriptor(Navigator.prototype, 'mediaSession').get` is a plain function; `MediaSession`
has an own `prototype` that is writable (native interface objects have a non-writable `prototype`) unless
defined with `Object.defineProperty(..., {writable:false})`, which the polyfill should do. Chrome's
`Error.prototype.stack` frames and `new Error().stack` inside handlers show the script URL. None of this is
visible to Spotify's servers (no header changes), it does not alter any content, and Spotify's detection is the
two `in` checks. A `Function.prototype.toString` override that returns native-looking strings would be another
layer of spoofing with its own tells; the recommendation for a research artefact is to leave the polyfill
honestly detectable and state so (same category as a user-space polyfill of a missing web platform feature).

**Consequence for H1/H4.** The mobile bundle (`mobile-web-player.*.js`) may not use the Media Session API at
all even when present; the recon found the calls in the desktop `web-player.*.js`. After installing the
polyfill, the first `handlers`/`metadata` message (or its absence) in Arm A versus Arm B decides which arm can
pass H4. Record it.

---

## MAJOR

### MAJOR-1. Media-element events are only observed through `document` capture; Spotify's element is detached (confirmed), so nothing is ever seen
**File:** `app/src/main/assets/media-bridge.js:145-151` (capture listeners and `mediaElements()`), `:154-169` (fallback).
Required companion of BLOCKER-1: with the polyfill in place this is the only way the play/pause fallback and
element position can work.

**Scenario (confirmed on the emulator: `querySelectorAll("audio,video").length === 0` while the player is
loaded).** The second source of truth assumes the page's `<audio>`/`<video>` is in the document tree. An
event's propagation path is built from the target's ancestors; an element created with `new Audio()` /
`document.createElement('audio')` that is never appended, or one inside a shadow root (`play`/`pause`/
`timeupdate` are not `composed`), dispatches only on itself. `document.addEventListener(type, fn, true)` never
fires, `querySelectorAll('audio,video')` returns nothing. Consequences: `elementPlaying` stays `null` for the
whole session, element position never arrives (position then depends entirely on `setPositionState`), and the
play/pause **fallback** can never find an element. If Spotify registers no `play`/`pause` action handlers (recon
only found `nexttrack`/`previoustrack`/seek), the notification Play/Pause buttons do nothing and H4 fails with
only `bridge: invoke pause -> "none"` in the log. This is a hole in the design (my M4.4), not an implementer
error; the implementation did what §2.6 says.

**Fix.** Discover elements at the prototype level and attach direct listeners; keep the document capture as
well. `play()` is the one call every playing element must make, so hooking it is sufficient for the fallback;
hooking `load()` and the `src`/`srcObject` setters additionally catches `durationchange` before the first
`play()`. Wrapping the `Audio` constructor is not needed (the element still goes through `src`/`play`). The
hooks are JS-visible in the same way as the polyfill (see BLOCKER-1, detectability).
```js
var known = []; // elements seen via play()/load()/src
function track(el) {
  if (!(el instanceof HTMLMediaElement) || known.indexOf(el) >= 0) return;
  known.push(el);
  MEDIA_EVENTS.forEach(function (t) { el.addEventListener(t, onMedia); });
}
['play', 'pause', 'load'].forEach(function (m) {
  var orig = HTMLMediaElement.prototype[m];
  HTMLMediaElement.prototype[m] = function () { track(this); return orig.apply(this, arguments); };
});
['src', 'srcObject'].forEach(function (p) {
  var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, p);
  if (d && d.set) Object.defineProperty(HTMLMediaElement.prototype, p, {
    configurable: true, enumerable: d.enumerable, get: d.get,
    set: function (v) { track(this); return d.set.call(this, v); }
  });
});
```
Then `mediaElements()` returns `known.concat(querySelectorAll(...))` (deduplicated), and `onMedia` must
de-duplicate when an element is both tracked and in the DOM (guard with `e.__osSeen` or compare
`e.timeStamp`). Add `elements: known.length` to `diag()` so the first run shows whether the element was found.

### MAJOR-2. Clearing metadata for 5 s stops the foreground service while audio is still playing
**File:** `app/src/main/java/dev/openspotify/shell/PlaybackService.kt:70-74` (`noTrackTimeout` scheduling),
`:152` (`NO_TRACK_GRACE_MS = 5_000`); `BridgeState.kt:40-48` (empty title+artist means "cleared").

**Scenario.** Any `metadata = null` or metadata with empty title and artist (track boundary on a slow network,
a free-tier ad break, a podcast chapter marker) that lasts longer than 5 s while the element is still playing
ends with `shutdown()`: `stopForeground(REMOVE)` + `stopSelf()`. If the screen is off, the process is now a
cached background process with no FGS. `WebViewHolder.startPlaybackServiceIfPossible()` refuses to restart it
("cannot start from the background") until the Activity is visible again, so for the rest of the H5 30-minute
run there is no notification, no media session, and the process is eligible for the cached-app freezer (API 34+)
and LMK. The audio stalls when the browser process is frozen. H5 would record a false failure and the log would
only show `service: no track loaded, stopping`.

**Fix.** A track is "loaded" if there is metadata **or** the element is playing; only count down when neither
holds, and use a grace that is longer than a track transition:
```kotlin
override fun onBridgeState(state: BridgeState) {
    val loaded = state.hasTrack || state.isPlaying
    if (!loaded) {
        handler.removeCallbacks(pausedTimeout)
        if (!handler.hasCallbacks(noTrackTimeout)) handler.postDelayed(noTrackTimeout, NO_TRACK_GRACE_MS)
        session.update(state, null); updateNotification(state)   // show idle, do not freeze the old track
        return
    }
    ...
}
private const val NO_TRACK_GRACE_MS = 60_000L
```
Also log the state that triggered the timeout so the thesis can distinguish "page idle" from "metadata gap".

### MAJOR-3. Bridge state is never cleared on a main-frame navigation; a stale `elementPlaying = true` keeps the service and WebView "playing" forever
**File:** `app/src/main/java/dev/openspotify/shell/ShellWebViewClient.kt:48-50` (`onPageStarted` only logs);
`MediaBridge.kt:60-64` (`reset()` is only called from `destroy()`/`rebuild()`); `BridgeState.kt:27`
(`isPlaying` prefers the sticky `elementPlaying`).

**Scenario.** Play a track, then navigate the main frame: log out (`accounts.spotify.com`), switch arm
(`setArm` → `reload()`), open a Spotify link from the debug menu, or any full navigation. The old document is
torn down without sending anything; the page never posts a final `pause`. Native keeps `title`, `handlers` and
`elementPlaying = true`. Effects: the notification shows the old track as playing; `pausedTimeout` is never
armed, so the service stays foreground indefinitely (violates §2.7 "leaves the foreground … after 30 min
paused"); `onActivityStop` never pauses the WebView; `onTaskRemoved` believes playback is active and keeps
the process alive with nothing playing. On the new document the bridge is re-injected and installs fine, so
the state eventually self-corrects only if the user plays again.

**Fix.** Treat a new main-frame document as a reset of the observed state, keeping the installation:
```kotlin
// ShellWebViewClient.onPageStarted (main WebView only)
if (!isPopup) MediaBridge.onDocumentStarted(url)

// MediaBridge
fun onDocumentStarted(url: String) {
    val old = state
    state = BridgeState()
    Log.i(TAG, "bridge: new document $url, state cleared")
    if (state != old) listeners.toList().forEach { it.onBridgeState(state) }
}
```
`onPageStarted` fires for real navigations and reloads, not for `pushState`, so SPA route changes are
unaffected. Belt and braces: in `media-bridge.js` post `{t:'media', ev:'pagehide', paused:true}` from a
`pagehide` listener; Chromium delivers it synchronously before unload in most cases.

### MAJOR-4. `PlaybackService.onBridgeState` returns early when `!hasTrack`, leaving the MediaSession active with the previous track
**File:** `app/src/main/java/dev/openspotify/shell/PlaybackService.kt:70-74`.

**Scenario.** Metadata is cleared (see MAJOR-2) but the session is not updated: `session.isActive` stays
`true`, `PlaybackStateCompat` still says `STATE_PLAYING` with a stale `positionUpdatedAt`, so the Android 13+
media controls keep extrapolating position for a track that no longer exists, and a headset button routes to
`onPause()` against stale state. Combined with MAJOR-3 this is what the tester will see as "notification
stuck". The fix is included in the MAJOR-2 snippet (always call `session.update` and `updateNotification`,
even for the empty state; `CompatMediaSession.update` already maps `!hasTrack` to `STATE_NONE` and
`isActive = false`).

---

## MINOR

### m1. `useWideViewPort = true` for Arm A is fine; `loadWithOverviewMode` should be `true` for both arms
**File:** `BrowserIdentity.kt:42-43`. With `useWideViewPort = true` WebView honours the page's viewport meta
tag exactly like Chrome Mobile, which is what Arm A wants. But a page **without** a viewport tag (the H6
header-echo page, some `accounts.spotify.com` error pages) gets a 980 px layout at 100 % zoom and horizontal
scrolling, whereas Chrome zooms out to fit. Set `loadWithOverviewMode = true` for both arms. Deviation
accepted.

### m2. `formFactors` cannot be set; Arm B therefore reports `"Mobile"` where Chrome's "Request desktop site" reports `"Desktop"`
**File:** `UserAgents.kt:48-55`. Verified: `setFormFactors` is `@RestrictTo(LIBRARY_GROUP)` in 1.15.0. The
annotation is lint-only; the method is public bytecode. For a research PoC it is acceptable to call
`setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))` under `@SuppressLint("RestrictedApi")` and
record that in the thesis, or keep the current behaviour and record the residual difference in H6. Either way,
make the choice explicit in `README.md` (today it only says Spotify does not request the hint).

### m3. The brand rewrite is silent when the base list does not contain `"Android WebView"`
**File:** `UserAgents.kt:39-47`, `BrowserIdentity.kt:30-31`. If `getUserAgentMetadata()` on a given WebView
build returns an empty brand list (the javadoc does not promise the populated default), the rewrite becomes a
no-op and Chromium falls back to its own `"Android WebView"` brands. H6 would show the leak but nothing in the
native log explains it. Log `base.brandVersionList` and the rebuilt list in `apply()`, and `Log.w` when no
`"Android WebView"` entry was found.

### m4. `getUserAgentMetadata` is called without the feature guard that protects the setter
**File:** `BrowserIdentity.kt:30-31` vs `:37`. `FeatureGate` makes `USER_AGENT_METADATA` mandatory, so this
cannot throw today, but the asymmetry is misleading. Either drop the guard on line 37 (the gate already
requires the feature) or guard both.

### m5. `MediaBridge.invoke()` and `requestDiag()` are silent no-ops when there is no WebView or the result is `null`
**File:** `MediaBridge.kt:105, 112, 114-117`. A headset Play after `WebViewHolder.destroy()` (task removed
while paused, then process kept alive) logs nothing; `--ez osp_diag true` on a page where `evaluateJavascript`
returns `null` logs nothing. Add `Log.w(TAG, "bridge: invoke $action dropped, no WebView")` and treat a
`null`/`"null"` diag result like `"bridge missing"`.

### m6. The install step does not report which wrappers were installed
**File:** `media-bridge.js:83-116`, `:215-219`. `wrapAccessor`/`wrapMethod` return silently when a descriptor
is missing; `diag()` only checks `metadata`. Post `{t:'install', wrapped:[...]}` at the end of installation
listing the four names that were actually wrapped, and include that list in `diag()`.

### m7. `post()` swallows exceptions without a trace
**File:** `media-bridge.js:27-31`. Keep the try/catch (never break the page), but add
`console.error('osShell post failed', e)` in the catch so the failure shows up in `console:` lines in debug
builds.

### m8. `NavigationPolicy.classify` uses `java.net.URI`, which rejects URLs Chromium accepts
**File:** `NavigationPolicy.kt:23-27`. `java.net.URI` throws on characters such as `|`, `^`, `{`, `[` in a
query. A Spotify URL that trips it is classified `External` and sent to Chrome, losing the user. On parse
failure fall back to a lenient host extraction (`Regex("""^https://([^/?#]+)""")`) before deciding, or pass
`android.net.Uri`-derived host/scheme into the pure policy and keep the JVM tests on the pure function.

### m9. Google sign-in cannot be observed inside the shell
**File:** `NavigationPolicy.kt:14-19`, `ShellWebViewClient.kt:20-36`. The Google IdP popup navigates to
`accounts.google.com`, which is `External`, so it opens in the system browser and the `403
disallowed_useragent` that H2 expects to record never appears in the shell; the OAuth redirect lands in Chrome
instead. Either add `accounts.google.com` to the popup allow-list for the H2 measurement (documented as
"expected to fail"), or change H2's expected outcome to "opens externally, does not complete".

### m10. Application start touches WebView before the feature gate
**File:** `WebViewHolder.kt:55-56` (`setWebContentsDebuggingEnabled`, `CookieManager.getInstance()` in
`Application.onCreate`). On a device with the WebView provider disabled this throws
`AndroidRuntimeException` in `Application.onCreate`, before `MainActivity` can show the "unsupported" screen.
Move both calls into `create()`.

### m11. Metadata and artwork are re-sent to the MediaSession at 1 Hz
**File:** `NativeMediaSession.kt:39-49`; triggered by `PlaybackService.kt:80-82` on every `timeupdate`.
`setMetadata` with a 512 px bitmap once a second is an unnecessary binder round trip and allocation. Cache the
last `(title, artist, album, duration, artwork)` key and only call `setMetadata` when it changes;
`setPlaybackState` can stay at 1 Hz.

### m12. Default JS dialogs will crash when the WebView is detached
**File:** `ShellChromeClient.kt` (no `onJsAlert`/`onJsConfirm`/`onJsPrompt`). After `detach()` the WebView's
context is the Application context; the default `WebChromeClient` builds an `AlertDialog` on it and throws
`WindowManager$BadTokenException`. Spotify does not use `alert()` today, but it costs three lines: override
the three callbacks and `result.cancel()` when `WebViewHolder.activity == null`, otherwise call `super`.

### m13. README states the media notification is not shown without `POST_NOTIFICATIONS`
**File:** `README.md`, "Install and run". On Android 13+ notifications bound to an active media session are
exempt from the permission (they are still shown in the media area). Verify on the first run and correct the
sentence; it affects what the tester records per run.

### m14. Startup diagnostics could be one line richer
**File:** `BrowserIdentity.kt:44`. The `identity:` line logs the UA only. Append `platform`, `mobile`, and the
brand list so each run's header record is complete without running `diag()`.

---

## Items checked and found correct

- `media-bridge.js` prototype wrapping (the path that will be used once WebView ships the API natively):
  descriptors are taken from `MediaSession.prototype`, `this` is forwarded to the original
  getter/setter/method, the original is always called first, `setActionHandler(a, null)` unregisters,
  `configurable`/`enumerable`/`writable` are preserved. Getters keep working for page code that reads
  `navigator.mediaSession.metadata`. Frame guard and idempotency guard are correct. `invoke()` builds a
  `MediaSessionActionDetails`-shaped object, defaults `fastSeek = false`, reports rejected promises and
  `NotAllowedError`. `diag()` awaits both probes and posts a single message. JSON shapes match
  `BridgeState.reduce` (`metadata`, `state`, `position`, `handlers`, `media`); `NaN`/`Infinity` become `null`
  and are handled. The `NoMediaSession` error path did its job on the emulator: the failure was visible, not
  swallowed.
- `MediaBridge.install`: `addWebMessageListener` before `addDocumentStartJavaScript`, both before the first
  `loadUrl`; sub-frame messages are dropped natively; everything stays on the UI thread.
- `WebViewHolder`: one WebView behind a `MutableContextWrapper`; `attach`/`detach` swap the base context and
  re-parent; `rebuild()` defers out of the dying callback, destroys the old view, re-creates with settings,
  identity, both clients and the bridge (doc-start script and message listener are per-WebView and are
  re-registered), re-attaches to the same parent and reloads the last URL. No Activity leak after `detach`
  (`activity` cleared, popups dismissed). `onPause()` only when not playing; never `pauseTimers()`; WebView is
  resumed before native-initiated JS (deviation accepted).
- `BrowserIdentity`/`UserAgents`: Chromium major parsed from the default UA and never spoofed; reduced Chrome
  UA per arm; UA-CH built from the pristine default via the Builder copy constructors with only the brand
  string replaced (versions and grease untouched); Arm B sets `platform="Linux"`, `mobile=false`, `model=""`,
  `platformVersion=""`. Each arm is internally consistent.
- `PlaybackService`: started only from a visible Activity (`activityVisible` is true between `onStart` and
  `onStop`) and re-started from `onStart` when a track is loaded (deviation accepted, it is required by the
  API 31+ start rules); `ServiceCompat.startForeground` with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`, type and
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission declared; stays foreground while a track is loaded; 30 min
  paused timeout; `onTaskRemoved` keeps running while playing and tears down otherwise; no audio focus anywhere.
  A second `startForegroundService()` from `MediaButtonReceiver` while the service is already foreground does
  not require another `startForeground()` (ActivityManager clears `fgRequired` for a service that is already
  foreground), and the cold path goes through `onCreate` → `enterForeground` first.
- `MediaStyle` notification bound to `MediaSessionCompat.Token`, compact actions 0–2, `MediaButtonReceiver`
  declared and used for the action PendingIntents; `handleMediaButton` dispatches through the controller so
  the framework maps `PLAY_PAUSE` using the advertised `PlaybackStateCompat` actions.
- `ShellChromeClient`: only `PROTECTED_MEDIA_ID` for `open.spotify.com`, everything else denied; popups get the
  same settings/identity, are delivered through `WebViewTransport`, and are destroyed on dismiss; the popup
  variant never rebuilds the main WebView.
- `ShellWebViewClient`: main-frame-only policy, `spotify:`/`intent:` blocked, others external, pushState
  captured through `doUpdateVisitedHistory`, `onRenderProcessGone` returns `true`.
- Manifest: `singleTask`, the full `configChanges` list, `foregroundServiceType="mediaPlayback"`, all three
  permissions, exported `MediaButtonReceiver`, non-exported service with the `MEDIA_BUTTON` filter.

## Judgement on the flagged deviations
1. Arm A `useWideViewPort = true`: accept (m1, add overview mode for both arms).
2. `formFactors` not settable: confirmed in the jar; accept, decide and document (m2).
3. 5 s grace before stopping when metadata clears: reject as implemented (MAJOR-2); make it 60 s and gate on
   "nothing playing".
4. WebView resumed before `evaluateJavascript`: accept, it is required.
5. Service (re)started from `onStart` when a track is loaded: accept, it is required by the API 31+ rules.

## Required DESIGN.md changes (v0.3)
- §2.6: replace "The web player publishes everything a native player needs via the standard
  `navigator.mediaSession`. Android WebView does not wire this to the system" with: Android WebView does not
  expose the Media Session API to pages at all (Blink feature disabled; confirmed on WebView 124). The bridge
  therefore **provides** a spec-conformant `MediaMetadata`/`MediaSession`/`navigator.mediaSession` at
  document start when absent (BLOCKER-1) and wraps the native one when present. State explicitly that this is
  a polyfill of a standard API, not DOM or content modification, and that it is JS-detectable.
- §2.6 item 3: the media element is detached; element discovery via `HTMLMediaElement.prototype` hooks
  (MAJOR-1) replaces "capture-phase listeners on `document`" as the primary mechanism (keep capture as backup).
- §2.7: "track loaded" means metadata present **or** element playing (MAJOR-2); a main-frame navigation clears
  the observed state (MAJOR-3).
- §4: add to the automatable checks `session: "polyfill"` in `diag()` and `elements ≥ 1` after pressing play
  anonymously (the anonymous preview plays 30 s clips on the mobile site; verify). Note that H4 may only be
  passable in the arm whose bundle calls the Media Session API.
- §5 risk 1: the mobile bundle shows an "Open App" header and is a different bundle from desktop; H1 must
  compare feature coverage of both bundles, not only layout.
