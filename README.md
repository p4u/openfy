# openfy (research)

A first proof of concept for a CS-degree research project: an Android app that is a **browser-like shell
around the official Spotify Web Player** (`https://open.spotify.com`). You sign in with your own Spotify
account in the web player. Everything you see (content, artwork, layout, styling) comes from the web
player. The shell adds Chrome-equivalent capability signals and a native media integration (notification,
lock screen, headset buttons, background playback).

The design is in [`docs/DESIGN.md`](docs/DESIGN.md) (v0.4) with the rationale in
[`docs/reviews/design-review-fable.md`](docs/reviews/design-review-fable.md) and
[`docs/reviews/poc-code-review-fable.md`](docs/reviews/poc-code-review-fable.md). Measured results (emulator) are in
[`docs/poc-results.md`](docs/poc-results.md). Source files reference
design sections as `DESIGN §x.y`.

## Ethics and non-goals

This is a personal research tool. It does not and will not:

- block ads (audio, third-party or display), block or inspect any network request, or touch playback;
- modify the page, with **one deliberate, documented, switchable exception**: the "UI adaptation" mode (see below,
  DESIGN §2.9), which hides or dismisses first-party app-install nags and first-party Premium upsell banners and
  nothing else;
- download, record or otherwise capture streams;
- circumvent DRM (playback uses the web player's own EME/Widevine pipeline, L3 is enough);
- defeat Google's embedded-WebView sign-in policy (`disallowed_useragent` is the expected, documented outcome);
- use private Spotify APIs or Web API keys. Native code's only network access **towards Spotify** is fetching the
  artwork URL the page itself published through `navigator.mediaSession`. The only other native network access is
  the self-updater ([`docs/UPDATER.md`](docs/UPDATER.md)): it talks exclusively to GitHub (`api.github.com` and the
  release-asset hosts) over HTTPS to check for and download new releases of this app, at most once a day, sends no
  identifiers beyond its own version string, and never touches the Spotify session.

The shell stays honestly identifiable as a WebView host: Android WebView sends `X-Requested-With: <package>`
on every request and that cannot be removed. The shell injects two scripts. `ui-adapt.js` is the UI adaptation
mode below. `media-bridge.js` does two things. Android WebView ships without the Media Session API, so it **provides** a spec-conformant
`MediaMetadata`/`MediaSession`/`navigator.mediaSession` (a polyfill of a standard API; it wraps the native one if
a future WebView has it), and it **observes** media elements (including detached ones). It does not touch the
DOM or CSS and changes nothing Spotify's servers see. The polyfill is JS-detectable (its functions have source
text, not `[native code]`) and is deliberately not disguised.

### UI adaptation (on by default, DESIGN §2.9)

A research decision, recorded as a deviation from the v0.3 "no content modification" non-goal: inside a dedicated
shell, first-party promotion for installing the app, or for Premium, has no function. With the mode on, the shell
- hides the "Open App" header button (`button[data-testid="download-button"]`) and the first-party Premium banner
  (`[data-testid="premium-banner"]` in `[data-testid="home-banner"]`) with CSS in an adopted stylesheet;
- dismisses the "get the app" popover (for example "Enjoy Your Library / Not now / Get App" after tapping the Library
  tab as an anonymous user) by clicking its own "Not now" button. If a popup has no dismiss button it is left alone.

