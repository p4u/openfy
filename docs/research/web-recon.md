# Web Recon: open.spotify.com as an Android WebView target

Date of investigation: 2026-10-09. Method: curl (headers, HTML, JS bundles), grep over the live bundles, and web search/read of Spotify support pages and prior-art reports.

**Limitation:** The Playwright MCP browser failed to start (`Chromium distribution 'chrome' is not found at /opt/google/chrome/chrome`). No runtime rendering, DOM snapshot, or screenshots were captured. Screenshots directory is empty. Anything that needs a live rendered DOM or a real WebView is marked UNVERIFIED. Nothing here was logged in or bypassed.

---

## 1. Anonymous page load behaviour

Requests to `https://open.spotify.com/` via curl (HTTP/2, no redirects followed in any case):

| UA | Status | Redirect | HTML size | Notes |
|---|---|---|---|---|
| Desktop Chrome 129 (Linux) | 200 | none | 166,371 B | Serves desktop web player shell |
| Android Chrome 129 (Pixel 8, Mobile) | 200 | none | 309,267 B | Different document (viewport `width=device-width, initial-scale=1`) |
| Android WebView (`; wv`, `Version/4.0`) | 200 | none | 309,331 B | Same mobile document as Chrome Mobile (near-identical size) |

- No `Location` header in any case, so no UA-based redirect to an app-store or "open in app" page at the HTTP level.
- Server-side the mobile and WebView UAs get the same document (within 64 bytes), so there is no visible difference between b and c at the HTML level. Whether the JS then behaves differently (for example, forcing an app-install interstitial) is UNVERIFIED without a runtime test.
- The mobile document contains Smart App Banner meta tags (`al:android:url content="spotify://"`, package `com.spotify.music`). These are metadata only; they don't block the page.
- Desktop vs mobile layout: the HTML is a JS-rendered shell (`<title>Spotify – Web Player`). No `data-testid` attributes appear in the server HTML, so the DOM is produced client-side. Actual layout (desktop/mobile) is UNVERIFIED at runtime.
- Community reports (Reddit, Sep/Oct 2025) say Spotify "pushes you towards the app and makes the browser version nearly unusable" on Android, and advise enabling "Desktop site" mode. This is anecdotal and UNVERIFIED for the current build.

Screenshots: NOT CAPTURED (browser unavailable). Planned filenames in `docs/research/screenshots/` once a browser is available: `desktop-chrome-anon.png`, `android-chrome-anon.png`, `android-webview-anon.png`.

## 2. DOM structure (stable selectors)

Source: testid strings extracted from the JS bundles (`web-player.dbd3aa01.js`, `vendor~web-player.9d1c96f6.js`, `encore~web-player.75034fff.js`). 102 unique `data-testid` values. These are the string values that the code passes in, not a runtime snapshot, so they show what exists, not what is visible on a given page.

Relevant to the requested list:

| Purpose | testid(s) | Where (per code context) |
|---|---|---|
| Login button | `login-button`, `login-trigger`, `signup-button` | Top bar, unauthenticated state |
| Logged-out user widget | `unauth-user-widget-link` | Top bar |
| Logged-in user menu | `user-widget-link`, `user-widget-menu`, `user-widget-dropdown-logout`, `user-icon`, `username-first-letter` | Top bar |
| Global nav | `global-nav-bar`, `home-button`, `top-bar-back-button`, `top-bar-forward-button` | Top bar |
| Search | `search-icon`, `search-input`, `clear-button` | Top bar / search view |
| Now-playing bar | `now-playing-bar` | Bottom bar (name confirmed in bundle, not runtime) |
| Now-playing view (full screen) | `now-playing-view`, `video-player-cinema` | Expanded NPV |
| Track title / artist (NPV and rows) | `context-item-info-title`, `context-item-info-artist`, `context-item-info-subtitles`, `npv-artist-link`, `npv-artist-bio-button` | Cards/NPV |
| Cover art | `card-image-fallback`, `card-image-age-restricted`, `video-card-image-masked` | Cards; NPV cover selector not in the list (UNVERIFIED) |
| Play / pause | `play-button` | Cards, context views |
| Shuffle / repeat | `shuffle`, `shuffle-toggle-upsell`, `repeat`, `repeat-rewind`, `repeat-count-high` | Now-playing controls |
| Progress bar | `progress-bar` (encore bundle), `progress-circle` | Playback controls |
| Volume | `volume`, `volumechange` (vendor bundle) | Playback controls |
| Next / prev | Not found as testid. Covered via `mediaSession` action handlers (`nexttrack`, `previoustrack`) and possibly a `control-button-*` pattern (UNVERIFIED) | |
| Left sidebar / library | Not found in the testid list (UNVERIFIED) | |
| Main view | `root`, `infinite-scroll-list`, `carousel-scroller`, `carousel-next-button`, `carousel-previous-button`, `top-sentinel`, `bottom-sentinel` | Main content |
| Track rows | `tracklist-row`, `tracklist-row-placeholder`, `track`, `track-icon`, `internal-track-link` | Tracklists |
| Context menu | `context-menu`, `more-button`, `popover` | Track/album menus |
| Error / loading | `error-page`, `loading-page`, `loading-icon` | Global |

