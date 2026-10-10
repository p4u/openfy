# PoC results (emulator run, DESIGN v0.3; H8 added with v0.4)
> Note: this document was written when the app was still called open-spotify (package `dev.openspotify.shell`, logcat tag `OpenSpotify`, JS bridge `OpenSpotifyBridge`). Recorded logs and commands below keep those old names as historical evidence; they are now `dev.openfy` / `dev.openfy.debug`, tag `Openfy`, `OpenfyBridge`.

Everything below was run against the debug build in this repository. Nothing was simulated except where a
section says "synthetic". Commands are shown with the key output lines. Screenshots are in
`docs/research/screenshots/`.

## Run metadata

| Item | Value |
|---|---|
| Device | Android Emulator AVD `osp35`, `sdk_gphone64_x86_64`, Google Play image, headless |
| Android | 15 (API 35), build `AE3A.240806.036` |
| WebView | `com.google.android.webview` 124.0.6367.219 (provider reported by the app at startup) |
| Control browser | `com.android.chrome` 124.0.6367.219 is installed on this image (same version as the WebView). The task brief assumed there was none; it launches fine ("Use without an account", notifications "No thanks"). It is used as the H6 control below. |
| App | `dev.openspotify.shell` 0.1.0-poc, debug, built from the working tree (uncommitted) |
| UA arm | Arm A (Chrome Mobile) unless a section says Arm B. Arm B was set with `run-as ... shared_prefs/open_spotify.xml` (`identity_arm=DESKTOP`) and an app restart. |
| `POST_NOTIFICATIONS` | revoked for the m13 check, granted (`adb shell pm grant`) for everything else |
| Account | none (anonymous). The cookie banner and "Open App" prompts were left untouched; nothing was clicked in the page. |
| Host tests | `./gradlew assembleDebug testDebugUnitTest lint`: BUILD SUCCESSFUL, 31 JVM tests, 0 lint issues (v0.3 run; the v0.4 run is in H8). `node --test tools/test/*.test.mjs`: 20/20 pass (Node 22.22.1). |

Setup used for the CDP checks (re-run after every app restart):

```sh
export ANDROID_SERIAL=emulator-5554      # a physical phone was attached too; it was not touched
PID=$(adb shell pidof dev.openspotify.shell)
adb forward tcp:9333 localabstract:webview_devtools_remote_$PID
node tools/cdp-eval.mjs 9333 '<js expression>' [gesture]
```

`tools/cdp-eval.mjs` gained an optional third argument `gesture` (evaluate with a user activation); it is only used
to start a test `Audio` element, never on Spotify's own player.

## Summary

| # | Hypothesis | Status | One-line evidence |
|---|---|---|---|
| H0 | Chrome control plays and survives screen-off | NOT TESTED (needs account) | instructions below |
| H1 | Web player usable in the shell, arm choice | PARTIAL | Arm A (mobile bundle) renders and navigates anonymously; Arm B (desktop bundle) loads but is cropped at phone width; playback not tested |
| H2 | Login, IdPs, persistence | NOT TESTED (needs account) | instructions below |
| H3 | Widevine playback | PARTIAL | capability PASS (L3, EME granted, `requestMediaKeySystemAccess` resolves); playback NOT TESTED (needs account) |
| H4 | Bridge sees state; native can invoke | PARTIAL | native half PASS with a synthetic session (metadata, state, notification, FGS, media keys, on-screen buttons); real Spotify metadata NOT TESTED |
| H5 | Background survival | PARTIAL | 133 s screen-off: service foreground, JS timers unthrottled, audio kept playing; 30 min and track changes NOT TESTED |
| H6 | Network identity equals Chrome except X-Requested-With | PARTIAL | request headers identical to Chrome 124 on the same device except `X-Requested-With`; JS-visible `navigator.platform` and `navigator.languages` differ |
| H7 | Survives Activity destruction and renderer crash | PARTIAL | rotation, recents-swipe (playing and paused) and renderer crash all PASS; "still logged in after rebuild" NOT TESTED (anonymous) |
| H8 | UI adaptation (DESIGN §2.9) | PASS (emulator, anonymous) | ON: Open App button and premium banner `display:none`, Library-tab popover auto-dismissed, scroll and navigation intact, bridge unchanged (`session:"polyfill"`); OFF: all reappear; physical device: script installed, not visually verified (see H8) |

Bridge installation (DESIGN §4 automatable): PASS, see "Bridge installation" below.

## Fixes that these runs verified on the device

- BLOCKER-1: the page sees `'mediaSession' in navigator === true`, `typeof MediaMetadata === "function"`; `diag().session === "polyfill"`.
- MAJOR-1: Spotify-style detached `new Audio()` is observed (`elements: 2` in `diag()`, `media` messages arrive, the play/pause hooks list is in the `install` message).
- MAJOR-2: after the renderer crash the state is empty; the service waited and stopped after 60 s with `service: nothing loaded for 60s, stopping (title=null declared=none element=null)`, not after 5 s.
- MAJOR-3/4: navigating the main frame while the synthetic session was playing logged `bridge: new document https://open.spotify.com/search, state cleared`; `dumpsys media_session` then showed `active=false` and `state=NONE(0)`.
- m13: README sentence corrected, see H4.

## Bridge installation