What it does **not** do, and will not: block audio ads or third-party/display ads, block or filter any network
request, change playback, touch the cookie/consent dialog, or hide the sign-up dialog the web player shows to
logged-out users. The rules are in `app/src/main/assets/ui-rules.json` (declarative, `data-testid` first, never hashed
class names) and applied by `ui-adapt.js`. The one place that needs text matching (the "Get App" label of the popover,
in 70 of Spotify's own locales) is described in DESIGN §2.9.

Toggle: debug menu -> **UI adaptation: on/off** (persisted as the `ui_adaptation` preference, takes effect by
reloading the page). Evidence and logs: `adb logcat -s Openfy | grep adapt:`; `__osAdapt.stats()` or
Diagnostics show per-rule match counts, the Spotify bundle hash and rules that never matched; the Diagnostics
dialog also lists, per bundle hash, which rules matched (rule survival across Spotify deploys).

## Build

Requirements: JDK 17 and the Android SDK (platform 35, build-tools 35.0.0). In this repo they are provided in
user space:

```sh
source tools/env.sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lint
node --test tools/test/*.test.mjs   # host tests: Media Session polyfill, UI adaptation rules/scheduler/dismissal (Node 22, no dependencies)
```

Stack: Kotlin 2.0.21, AGP 8.7.3, Gradle 8.14.3, compileSdk/targetSdk 35, minSdk 26, androidx.webkit 1.15.0,
androidx.media 1.7.1. No other dependencies.

## Releasing

Publishing a GitHub Release (tag `vX.Y.Z` or `vX.Y.Z-rc.N`) builds, signs and verifies `openfy-<version>.apk` in
GitHub Actions and attaches it to the release. One-time setup, how to cut a release and the keystore backup rules
are in [`docs/RELEASING.md`](docs/RELEASING.md); the spec is `docs/UPDATER.md` section 1. Local signed build:
`./gradlew assembleRelease -PversionName=0.1.0 -PrequireSigning=true`.

## Install and run

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.openfy.debug/dev.openfy.MainActivity
```

The debug build installs as `dev.openfy.debug` (label "openfy debug") and the release build as `dev.openfy`
(label "openfy"), so both can coexist on one device. The `--es`/`--ez` extras below work on either; for the
release build use `-n dev.openfy/dev.openfy.MainActivity`. The old PoC install `dev.openspotify.shell` is a
separate app and can be uninstalled (`adb uninstall dev.openspotify.shell`).

Needs a WebView with Chromium >= 120 (the app shows an error screen otherwise). On Android 13+ allow the
notification permission. Measured on Android 15: without it the foreground service still runs and the media
controls card (title, artist, artwork, transport buttons) is still shown in the system media area, because
notifications bound to an active media session are exempt, but the ordinary notification entry is not listed.
Record the permission state for every run.

Automation extras for `adb`:

| Extra | Effect |
|---|---|
| `--es osp_url <https url>` | load that URL (any URL; it is still subject to the navigation policy for later navigations) |
| `--ez osp_diag true` | after the page finishes loading, run `__osShell.diag()` and log the JSON (`adb logcat -s Openfy`, lines `diag[n]:`) |

With several devices attached, set `ANDROID_SERIAL` (or use `adb -s`). Helpers: `tools/emulator.sh {start|stop|shot <file>}`,
`node tools/cdp-eval.mjs <port> '<js>' [gesture]` (evaluate in the shell's WebView after
`adb forward tcp:<port> localabstract:webview_devtools_remote_$(adb shell pidof dev.openfy.debug)`) and
`tools/h4-session.js` (a synthetic Media Session for the native half of H4).

The small translucent button in the top-right corner opens the debug menu:

- **Identity** switches between Arm A (Chrome Mobile) and Arm B (Desktop site) and reloads. The choice persists.
- **Open Spotify link...** loads a pasted `https://*.spotify.com` URL in the shell (magic links from email open in
  the system browser otherwise).
- **Diagnostics** runs `__osShell.diag()` and shows the result.
- **Reload**.
- **UI adaptation: on/off** switches the UI adaptation mode (default on) and reloads.
- **Check for updates** runs the updater by hand (see Updates).

## Updates

The app can update itself from the GitHub Releases of `p4u/openfy` (spec: [`docs/UPDATER.md`](docs/UPDATER.md), code:
`app/src/main/java/dev/openfy/update/`).

- **When:** release builds check on app start at most once per 24 h; debug builds never check by themselves and use
  the debug menu item **Check for updates**. A newer release shows a dialog with the notes and **Install / Later /
  Skip this version**.
- **What is verified before anything is installed:** HTTPS only and an exact host allow-list (`api.github.com`,
  `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`, redirects followed by hand);
  the SHA-256 of the download against the release's `digest` (or `.sha256` asset); package name, a strictly higher
  `versionCode`; and that the APK is signed by the same certificate(s) as the installed app. The signer check, backed
  by Android's own update rule, is the real authentication; the checksum only catches corruption.
- **Install:** `PackageInstaller` session. Android asks for the "install unknown apps" permission for openfy once and
  shows its own confirmation sheet (always for the first update after a sideload).