Caveats:
- Testids are a snapshot of one build (`web-player.dbd3aa01.js`). They change with deploys. Hashed bundle names change on every release, so anything the shell relies on needs to be checked again per release.
- The native shell should avoid depending on exact DOM for playback. Use the browser's media element and `navigator.mediaSession` (see §3) rather than clicking testids.
- Sidebar, next/prev buttons, and the progress bar's testid need runtime confirmation (UNVERIFIED).

## 3. Media / DRM

**EME / Widevine:**
- The bundle contains the key-system constants `com.widevine.alpha`, `com.microsoft.playready`, `com.microsoft.playready.hardware`, `com.apple.fps.1_0` (vendor bundle). So the player supports multiple DRMs and selects one at runtime.
- The string `requestMediaKeySystemAccess` appears twice in the vendor bundle (the call site is the EME path). The check at runtime (which system is selected, and whether it's Widevine on Android) is UNVERIFIED without running the page.
- Spotify's support page (https://support.spotify.com/us/article/web-player-help) states: if you see "Playback of protected content is not enabled", "you need to enable the Widevine plugin or download a media pack". The Chrome instructions say to set "Protected content IDs" to Allow for open.spotify.com. Firefox asks you to "Enable DRM". This confirms the web player requires Widevine.

**Media Session:**
- Confirmed. Both bundles use `navigator.mediaSession`:
  - `MediaMetadata` (`name`, `artists`, `album`, `artwork`) is set from track data.
  - `playbackState` and `setPositionState` are updated.
  - `setActionHandler` is registered for `nexttrack` / `previoustrack` (seek-forward/backward too).
  - A guard checks `"mediaSession" in navigator && "MediaMetadata" in window` and falls back if missing.
- This means lock-screen and notification controls come from the web page itself, but only if the WebView's media session is wired to the Android media notification (see Risks).

## 4. Login flow

- The bundle contains `accounts.spotify.com` and paths: `/login`, `/login_account`, `/login-choice`, `/login-methods`, `/login-methods-v2`, `/login-methods-v2-email`, `/login-methods-v2-passkeys`, `/login-methods-v2-password`, `/login-different-device`, `/login-current-device`.
- The string `"facebook"` appears in the bundles, so a Facebook login method is listed. Google and Apple methods were not found as explicit string literals in the bundle (UNVERIFIED; may be built dynamically).
- Passkeys are referenced (`login-methods-v2-passkeys`). WebAuthn in an Android WebView is a risk (see §5).
- Known issues (web search results):
  - **Google OAuth in WebViews:** Google blocks OAuth in embedded browsers and returns `403 disallowed_useragent`. Auth0 and Firebase developers report there is "no way to get around it" for WebView-based Google sign-in (Auth0 community 2022, Reddit 2023). Spotify's own forum reports the same 403 when signing in with Google from a browser that looks like a WebView (community.spotify.com, 2023).
  - **Facebook in WebViews:** not verified in this session.
  - Implication: if the user's Spotify account is created via Google, the native shell may be unable to complete login in a WebView. Email/password login is the most likely path to work. UNVERIFIED for Spotify at runtime.
- The login button testid is `login-button` (also `login-trigger`).

## 5. Prior art and known problems

**Prior art found:**
- `lyssadev/Spotilol` (GitHub, indexed Oct 8, 2026): "an Android app that wraps Spotify's web player with built-in adblocking — no root, no shady mods, just your Spotify account on a slick WebView." Closest existing project. Note the adblock feature: it is out of scope for this research (it modifies the content the user sees, and may conflict with Spotify's terms). Not reviewed in depth; UNVERIFIED how it handles DRM and login.
- Reddit r/SpotifyLatestModAPK (2025): a guide for "Spotify web working in your android browser - all features". Suggests using desktop mode. Not a wrapper.
- Stack Overflow / Spotify community (2020): embedded Spotify in Android WebView needed `onPermissionRequest()` in `WebChromeClient` to grant protected-media permission.
- Spotify community (2022): WebView-based Spotify playback stopped working when permission requests got stuck on older WebView versions.
- Nothing found for F-Droid apps or Nativefier/Electron wrappers of open.spotify.com specifically in this session (not exhaustively searched).

**Widevine in Android WebView:**
- Requires `WebChromeClient.onPermissionRequest` to grant `PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID` (confirmed in a 2023 developer write-up and the Stack Overflow thread). Without it, EME fails silently or with "Playback of protected content is not enabled".
- Android WebView uses the platform's Widevine CDM. Its level (L1 vs L3) depends on the device. L3 devices may be limited to lower-quality video, and some services refuse L3 playback entirely. Spotify audio is less demanding, but this has not been verified for Spotify (UNVERIFIED).

**Background audio in WebView:**
- A plain WebView pauses media when the activity is backgrounded or the screen turns off, unless a foreground service with a media notification keeps the process alive and routes `MediaSession` to the system. Needs a dedicated implementation (UNVERIFIED for Spotify's player specifically; it may also pause because of its own visibility checks).

**Mobile browser playback:**
- Spotify's web player does play in mobile browsers (Reddit reports, Oct 2025, and the mobile document is served to Chrome Android and WebView UAs). Spotify's support pages do not say mobile playback is blocked. Reports say the mobile version nudges you towards the app. Current status in 2026: UNVERIFIED.

## 6. Response headers relevant to embedding/injection

From `curl -sI` on the desktop UA:

- `content-security-policy`: `script-src 'self' 'unsafe-eval' blob: ... 'sha256-...'` with a long allowlist (Google, Facebook, TikTok, Hotjar, Heap, etc.). Notable: `'unsafe-eval'` is allowed. No `default-src` restriction is visible in the output.
- `frame-ancestors 'self' https://adgen-dev.spotify.com/... https://app.smartly.io/*;`. Only Spotify ad tools may embed the page in an iframe. This is the anti-framing control. It does NOT restrict top-level navigation in a WebView.
- **No `X-Frame-Options` header** was returned. `frame-ancestors` takes precedence in modern browsers.
- `strict-transport-security: max-age=31536000`.
- `set-cookie`: `sp_t` (SameSite=None; Secure), `sp_landing`, `sp_new`. Cookies must be persisted in the WebView's cookie jar (`CookieManager` with third-party cookies, since SameSite=None).
- `server: envoy`, `via: ... varnish`.

Implications: the WebView can load the page at top level. Injecting JS is not blocked by the CSP for the app's own code, but injected scripts that are not on the allowlist are blocked from running as `<script src>`. Use `evaluateJavascript` / `addJavascriptInterface` rather than loading external scripts from the shell.

---

## Key risks for the Android WebView approach

1. **Google login is blocked in WebViews** (403 `disallowed_useragent`). Account creation or login via Google will likely fail. Email/password is the realistic path. Verify with a real WebView.
2. **Widevine dependency:** playback needs `onPermissionRequest` granting `RESOURCE_PROTECTED_MEDIA_ID` and a working Widevine CDM. Devices with L3-only CDM may get reduced playback or failures. Test on a real device.
3. **Background playback:** a plain WebView will pause when backgrounded. Needs a foreground media service plus `MediaSession` integration to keep audio and lock-screen controls. Not verified.
4. **Mobile UA behaviour may nudge to the app:** the server serves a mobile document to Android UAs, and reports say the web player pushes users to the app. The WebView UA received the same document as Chrome Mobile, so a plain UA string change is unlikely to avoid it. Whether the JS then shows an app-install overlay is UNVERIFIED.
5. **Fragile DOM:** testids change with each bundle deploy (hashed filenames change). Do not script UI clicks as a core feature; rely on `mediaSession` and the media element where possible.
6. **Terms of service / adblocking:** prior art (Spotilol) advertises ad blocking. Ad-blocking or DOM injection to remove ads or modify playback is against the spirit of the research scope and likely against Spotify's terms. Keep the shell a pure browser wrapper with no content modification.
7. **Third-party cookies and SameSite=None:** session cookies are `SameSite=None; Secure`. The WebView needs third-party cookies enabled for login to work across `accounts.spotify.com` and `open.spotify.com`.
8. **Unverified runtime items** (need a real browser or WebView): rendered layout on each UA, the presence and location of the sidebar and next/prev controls, whether `requestMediaKeySystemAccess` selects Widevine on Android, the current status of mobile playback, and the exact login options offered.