```
$ adb logcat -d -s OpenSpotify | grep '"t":"install"'
bridge: {"t":"install","session":"polyfill","wrapped":["MediaMetadata","MediaSession","Navigator.prototype.mediaSession"],"hooks":["play","pause","load","src","srcObject"],"elements":0}
$ node tools/cdp-eval.mjs 9333 'JSON.stringify([ "mediaSession" in navigator, typeof MediaMetadata, typeof MediaSession ])'
[true,"function","function"]
$ node tools/cdp-eval.mjs 9333 '__osShell.diag().then(()=>1)'   # then logcat diag[0]
"session":"polyfill" ... "bridgeInstalled":true ... "widevine":true ... "elements":0 ... "handlers":[]
```

Status: **PASS** (`session:"polyfill"`, both feature checks true, `widevine:true`).

Does Spotify's bundle use it? Both bundles contain Media Session code (fetched through the page and counted):

```
mobile-web-player.fe7b9205.js   mediaSession x23, setActionHandler x32, MediaMetadata x3
web-player/*.js (50 files)      mediaSession x25, setActionHandler x33
```

Anonymous registrations seen by the bridge on page load:

| Arm / bundle | Registrations anonymously |
|---|---|
| A, `mobile-web-player` | none (no `handlers`, `metadata`, `state` message on home, `/search`, album) |
| B, `web-player` | `handlers` grows to `["previoustrack","nexttrack","play","pause"]` about 2 s after load, on every page load; no metadata until something is loaded |