- **Limits:** no protection against a leaked signing key; a debug build refuses release-signed APKs (different key).
- **Testing:** the pure logic has JVM tests (`app/src/test/java/dev/openfy/update/`). A debug-only build property
  `-PupdaterTestBase=http://127.0.0.1:<port>` lets an emulator run be pointed at a local fake GitHub; release builds
  cannot carry it (the value is empty and the host policy ignores it). Run record: `docs/poc-results.md`, H9.

## Architecture in one paragraph

`WebViewHolder` (process singleton) owns the one WebView, created with a `MutableContextWrapper` so it survives
Activity destruction; `MainActivity` is only a window for it. `BrowserIdentity` applies the UA string, UA client
hints and viewport for the chosen arm. `media-bridge.js` is injected at document start
(`addDocumentStartJavaScript`), provides the Media Session API the WebView lacks, observes media elements, and talks
to `MediaBridge` through `addWebMessageListener`; `MediaBridge` keeps a `BridgeState` (cleared on every new
main-frame document). `UiAdaptation` injects `ui-adapt.js` with a second document-start handle (removed and re-added by the
debug toggle) and records what it reports. `PlaybackService` (foreground, `mediaPlayback`) mirrors that state into a `MediaSessionCompat` and a
MediaStyle notification and routes transport callbacks back into the page. Native code never requests audio
focus; WebView already does.

## Hypothesis runbook (DESIGN §4)

Record for every run: device, Android version, WebView package version (logged at startup as
`startup: webview=...`), UA arm, and whether `POST_NOTIFICATIONS` was granted.

Start logging:

```sh
adb logcat -c
adb logcat -s Openfy chromium
```

Log prefixes you will see: `startup:`, `identity:`, `drm:`, `nav:`, `permission:`, `window:`, `bridge:`,
`service:`, `artwork:`, `adapt:`, `webview:`, `renderer gone:`, `console:` (debug builds).

**Automatable without an account (emulator or device):**

- **H1, anonymous render.** Launch, take a screenshot per arm (menu -> Identity), compare with Chrome on the same
  device. Check `identity:` lines for the UA actually set.
- **H6.** Run Diagnostics in each arm; compare `ua` and `uaData` (brands must contain `Google Chrome` and not
  `Android WebView`; `platform`/`mobile` per arm) with `navigator.userAgentData.getHighEntropyValues` in Chrome on
  the same device (`chrome://inspect`). For headers, use a header-echo page (your own endpoint or httpbin.org). A
  host outside the allow-list opens in the system browser via `--es osp_url`, so drive the shell with CDP
  (`Page.navigate`, or `fetch()` from the page for fetch headers only) and diff against Chrome; the only expected
  extra is `X-Requested-With`. Known residual differences: `navigator.platform` and `navigator.languages`
  (see `docs/poc-results.md`).
- **Bridge installed / DRM capability.** `adb shell am start -n dev.openfy.debug/dev.openfy.MainActivity --ez osp_diag true`.
  In the `diag` JSON, `bridgeInstalled: true`, `session: "polyfill"` (`"wrapped-native"` on a WebView that ships the
  API, `"missing"` is a failure), `widevine: true` and, after any media element has played, `elements >= 1` are the
  pass signals (`adapt` is the UI adaptation stats, or `null` when the mode is off). The page-side checks are `'mediaSession' in navigator` and `typeof MediaMetadata`, both truthy.
  Startup also logs `drm: Widevine supported, securityLevel=...`. The first bridge message of each document is
  `{"t":"install","session":...}`.
- **H4, native half.** `node tools/cdp-eval.mjs <port> "$(cat tools/h4-session.js)"` sets a synthetic session; then check
  `adb shell dumpsys media_session`, `dumpsys notification --noredact`, `dumpsys activity services dev.openfy.debug`.
  Media keys (`adb shell input keyevent KEYCODE_MEDIA_NEXT`) only reach the session while the app is actually playing
  audio, so for a synthetic run also start a test `Audio` element with `gesture`.
- **H7, renderer kill and rotation.** Rotate (`adb shell settings put system user_rotation 1`, with
  `accelerometer_rotation 0`) and confirm no `webview: created` line appears again and `performance.timeOrigin` is
  unchanged. Kill the renderer: `adb shell kill` is not permitted on a non-root image, so use the DevTools protocol
  (`Page.crash`) or `chrome://inspect`; expect `renderer gone:` followed by `webview: rebuilt, reloading ...`.