So the desktop bundle does feature-detect and use the polyfill; the mobile bundle may only register during
logged-in playback (not testable here). This is what the review predicted for H4 ("may only be passable in the
arm whose bundle calls the API"); it must be re-checked per arm with an account.

## H1: web player usable in the shell (anonymous)

Status: **PARTIAL** (Arm A usable, Arm B renders but is not usable at phone width, no playback without account).

Arm A (Chrome Mobile), screenshot `docs/research/screenshots/h1-arm-a.png`:

```
identity: arm=MOBILE ua=Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 platform=Android mobile=true brands=[Chromium/124, Google Chrome/124, Not-A.Brand/99] defaultBrands=[Chromium/124, Android WebView/124, Not-A.Brand/99]
bundle (performance.getEntriesByType('resource')): mobile-web-player/mobile-web-player.fe7b9205.js, vendor~mobile-web-player.04a3b031.js, mwp-home-page..., mwp-search-*.js
title: "Spotify - Web Player: Music for everyone"; innerWidth 412
```

- App wall: no blocking wall. The page shows an "Open App" header button, a Premium upsell card and the cookie banner (left untouched).
- `https://open.spotify.com/search`: title "Spotify – Mobile Web Player", renders "Search / What do you want to listen to? / Browse all" and the category tiles.
- `https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy`: title "Global Warming - Album by Pitbull | Spotify", "Open App", album header, track list (18 songs).
- Console noise in debug logs: `Minified React error #421` once at home load and a Widevine robustness warning from Spotify's own code; the page still rendered. Not investigated further (it was not compared with Chrome).

Arm B (Desktop site), screenshot `docs/research/screenshots/h1-arm-b.png`:

```
identity: arm=DESKTOP ua=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 platform=Linux mobile=false brands=[Chromium/124, Google Chrome/124, Not-A.Brand/99] ...
bundle: web-player (desktop). Page text: "Skip to main content | Install App | Sign up | Log in | Your Library | ..."
/search -> title "Spotify – Search"; /album/4aaw... -> "Global Warming - Album by Pitbull | Spotify" (both load)
innerWidth 800, visualViewport.width 412.19, scale 1, meta viewport "width=device-width, initial-scale=1, maximum-scale=1"
```

Arm B gets the desktop bundle with the "Install App / Sign up / Log in" chrome, but the layout is 800 CSS px wide
in a 412 px viewport with `maximum-scale=1`, so overview mode cannot zoom out and the screenshot is cropped on
the right. This is the limitation already noted in the README (WebView cannot ignore the viewport meta tag).
Not fixed. Recommendation for the thesis: Arm A is the working arm for H1; Arm B is only informative (bundle
comparison, handler registrations).

## H2: login (NOT TESTED, needs account)

Instructions for a real phone (a spare Spotify account is best):

1. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Allow notifications when asked. Start logging: `adb logcat -c; adb logcat -s OpenSpotify chromium > h2.log`.
2. Open the app, choose the debug menu (translucent button, top right) and note the arm (default Arm A).
3. Tap "Log in" on open.spotify.com. Enter email and password. If asked for an emailed code, enter it in the same page. Expected: you land on the home page logged in. Note any captcha or challenge page (`challenge.spotify.com` should stay in the shell).
4. Persistence: `adb shell am force-stop dev.openspotify.shell`, relaunch from the launcher. Expected: still logged in, no login prompt.
5. Facebook and Apple: log out, choose "Continue with Facebook" and then "Continue with Apple". Look for `window: popup opened` and `popup closed` in the log. Record: completes / fails cleanly / hangs.
6. Google: choose "Continue with Google". Expected (changed in v0.3): the popup opens in the system browser (`nav: external https://accounts.google.com/...`) and sign-in does not complete in the shell; no `disallowed_useragent` page appears in the shell. Record what the system browser does.
7. Passkeys: if Spotify offers one, record that it is unavailable or fails.
8. Record: device, Android version, WebView version (`startup: webview=` line), arm, notification permission state, and `identity:` line.

## H3: Widevine (capability PASS, playback NOT TESTED)

```
drm: Widevine supported, securityLevel=L3 version=19.0.1@AV1A.240428.001 vendor=com.google.android.widevine
permission: EME granted for https://open.spotify.com/            (logged when Spotify's page probes EME on load)
requestMediaKeySystemAccess('com.widevine.alpha', cenc, audio mp4a.40.2 + video avc1, SW_SECURE_CRYPTO) -> resolved, keySystem "com.widevine.alpha"
diag: "widevine": true
```

Playback of a track and a podcast needs an account: **NOT TESTED**. Steps for a human: log in (H2), play a track and
a podcast, expect no "Playback of protected content is not enabled", and `adb logcat | grep -i AudioFocusDelegate`
shows a focus request.

## H4: bridge end to end (native half PASS with a synthetic session; real Spotify metadata NOT TESTED)

Status: **PARTIAL**. The synthetic session is `tools/h4-session.js` (sets `MediaMetadata{title:'PoC Test',
artist:'Bridge', album:'H4', artwork: i.scdn.co 640x640}`, `playbackState='playing'`,
`setPositionState({duration:200, position:10, playbackRate:1})`, handlers for play/pause/nexttrack/previoustrack/seekto
recording into `window.__h4calls`).

```
bridge: {"t":"metadata","title":"PoC Test","artist":"Bridge","album":"H4","artwork":[{"src":"https://i.scdn.co/image/ab67616d0000b273d8601e15fa1b4351fe1fc6ae",...}]}
bridge: {"t":"state","declared":"playing"}   bridge: {"t":"position","position":10,"duration":200,"rate":1}
bridge: {"t":"handlers","actions":["play","pause","nexttrack","previoustrack","seekto"]}
service: created      artwork: loaded https://i.scdn.co/image/ab67616d...

$ adb shell dumpsys media_session
  OpenSpotify dev.openspotify.shell/OpenSpotify/7   active=true
  state=PlaybackState {state=PLAYING(3), position=10000, speed=1.0, actions=822, ...}
  metadata: size=5, description=PoC Test, Bridge, H4
$ adb shell dumpsys notification --noredact | grep -A5 dev.openspotify.shell
  NotificationRecord(... pkg=dev.openspotify.shell id=1 ... channel=playback flags=ONGOING_EVENT|...|FOREGROUND_SERVICE ... category=transport actions=4 vis=PUBLIC)
  android.title=String (PoC Test)
$ adb shell dumpsys activity services dev.openspotify.shell
  ServiceRecord{... dev.openspotify.shell/.PlaybackService}  isForeground=true foregroundId=1 types=0x00000002   (0x2 = mediaPlayback)
```

Media keys. First attempt failed and the cause is a real platform behaviour, recorded here because it affects
the H4 procedure: with the synthetic session alone, `adb shell input keyevent KEYCODE_MEDIA_NEXT/PREVIOUS/PAUSE/PLAY`
reached nothing (`__h4calls == []`, no `bridge: invoke` lines) because `dumpsys media_session` said
`Media button session is null`. Android only routes media keys to a session whose app is actually playing audio.
After starting a looping test `Audio` element in the page (`tools/cdp-eval.mjs ... gesture`; this is also the
detached-element test for MAJOR-1) `Media button session is dev.openspotify.shell/OpenSpotify/7`, and:

```
$ for k in NEXT PREVIOUS PAUSE PLAY; do adb shell input keyevent KEYCODE_MEDIA_$k; sleep 1.5; done
$ node tools/cdp-eval.mjs 9333 'window.__h4calls'
["{\"action\":\"nexttrack\"}","{\"action\":\"previoustrack\"}","{\"action\":\"pause\"}","{\"action\":\"play\"}"]
bridge: invoke nexttrack  -> "handler"   (previoustrack, pause, play likewise)
```

Order and `MediaSessionActionDetails` shape are as expected. The on-screen media card buttons (system UI, tapped with
`adb shell input tap`) produced `nexttrack`, `pause`, `previoustrack` in the same way. `seekto`: the advertised actions
include seek (822), but dragging the card's seek bar with `input swipe` produced no call; `seekto` is only verified at
the JS level (`__osShell.invoke('seekto', ...)` -> `{action:'seekto',seekTime,fastSeek:false}` in the Node test). Treat
native seek as **NOT TESTED**.

Expanded shade screenshot (collapsed afterwards): `docs/research/screenshots/h4-notification.png`. On Android 15 the
MediaStyle notification is rendered as the media controls card (title "PoC Test", artist "Bridge", the artwork, previous /
pause / next and a progressing seek bar), not as a separate entry in the notification list.

m13 (README claim), measured: with `POST_NOTIFICATIONS` revoked, the service still became foreground, the
`NotificationRecord` existed, and the media card was still shown (`docs/research/screenshots/m13-no-notification-permission.png`).
Only the ordinary notification-list entry is absent. README corrected accordingly.

Not tested: real Spotify metadata, headset button, the 10-minute-paused resume (needs an account). Steps for a human:
log in, play a track in Arm A and again in Arm B, `adb logcat -s OpenSpotify | grep 'bridge:'` and record which arm
produces `metadata`/`handlers`/`state`/`position`, then use the notification buttons and a Bluetooth/wired headset
button; pause for 10 minutes and resume from the notification.

## H5: background survival (PARTIAL)

Procedure: synthetic session + looping test `Audio` element (so the media button session and audio focus exist),
`setInterval` counter and `visibilitychange` log in the page, then HOME and `KEYCODE_SLEEP`, 133 s later wake.

```
$ adb shell input keyevent KEYCODE_HOME; adb shell input keyevent KEYCODE_SLEEP    # mWakefulness=Asleep
after ~60 s : ticks 62, timeupdate 183, vis ["visible","hidden"], audioPaused false
after ~133 s: {"ticks":134,"elapsedMs":133000,"gapMin":1000,"gapMedian":1000,"gapMax":1000,"over1500":0,
               "timeupdates":397,"vis":["visible","hidden"],"audioPaused":false}
dumpsys activity services: PlaybackService isForeground=true types=0x2
dumpsys media_session: state=PLAYING(3)
logcat: no 'service:' stop line, no renderer gone
```

Observation: `visibilityState` becomes `hidden` immediately (as the design assumes). With audio playing there is no timer
throttling at all (every 1 s timer fired exactly 1000 ms apart), so Chrome's intensive throttling of silent hidden
pages did not apply. The page's media element kept playing and the foreground service stayed up.

Limits: 133 s instead of 30 min, a synthetic page element instead of Spotify's player, no automatic track changes,
emulator power management. The 30-minute, five-track-change run, the 10-minute pause and the swipe from recents with
real playback need a human (see H0 for the control). Steps: play a playlist in Arm A, HOME, screen off, 30 min,
`adb logcat -s OpenSpotify` for gaps and `service:` lines; in Chrome on the same device repeat the same (H0).

## H6: network identity vs Chrome

Status: **PARTIAL** (headers identical except `X-Requested-With`; two JS-visible differences).

Control: real Chrome 124.0.6367.219 (same version as the WebView) driven over `chrome_devtools_remote`; both ran the
same checks from an `open.spotify.com` page. The "no Chrome on this image" assumption was wrong (see metadata).

Fetch headers (`fetch("https://httpbin.org/headers")` from the page; this shows fetch headers only; httpbin is outside
the allow-list so it cannot be loaded as a main-frame URL via `osp_url`):

```
$ diff chrome.txt shell.txt
< X-Amzn-Trace-Id = Root=1-6ac97257-...              (per-request id, ignore)
> X-Amzn-Trace-Id = Root=1-6ac97265-...
> X-Requested-With = dev.openspotify.shell
```
Common to both: `Sec-Ch-Ua = "Chromium";v="124", "Google Chrome";v="124", "Not-A.Brand";v="99"`, `Sec-Ch-Ua-Mobile = ?1`,
`Sec-Ch-Ua-Platform = "Android"`, identical `User-Agent`, `Accept-Language: en-US,en;q=0.9`, `Accept-Encoding: gzip, deflate, br, zstd`.

Main-frame navigation headers, including the high-entropy hints (CDP `Page.navigate` to
`httpbin.org/response-headers?Accept-CH=Sec-CH-UA-Full-Version-List,Sec-CH-UA-Platform-Version,Sec-CH-UA-Model,Sec-CH-UA-Arch,Sec-CH-UA-Bitness,Sec-CH-UA-Form-Factors,Sec-CH-UA-Full-Version`,
then `Page.navigate` to `httpbin.org/headers`, in Chrome and in the shell):

```
$ diff chrome-nav.txt shell-nav.txt
> X-Requested-With = dev.openspotify.shell           (plus the trace id)
```
Identical lines in both include `Sec-Ch-Ua-Full-Version-List = "Chromium";v="124.0.6367.219", "Google Chrome";v="124.0.6367.219", "Not-A.Brand";v="99.0.0.0"`,
`Sec-Ch-Ua-Platform-Version = "15.0.0"`, `Sec-Ch-Ua-Model = "sdk_gphone64_x86_64"`, `Sec-Ch-Ua-Form-Factors = "Mobile"`,
`Sec-Ch-Ua-Arch = ""`, `Sec-Ch-Ua-Bitness = ""`, `Sec-Fetch-*`, `Upgrade-Insecure-Requests`, `Accept`, `Priority`.

`navigator.userAgentData` (JS, high-entropy values) is identical field by field in Chrome and the shell
(brands, `fullVersionList`, `uaFullVersion`, `platform "Android"`, `platformVersion "15.0.0"`, `model`, `formFactors ["Mobile"]`, `mobile true`).
The shell's `formFactors` is `["Mobile"]` in Arm A, which equals Chrome Mobile. For Arm B it stays `"Mobile"` where
Chrome "Request desktop site" would report `"Desktop"` (not settable through public API; no desktop-mode Chrome
comparison was run).

JS-visible differences found (not patched, by design: no spoofing beyond UA string and UA-CH):

| Property | Chrome 124 | Shell (WebView 124) |
|---|---|---|
| `navigator.platform` | `"Linux armv81"` | `"Linux x86_64"` (real uname) |
| `navigator.languages` | `["en-US","en"]` | `["en-US"]` (the `Accept-Language` header is the same: `en-US,en;q=0.9`) |
| `X-Requested-With` | absent | `dev.openspotify.shell` (irremovable, documented) |

`navigator.platform` on a real ARM phone will differ in the same way (WebView reports the kernel machine string,
Chrome Android reports `Linux armv81`/`armv8l`); this is a real, observable difference worth stating in the thesis.

## H7: Activity destruction and renderer crash

Status: **PARTIAL** (all mechanics PASS; "still logged in after rebuild" NOT TESTED, anonymous).

Rotation (`adb shell settings put system accelerometer_rotation 0; ... user_rotation 1`, then back to 0), with the
synthetic session and the test audio playing:

```
before: performance.timeOrigin 1791586965183
landscape: {"origin":1791586965183,"iw":863,"ih":360,"paused":false}      -> same timeOrigin, no reload
logcat: no "webview: created"/"rebuilt" line; PlaybackService isForeground=true; media_session state=PLAYING
KEYCODE_MEDIA_NEXT after rotating -> __h4calls ["{\"action\":\"nexttrack\"}"]
back to portrait: {"origin":1791586965183,"iw":412}
```
PASS.

Renderer kill. `adb shell kill <sandboxed pid>` and `run-as ... kill` are refused on this non-root image
(`Operation not permitted`), so the renderer was crashed over CDP (`Page.crash`, which is a real renderer crash from
the embedder's view):

```
renderer gone: crashed=true priority=2
webview: created
webview: rebuilt, reloading https://open.spotify.com/
nav: started https://open.spotify.com/        bridge: new document ..., state cleared
bridge: {"t":"install","session":"polyfill",...}      nav: finished https://open.spotify.com/
app pid unchanged (9543), no FATAL/AndroidRuntime; new sandboxed process ...SandboxedProcessService0:1
diag after rebuild: "bridgeInstalled":true,"session":"polyfill","elements":0
60 s later: service: nothing loaded for 60s, stopping (title=null declared=none element=null)
```
PASS (page rebuilt and reloaded, app did not crash, bridge reinstalled). The session ended 60 s after the crash
because the rebuilt page had no track, which is the intended MAJOR-2 behaviour. Login persistence after a rebuild:
NOT TESTED (anonymous; the cookie store is not touched by `rebuild()`).

Recents swipe (`KEYCODE_APP_SWITCH`, then `input swipe 540 1100 540 150 250` on the task card):

- While playing: `service: task removed while playing, staying alive`; same app pid, `isForeground=true`, media_session `PLAYING`,
  the page's audio element still `paused:false`, no `webview:` line; `KEYCODE_MEDIA_NEXT` still reached the handler
  (`bridge: invoke nexttrack -> "handler"`) with no Activity. PASS per the design.
- While paused (test element paused, Activity reopened first): `service: task removed while paused, shutting down`,
  `webview: destroyed`, `service: destroyed`; no media session and no PlaybackService remained. The process stayed
  alive as an ordinary cached process (pid unchanged), which is expected. PASS per the design.

## H8: UI adaptation (DESIGN §2.9, v0.4)

Status: **PASS on the emulator** (anonymous, WebView 124.0.6367.219, Spotify bundle `mobile-web-player.fe7b9205.js`).
Physical device: install and script injection verified from logs and DevTools; **no screenshot** (see the end).

Build: `./gradlew assembleDebug testDebugUnitTest lint` BUILD SUCCESSFUL (46 JVM tests, 0 failures, lint warnings
only, no errors); `node --test tools/test/*.test.mjs` 47/47 (the 20 media-bridge tests plus 27 for UI adaptation).

### Step 1: the DOM that was found

Which popup. Navigating between pages (home, album, artist, playlist, search) by clicking links did **not** show an
install popup anonymously: the mobile bundle reloads the document on each link, and nothing appeared. Reading the
bundle (`mobile-web-player.fe7b9205.js`) showed the popover component (tippy.js, "action-trigger") and tapping the
bottom-navigation **Your Library** tab as an anonymous user triggers it. It is non-modal.

```
<div data-tippy-root id="tippy-1" style="z-index: 9999; position: absolute; ...">
  <div id="context-menu" data-testid="context-menu" data-placement="top">
    <div class="E3aHzyli7TiD5edkDw0z Uu3jCgAVnLKpoGejbJYK encore-dark-theme encore-layout-themes">
      <div class="G74a1f_k0LzRh8ZFHzWk">
        <div class="e-10860-text encore-text-body-medium-bold" data-encore-id="text">Enjoy Your Library</div>
        <div class="e-10860-text encore-text-body-small" data-encore-id="text">See your saved songs, podcasts, artists, and playlists in the Spotify App.</div>
        <div class="zSWtRRtthbsBl2z6BKEx">
          <button data-encore-id="buttonTertiary" class="...">Not now</button>
          <button data-encore-id="buttonPrimary" class="..."><span class="...">Get App</span></button>
        </div>
      </div>
      <div data-popper-arrow="" class="OUv88xKmCEuY0nBkd1YT" ...></div>
    </div>
  </div>
</div>
```
- Appended as a child of `<body>` by tippy. `id` and `data-testid` are `context-menu` (the generic popover id, so it is
  not specific to this popup), no `role="dialog"`, no `aria-modal`, no `aria-label`. The page-level `role=dialog`
  elements are all something else: the OneTrust consent dialogs and the hidden "Choose a language" dialog.
- Dismiss button: the first button, `data-encore-id="buttonTertiary"`, text "Not now" (`action-trigger.button.not-now`).
  It has **no testid and no aria-label**. The "Get App" button is `buttonPrimary`, opens a `spotify.link` URL via
  `window.open`, and the popover has no `<a href>`.
- No backdrop element. `getComputedStyle(document.body).overflow` and `documentElement` are `visible` while it is
  shown, no inline `style` on body/html. Scrolling keeps working. The page itself closes it on outside click.
- Related popovers in the same bundle (same component, not triggered in the run): "Listen to the full track / Get the
  free app" (`mwp.nav.get.app`), "Download episodes / Available only in the app" (`activation-trigger-mme-hook`
  testid: `${id}-hook`). All end in a tertiary "Not now" and a primary "Get App".
- Screenshot: `docs/research/screenshots/nag-popup.png` (adaptation OFF; the OneTrust consent banner covers the bottom
  of the screen, so for this shot the emulator's cookie banner was answered with "Reject all").
- A **different** dialog was also seen and deliberately left out of scope: tapping "add to library" as an anonymous user
  shows `[data-testid="upsell-dialog"]` ("Start listening with a free Spotify account", "Sign up free",
  `upsell-download-app-button` "Download app", "Log in", a "Close" button without testid). It is the logged-out
  user's functional sign-up path.

Open App button (`entity-view-top-bar` > div > button):
```
<button data-testid="download-button" data-encore-id="buttonPrimary" class="encore-text-body-small-bold e-10860-legacy-button-primary e-10860-legacy-button">
  <span class="... e-10860-button-primary__inner encore-inverted-light-set ...">Open App</span></button>
```
Premium banner (the banner has its own `aria-label="Close"` button, not used by the rules):
```
<div data-testid="home-banner"><div class="dQr6UEoxMhIjxX_bjErq encore-inverted-dark-set" data-testid="premium-banner">
  <div ...>[svg title id="spotify-premium-logo-icon"]<button aria-label="Close" data-encore-id="buttonTertiary">..</button></div>
  <span ...>Listen without limits. Try 1 month of Premium Individual for €0.</span> ... buttons "Try 1 month for €0", "View all plans" ... </div></div>
```
`home-banner` is rendered empty first and the banner is filled in a second step (the observer sees the nodes added in
two batches). `entity-view-top-bar` is also the header on album/artist pages.

Logged-in physical device (DOM read only; page loaded in the shell, navigation only): home had **no**
`download-button`, no `premium-banner` (an empty `home-banner`), a `settings-container`/`gears-button` header and the
same two bundle files. Navigating album -> artist -> album -> search by link produced no popup there either.
Locale labels: 70 of 74 Spotify locale files fetched (`generated-locales/mobile-web-player/<lang>.<hash>.json`) for
the rule's label list; `ar`, `ar-EG`, `th`, `vi` were not served.

### Step 3: results with adaptation ON (emulator)

```
adapt: script registered for [https://open.spotify.com]
adapt: installed rules=[open-app-button, premium-banner, get-app-popover, get-app-dialog] rejected=0
adapt: rule=open-app-button action=hidden bundle=mobile-web-player.fe7b9205.js sel=0
adapt: rule=premium-banner action=hidden bundle=mobile-web-player.fe7b9205.js sel=0
adapt: rule=get-app-popover action=dismissed bundle=mobile-web-player.fe7b9205.js sel=1      <- real touch tap on "Your Library"
adapt: survival bundle=mobile-web-player.fe7b9205.js matched=[get-app-popover, open-app-button, premium-banner]
```
- Home (`adapt-on.png`, cookie banner answered): no Open App button, no Premium banner, no gap; the page title
  row is directly under the header. `getComputedStyle`: `download-button` `display: none`, `premium-banner` `none`,
  `home-banner` `none` (the container rule). `__osAdapt.stats()`: `open-app-button` matches 1 (selector 0),
  `premium-banner` matches 2 (selectors 0 and 1), `neverMatched: ["get-app-popover","get-app-dialog"]` before the tap
  (fallback `get-app-dialog` never matched in any run, as expected), `badCss: []`, `rejectedRules: []`.
- Navigation popup: tapping the Library tab with a real `adb shell input tap` -> `adapt: ... dismissed`; `[data-tippy-root]`
  count 0 within 100 ms; tapped again twice via JS: `acted` 3 (three popover instances, one click each).
  `getComputedStyle(document.body).overflow` = `visible` (html: `visible`), no inline style on body/html.
- Still scrollable and clickable: `adb shell input swipe` -> `scrollY` 477; tap on the Search tab -> `location.pathname`
  `/search`.
- Bridge unchanged: Diagnostics (`--ez osp_diag true`) with adaptation ON: `"bridgeInstalled":true`,
  `"session":"polyfill"`, `"widevine":true`, plus the new `"adapt":{"bundle":"mobile-web-player.fe7b9205.js",...}`.
- Persisted survival record (`shared_prefs/open_spotify.xml`, key `adapt_survival`):
  `{"mobile-web-player.fe7b9205.js":{"open-app-button":{"hidden":3,...},"premium-banner":{"hidden":3,...},"get-app-popover":{"dismissed":4,...}}}`.
  A new Spotify deploy adds another bundle key; rules missing there are the ones that did not survive.

### Results with adaptation OFF (emulator)

Debug menu -> "UI adaptation: on (switch off)" (real tap): `adapt: off, reloading`, `ui_adaptation=false` in the prefs, no
`adapt:` lines after the reload, `typeof __osAdapt === "undefined"`, `document.adoptedStyleSheets.length === 0`,
`download-button` `display: block`, `premium-banner` `flex`. `adapt-off.png`: Open App button and the Premium banner are
back. The Library popover then stays until dismissed by the user (`nag-popup.png`). Re-enabling from the menu logged
`adapt: script registered ... adapt: on, reloading` and hid both again (`adapt-on.png`).

### Physical device (`dG140829DFU5`, logged in)

`adb -s dG140829DFU5 install -r` (keeps the login); launched `MainActivity` (WebView `com.android.webview` 124.0.6367.219):
```
adapt: script registered for [https://open.spotify.com]
adapt: installed rules=[open-app-button, premium-banner, get-app-popover, get-app-dialog] rejected=0
```
`__osAdapt.stats()` read over DevTools: bundle `mobile-web-player.fe7b9205.js`, `rejectedRules: []`, `badCss: []`, no rule
matched on the loaded `/search` page, which is expected for this logged-in account (no Open App button, no
banner on that page, no popup triggered). No `hidden`/`dismissed` line because nothing matched. **No screenshot:** the phone was
asleep behind its lock screen (`mWakefulness=Asleep`, keyguard showing); `screencap` returns a black frame, the file was
discarded, and the screen was not woken or unlocked because only install, launch and log reading were permitted.
Nothing else on the device was touched (no playback, likes, follows or settings).

### Findings worth keeping for the thesis

- The "popup while navigating" is not a dialog: it is a tippy popover with unlabelled buttons, which is why the rule
  needs a label gate (the only text matching in the feature) and why `data-testid="context-menu"` alone must not be used.
- Mobile-bundle navigation reloads the document, so the CSS adopted at document start (no flash of the nag, no
  dependence on the DOM existing) is the right mechanism for hide rules.
- The consent dialog (OneTrust) overlays the bottom of the page, can hide popovers behind it, and is never touched.

## H9 (self-update)

Spec: `docs/UPDATER.md` §2-§5; code in `app/src/main/java/dev/openfy/update/`. Status: **PASS on the emulator** (happy
path through `PackageInstaller`, checksum from the API `digest` and from a `.sha256` sidecar, and the refusal paths). Not
run here: the real GitHub hosts and the release-signed pipeline (§5.2 steps 1-7 against a scratch repo), silent update,
installing while playing, airplane mode with a real radio off.

Build and tests: `./gradlew assembleDebug testDebugUnitTest lint lintRelease testReleaseUnitTest` BUILD SUCCESSFUL, 113
JVM tests (67 in `dev.openfy.update`: version vectors, parser fixture `app/src/test/resources/update/release-latest.json`
incl. missing asset / missing digest / two APKs / draft / pre-release / tag mismatch / 404, host allow-list incl.
`github.com.evil.com`, `api.github.com:8443`, `http://`, userinfo tricks, redirect policy, signer set equality,
downgrade and same-version refusal, size cap, streaming SHA-256, `UpdateDecision`, and "release config rejects http");
`node --test tools/test/*.test.mjs` 47/47. The release `BuildConfig.UPDATER_TEST_BASE` is `""` and the release APK
contains no `network_security_config`.

Setup (emulator `emulator-5554`, Android 15, Google Play image; the physical device was not touched):

- Debug-only test override: `-PupdaterTestBase=http://127.0.0.1:8765` fills `BuildConfig.UPDATER_TEST_BASE` (debug build
  type only) and `app/src/debug` adds a network security config allowing cleartext to `127.0.0.1`. `UpdateHosts.forBuild`
  ignores the value unless `BuildConfig.DEBUG`, and only accepts exactly `http://127.0.0.1:<port>`.
- Versions without touching the versioning code: a temporary local edit of `versionCode`/`versionName` in
  `app/build.gradle.kts`, reverted after each build. Builds: `0.9.0`/90099, `0.9.1`/90199, `0.9.2`/90299, and `0.9.2`/80000
  (low code). Same debug keystore, except one copy re-signed with a throwaway keystore (`/tmp/e2e/other.jks`, SHA-256
  `14f692d0...` against the debug key `89352919...`) with `apksigner`.
- Fake GitHub: `python3 -m http.server 8765 --directory /tmp/e2e/srv` serving `repos/p4u/openfy/releases/latest` (static
  JSON, asset `browser_download_url` on the same origin) and the APK, `adb reverse tcp:8765 tcp:8765`.
- UI driven with `adb shell input tap` using `uiautomator dump` to find buttons.

| # | Scenario | Result |
|---|---|---|
| 1 | v0.9.0 installed (`adb install`), debug menu, **Check for updates**, served `v0.9.1` with `digest` | Dialog "Update available: 0.9.1", notes, "Size: 3.9 MB", debug footer; buttons Skip this version / Later / Install (`screenshots/update-dialog.png`) |
| 2 | Install without "install unknown apps" granted | Explanation dialog, "Open settings", system page "Install unknown apps" for openfy debug, "Allow from this source", Back: download started on return with no further prompt |
| 3 | After download | System sheet "Do you want to update this app?" (`screenshots/update-confirm.png`), then (this image only) a Google Play Protect "App scan recommended" page; "Install without scanning". `dumpsys package`: `versionCode=90199 versionName=0.9.1-debug installerPackageName=dev.openfy.debug`. Log: `update: session ... committed`, `install status=-1`, later `status=0 INSTALL_SUCCEEDED`; `cache/updates` empty after restart; prefs `last_installed_code=90199` |
| 4 | Second update `0.9.2`, **no** `digest`, `.sha256` sidecar only | Sidecar fetched and matched, update installed (`versionCode=90299`). The confirm sheet was shown again even though openfy is now the installer of record (see findings) |
| 5 | Wrong checksum (`digest` = 64 zeros) | Dialog "The download is corrupt or was tampered with (SHA-256 mismatch).", log `sha256 mismatch expected=0000... actual=4f62...`, file deleted, no session created |
| 6 | Downgrade: tag `v0.9.2` but the APK has `versionCode` 80000 (installed 90199) | Dialog "The downloaded APK (versionCode 80000) is not newer than the installed one (90199). Downgrades are refused.", no session |
| 7 | APK signed with a different key (`0.9.2`/90299, throwaway keystore) | Dialog "The downloaded APK is signed with a different key than the installed app. Uninstall and install the release APK from GitHub instead.", no session |
| 8 | Release tag older than installed (`v0.9.0` vs 0.9.1) | Toast "You have the latest version (0.9.1-debug)." (log `update: up to date`) |
| 9 | Server unreachable (`adb reverse --remove`) | Dialog "Update check failed: Failed to connect to /127.0.0.1:8765", log `update: check failed` |

Across 5-7 the log contains no `session ... committed` line, so the refusals happen before any `PackageInstaller`
session exists. All error lines are logged as `update: ...` under the `Openfy` tag.

Re-verified after review fixes (docs/reviews/updater-review-fable.md; emulator only, same fake GitHub, now with
`-PversionName=...` debug builds `0.9.0`/`0.9.1`/`0.9.2` and asset URLs `http://127.0.0.1:8765/p4u/openfy/releases/download/<tag>/<name>`,
which satisfy the new repo pinning):

- Happy path 0.9.0 -> 0.9.1: offer, permission explanation, Settings round trip, download, session re-hash, confirm sheet,
  Play Protect page ("More details", then the lower "Install without scanning" line); `versionCode=90199 versionName=0.9.1
  installerPackageName=dev.openfy.debug`. The new process logs `install status=0` and ignores it (no active session there);
  `cache/updates` gone, `last_installed_code=90199`.
- MAJOR-2: with `settings put system font_scale 1.3` while Settings was open, `wm_relaunch_activity` recreated
  `MainActivity`; granting the permission and pressing Back still started the download and reached the confirm sheet.
- MAJOR-3: with the sheet up, Home then back relaunches the sheet (state stays `NeedsUserAction`); Cancel gives
  `status=3` -> `Idle`, and a manual check then offers the update again. For the break-out itself a throwaway build that
  skips `startActivity(confirm)` (not committed) was used, since a real sheet relaunches on every start: the manual
  check logged `manual check abandons the pending install`, abandoned the session (its late `status=3` was ignored by
  the session-id check) and offered 0.9.2 again.
- Pinning: a release whose asset URL points at `/evil/other/releases/download/...` shows "Update check failed: APK asset
  is hosted outside the release."
- Side effect noted: after an Activity recreation while the sheet is up, the new Activity launches the sheet a second
  time (two identical sheets, harmless; Cancel twice).

Findings worth keeping:

- The settings round trip works with `registerForActivityResult`: after Back the install continues from the same
  `UpdateManager` call, and the status receiver delivered `STATUS_PENDING_USER_ACTION`, which `MainActivity` launched
  from the foreground.
- `STATUS_SUCCESS` is delivered to the **new** process (pid changed), as the spec expects: the old one is replaced
  during the install. Only the new process logs `install status=0`.
- `USER_ACTION_NOT_REQUIRED` did not suppress the confirm sheet on the second update on this emulator image
  (installer of record was already `dev.openfy.debug`). Per the `SessionParams` reference the silent path has further
  conditions (update ownership, target SDK of the installed app); the code always handles `STATUS_PENDING_USER_ACTION`,
  so this only changes how often the user taps. Worth re-measuring on a release build and a physical device.
- Google Play Protect's scan prompt is a property of this Google Play emulator image, not of the app.
- (Superseded: debug builds no longer have a `-debug` suffix, and the suffix handling was removed.) Debug versionName carried AGP's `-debug` suffix: `Version.parseInstalled` strips it, and the archive versionName
  comparison strips it in debug builds only. A name outside the grammar (the old `0.1.0-poc`) counts as `0.0.0-dev`.

## H0: Chrome control (NOT TESTED, needs account)

Steps for a real phone:

1. In Chrome (not the app), open `https://open.spotify.com`, log in, play a playlist. Do this once in the default mobile site and once with "Desktop site" checked in the Chrome menu.
2. Press Home, turn the screen off, leave it 30 minutes.
3. Record, for each mode: did playback continue the whole time, did it stop and when, was there a media notification, and whether the page auto-paused. `adb logcat -s chromium` and `dumpsys media_session` snapshots at 0, 10, 30 min help.
4. Pass criterion (DESIGN §4): playback continues for 30 min in at least one mode. This decides whether a visibility shim could ever be needed, and gives the baseline for H5.

## What a human still has to run (summary)

H0, H2 (above), H3 playback, H4 with real Spotify metadata in both arms plus headset and 10-minute-pause resume,
H5 for 30 minutes with track changes, and the login-persistence part of H7. Everything else listed as PASS above was
executed on the emulator in this run.