**Needs a human with an account:**

- **H0 (control).** In Chrome on the same device, play `open.spotify.com` in mobile and in desktop mode, press Home
  and turn the screen off for 30 min. Note which mode keeps playing.
- **H2, login.** Email/password (and email-code) at `accounts.spotify.com`; force-stop the app
  (`adb shell am force-stop dev.openfy.debug`) and relaunch; you should still be logged in. Try Facebook and
  Apple (popups: look for `window: popup opened` / `popup closed`). Google should fail with
  `403 disallowed_useragent`; passkeys are expected to be unavailable.
- **H3, Widevine playback.** Play a track and a podcast. Expect `permission: EME granted for https://open.spotify.com`
  and, from the system, an `AudioFocusDelegate` focus request:
  `adb logcat | grep -i AudioFocusDelegate`. A failure shows Spotify's "Playback of protected content is not enabled".
- **H4, bridge end to end.** Expect `bridge: {"t":"metadata",...}`, `{"t":"state",...}`, `{"t":"position",...}`,
  `{"t":"handlers",...}` and 1 Hz `{"t":"media","ev":"timeupdate",...}` lines, then `artwork: loaded <url>`. Use the
  notification buttons and a headset/Bluetooth button; each logs `bridge: invoke <action> -> "handler"` (or
  `"element"` for the play/pause fallback). Repeat after 10 min paused. A `{"t":"error",...,"name":"NotAllowedError"}`
  line means a native-initiated `play()` lacked user activation; only then consider
  `mediaPlaybackRequiresUserGesture = false` in `WebViewConfig`.
- **H5, background survival.** 30 min screen-off with at least 5 automatic track changes; 10 min paused then resume
  from the notification; rotation while playing; swipe from recents while playing (log:
  `service: task removed while playing, staying alive`). Use the logcat timestamps.

## Known limitations

- UI adaptation matches Spotify's current `data-testid`s and Encore ids. A Spotify deploy can rename them; the nag then
  simply reappears (see `neverMatched` and the per-bundle record). The popover rule is label-gated, with no labels for
  `ar`, `ar-EG`, `th`, `vi`. It does nothing about the sign-up dialog or the "Premium" tab in the bottom navigation.
- Google sign-in does not complete in the shell by design: the Google popup navigates to `accounts.google.com`,
  which opens in the system browser, so the `disallowed_useragent` error is not shown in the shell. Passkeys are
  unavailable in WebView.
- `X-Requested-With` cannot be removed and is not hidden.
- The page is assumed `hidden` in the background. No visibility shim exists; add one only if H0/H5 show the page
  self-pausing while Chrome does not (record it as a deviation).
- UA-CH `formFactors` cannot be set through the public androidx.webkit API (it is `@RestrictTo`; calling it anyway
  would need `@SuppressLint("RestrictedApi")`, a choice deliberately not made). Arm B therefore reports
  `"Mobile"` where Chrome's "Request desktop site" reports `"Desktop"`. Spotify does not request it today; `diag()`
  reports it and it is recorded in H6.
- Arm B can only approximate Chrome's "Request desktop site": WebView has no switch to ignore the viewport meta tag.
- With the polyfill, attribute changes on a `MediaMetadata` after assignment are observed (as in Chrome). If a future
  WebView ships the native API, the wrapper path observes assignments only.
- `navigator.platform` (WebView: real `uname`, Chrome Android: a fixed `Linux armv81`) and `navigator.languages`
  (WebView: `["en-US"]`, Chrome: `["en-US","en"]`) differ from Chrome and are not patched (see `docs/poc-results.md`).
- Arm B (desktop bundle) is cropped at phone width: the page forces `maximum-scale=1` with an 800 px layout. Arm A is
  the usable arm.
- The overflow button floats over the page's top-right corner and may cover page UI; that is the cost of having
  no native chrome.
- `androidx.media` (MediaSessionCompat) is in maintenance mode; Media3 is the planned upgrade (without audio focus).
- Artwork is fetched with a plain `HttpURLConnection`; no caching beyond the current track.
- The release build is unminified (`isMinifyEnabled = false`, by decision). It is signed only when a release keystore is
  configured (see `docs/RELEASING.md`); otherwise it is left unsigned.
