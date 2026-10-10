# In-app updater: security review

Scope: `app/src/main/java/dev/openfy/update/` (16 files), the wiring in `MainActivity.kt` / `OpenfyApp.kt`, the
manifests (`app/src/main`, `app/src/debug`), `app/build.gradle.kts`, the tests in `app/src/test/java/dev/openfy/update/`
and the H9 section of `docs/poc-results.md`, as merged on `main` at `f6d93a5`. Spec: `docs/UPDATER.md` §2-§5; release
assumptions: `docs/reviews/release-review-fable.md`. Reviewer: Fable. Nothing was edited.

Verdict: **no BLOCKER**. The authentication chain is sound: HTTPS to an exact allow-list checked on every hop, SHA-256
from the API host over bytes from the asset host, package/versionCode/versionName read from the archive itself, signer
set equality, and Android's own `INSTALL_FAILED_UPDATE_INCOMPATIBLE` behind all of it. Three MAJORs: one
defense-in-depth gap (the bytes handed to `PackageInstaller` are not the bytes that were hashed) and two UX dead ends
that leave the user with no feedback or no way to continue. The rest is MINOR.

Build check performed: `./gradlew assembleRelease -PversionName=0.1.0 -PrequireSigning=true` succeeded;
`BuildConfig` (release) has `DEBUG=false`, `UPDATER_TEST_BASE=""`; the merged release manifest has no
`networkSecurityConfig`; the APK contains no `res/xml/network_security_config`; `InstallStatusReceiver` is
`exported=false`; `apksigner verify`: v2+v3 only, one signer, cert SHA-256 `1db9ee4a...b263` (matches the release review).

---

## MAJOR

### MAJOR-1: the session receives bytes that were never hashed (verify/install TOCTOU within the sandbox)

- `app/src/main/java/dev/openfy/update/UpdateHttp.kt:84-97`: SHA-256 is computed over the **network stream** as it is
  written to `cacheDir/updates/openfy-<v>.apk`.
- `app/src/main/java/dev/openfy/update/UpdateManager.kt:166-177`: hash check, then `preInstallRefusal` (parses the
  file from disk via `getPackageArchiveInfo`), then `Installer.commit`, each in its **own** `withContext(Dispatchers.IO)`
  with main-thread hops in between.
- `app/src/main/java/dev/openfy/update/Installer.kt:33-35`: `file.inputStream().use { it.copyTo(out) }` re-reads the
  file from disk into the session with no digest and no byte count check.

Scenario: anything that changes the file between line 166 and line 177 ships unverified bytes to the installer. In
the app sandbox that is "same uid or root" (so not an external attacker), but also the system itself: `cacheDir` may be
trimmed under storage pressure, and a half-deleted or truncated file would be copied silently (`copyTo` does not check
length; `openWrite(..., file.length())` is evaluated again at that moment). Spec §2.3-5 asks to "hash and parse the
same `File` object and copy it into the session with `openWrite` immediately after the checks on the same thread";
the implementation parses and copies the same path, but the hash is of the stream, not of the file, and the steps are
not on one thread. Android will still refuse a wrong-signer APK, so this is defense in depth, not an exploitable hole.

Fix (cheap, 64 MiB max): make `Installer.commit(context, file, expectedSha256)` stream with
`copyBounded(input, out, file.length(), Sha256.newDigest())`, compare `Sha256.hex(md.digest())` to `expectedSha256`
and the byte count to `file.length()` **before** `session.commit(...)`; on mismatch `abandonSession` and throw
`UpdateException("The APK changed on disk between verification and install.")`. Then the installer provably sees
exactly the verified bytes, and the three IO blocks can stay separate.

### MAJOR-2: the "install unknown apps" return path dies silently if the Activity was recreated

- `app/src/main/java/dev/openfy/update/UpdateUi.kt:31`: `pendingRelease` is a plain field of the `UpdateUi` instance.
- `UpdateUi.kt:33-43`: the `registerForActivityResult` callback does `val release = pendingRelease ?: return`.
- `UpdateUi.kt:119`: `pendingRelease = release` is set just before `unknownSources.launch(...)`.

Scenario: the user taps "Open settings", the system Settings activity covers `MainActivity` (STOPPED). On a low-memory
device the system destroys `MainActivity`. The user flips "Allow from this source" and presses Back; `MainActivity` is
recreated, a **new** `UpdateUi` is built with `pendingRelease == null`, the result is delivered to it, and the callback
returns without doing anything. `UpdateManager.state` is still `AwaitingPermission(release)`, which `render` maps to
`Unit` (`UpdateUi.kt:85`): the user granted the permission and sees nothing. `isBusy()` (`UpdateManager.kt:36-40`)
does not include `AwaitingPermission`, so the state is never cleared either; a later manual check happens to work.

Fix: recover the release from the singleton instead of the field:
`val release = pendingRelease ?: (UpdateManager.state.value as? UpdateState.AwaitingPermission)?.release ?: return`.
Also render `AwaitingPermission` by re-checking `canRequestPackageInstalls()` on `onStart` (if granted, start the
install; if not, show the explanation dialog again) so the state is never an invisible dead end.

### MAJOR-3: `Installing` can wedge the updater for the rest of the process lifetime

- `app/src/main/java/dev/openfy/update/UpdateManager.kt:251-253`: `userActionLaunched()` moves `NeedsUserAction` to
  `Installing` and drops the confirm `Intent`.
- `UpdateManager.kt:36-40` + `74-77` + `159`: `Installing` is busy; `check()` and `install()` return silently
  ("check ignored, busy"), including **manual** checks, which then show nothing.
- `UpdateManager.kt:255-257`: `STATUS_SUCCESS` keeps `Installing` (fine when the process is replaced).

Scenario: the system confirm sheet is launched, the user leaves it with Home (or a Play Protect "scan recommended"
page, observed in H9 step 3, is backgrounded) and no terminal status ever arrives; or the status broadcast is lost.
Not observed in H9, but nothing in the code bounds it. The process is long-lived (playback service), so from then on
"Check for updates" does nothing, with no toast and no dialog, until the user force-stops the app. The only recovery
is `cleanup()` on the next process start.

Fix: keep `NeedsUserAction(confirm)` as the state until a terminal status (so a later `onStart` can relaunch the
sheet, which is what §3.7 describes), and let a **manual** `check()` break out of `Installing`/`NeedsUserAction`:
`Installer.abandonStaleSessions(context); discardFile(); mutableState.value = Idle` before proceeding. Also report
"An update is already in progress." as a `Notice` for a manual check that is refused for any other busy state.

---

## MINOR

### MINOR-1: download/sidecar URLs are not pinned to the repository

`app/src/main/java/dev/openfy/update/ReleaseParser.kt:58, 65-66` accept any `browser_download_url` that later passes
the host allow-list, i.e. any `https://github.com/<other-owner>/<other-repo>/releases/download/...`. Under §2.3 the API
response is trusted, so this only matters if the JSON is wrong without the API being compromised (none today). Still
cheap and makes the trust boundary explicit: require
`url == "https://github.com/${BuildConfig.UPDATER_REPO}/releases/download/$tag/$apkName"` (and the `.sha256` likewise),
else `Invalid("APK asset is hosted outside the release")`.

### MINOR-2: signer check, API-specific notes (no bug found)

`app/src/main/java/dev/openfy/update/PackageSigners.kt:30-43`:
- 28+: `GET_SIGNING_CERTIFICATES or GET_SIGNATURES`, `apkContentsSigners` (returns all signers, so
  `hasMultipleSigners()` is implicitly handled), fallback to `signatures`. Correct, and the dual flag covers the
  API 28-33 null-`signingInfo` archive bug the spec cites.
- Fallback caveat: on 28+ `signatures` for a **rotated** app contains the *original* cert, `apkContentsSigners` the
  *current* one; mixing them across installed/archive would mismatch. Irrelevant today (no rotation, single signer),
  but if rotation is ever allowed use `signingCertificateHistory` of the archive and require it to contain the
  installed set; Android's own rule is "lineage contains current signer", so set equality is strictly stricter
  (fail-closed). Keep as is, keep "no rotation" in the spec.
- 26-27: `getPackageArchiveInfo(path, GET_SIGNATURES)` runs `ApkSignatureVerifier` (v2 first) on those releases, so a
  v2/v3-only APK parses. H9 ran only on API 35; run steps 5-7 of §5 once on an API 26 or 27 image before the first
  release to confirm `signatures` is non-empty for the release APK there.
- `PreInstallCheck.refusal` parses the archive twice (`archiveFacts` + `archive()` at `UpdateManager.kt:217-218`,
  `PackageSigners.kt:22,29`); one `getPackageArchiveInfo` call with the signing flags would do both. Performance only.

### MINOR-3: the receiver does not check the session id

`app/src/main/java/dev/openfy/update/InstallStatusReceiver.kt:17-39` acts on any status for the action. Not spoofable
from outside: `exported=false` (verified in the release manifest), explicit-component `PendingIntent`, and the
intent sender is only ever handed to `PackageInstaller`. Within the app a status for a session abandoned by
`cleanup()` (a late broadcast) can still flip the state. Store the committed session id in `UpdateManager` and ignore
`EXTRA_SESSION_ID` mismatches.

### MINOR-4: `cleanup()` abandons sessions that may still be confirmable

`app/src/main/java/dev/openfy/update/Installer.kt:59-65` abandons every `mySessions` entry. If the process died while
the system confirm sheet was up (our Activity is in the background behind it and is a kill candidate), the user's later
tap on the system "Update" button installs fine, but if they return to openfy first, the new process abandons the
committed session and the sheet fails with a system error. On API 29+ skip sessions where `isCommitted`; on 26-28
keep the current behaviour (no way to tell).

### MINOR-5: size-cap errors carry a misleading message for small fetches

`app/src/main/java/dev/openfy/update/BoundedCopy.kt:43` always says "download exceeds the expected size (N bytes)".
For the 1 MiB JSON cap and the 4 KiB checksum cap (`UpdateHttp.kt:61-70`) the user then reads "Update check failed:
download exceeds the expected size (1048576 bytes)". Pass a `what: String` into `copyBounded` or map the message in
`getText`. Also: a `copyBounded` overflow on an error body (`errorStream`) hides the real HTTP status.

### MINOR-6: `URI` vs `URL` parsing differential (verified fail-closed; add tests)

`UpdateHosts.isAllowed` (`app/src/main/java/dev/openfy/update/UpdateHosts.kt:24-33`) decides with `java.net.URI`;
`UpdateHttp.get` connects with `java.net.URL` (`UpdateHttp.kt:31`). Checked cases: backslashes, `%`-encoded host
bytes, `_` in the host and non-ASCII (raw IDN) make `URI` throw or yield `host == null`, so they are rejected before
`URL` sees them; trailing dot (`github.com.`) fails the exact match; `userInfo` is rejected; port must be absent or
443; scheme-relative `Location` (`//evil/x`) resolves to a disallowed host. The redirect cap is 5 hops
(`UpdateHosts.kt:44`), `Location` is resolved with `URI.resolve` (`UpdateHosts.kt:46`) and every hop is re-checked at
the top of the loop (`UpdateHttp.kt:28`). No bug. Add `https://github.com./`, `https://xn--gthub-9ta.com/`,
`https://github.com%2eevil.com/`, `https://github.com\\@evil.com/` and a `Location: //evil.com/x` case to
`UpdateHostsTest` so the fail-closed behaviour is pinned.

### MINOR-7: `Content-Length`/streaming cap (verified correct)

`UpdateHttp.kt:79-96`: `contentLengthLong` is only trusted when present and must equal `size`; the stream is bounded
by `size` while writing (`copyBounded`, abort on the first byte over), and the final count and `dest.length()` must
equal `size`. `size` itself is capped to 64 MiB at parse time (`ReleaseParser.kt:61`, `BoundedCopy.kt:16-20`). The
hash is updated with exactly the bytes written (`BoundedCopy.kt:44-45`). No change needed.

### MINOR-8: checksum formats (verified correct)

`BoundedCopy.kt:69-77`: `sha256:<64 hex>` → lower-case hex, anything else → null (falls through to the sidecar);
sidecar: first whitespace token, 64 hex, upper-case accepted, lower-cased. `<64 hex>  openfy-x.apk\n` from
`sha256sum` parses. The file-name token of the sidecar is not compared to the APK name; optional hardening. Note the
JVM tests run stock `org.json` where `optString` of a JSON `null` returns `""`, while Android's `org.json` returns the
string `"null"`; both paths end in `fromDigestField(...) == null`, so the behaviour is the same, but keep that in mind
for any future `optString` use (`ReleaseParser.kt:58, 63`).

### MINOR-9: zero-asset and skipped-tag handling (verified correct)

A published release with no assets parses as `Ignored("release has no usable APK (0 candidates)")`
(`ReleaseParser.kt:51`) → `quiet(...)` → `Idle`/`Notice` (`UpdateManager.kt:88`); `skipped_tag` is written only in
`skip()` (`UpdateManager.kt:134-138`), never by a check; `last_check_ms` is written (`UpdateManager.kt:85`). This
matches the release-review requirement. Cosmetic: the manual toast reads "No update available (release has no usable
APK (0 candidates))."; "No update available yet (the latest release is still being built)." reads better.

### MINOR-10: concurrency and cancellation

- `check()`/`install()` do check-then-set on `mutableState` without suspension on the main dispatcher
  (`UpdateManager.kt:74-80, 159-163`), so two taps cannot interleave. Fine.
- Cancel is honoured only between reads (`UpdateManager.kt:203-205` via `onProgress`); a stalled socket blocks up to
  the 30 s read timeout with the Cancel button already pressed and the dialog still showing. Call `conn.disconnect()`
  from the cancel path (or use `invokeOnCompletion`) if this is noticeable.
- `installJob` (`UpdateUi.kt:30, 130, 150`) belongs to one `UpdateUi`; after Activity recreation it is `null`, but the
  old `lifecycleScope` has already cancelled the download, so nothing leaks.

### MINOR-11: error handling that hides failures

- `UpdateManager.kt:74-77, 159`: silent return when busy (see MAJOR-3 for the manual case).
- `UpdateUi.kt:35`: silent return in the activity-result callback (see MAJOR-2).
- `UpdateUi.kt:81`: `activity.startActivity(state.confirm)` is not guarded; an `ActivityNotFoundException` or
  `SecurityException` from a malformed system intent propagates out of the `collect` and crashes the app. Wrap and
  route to `UpdateManager.onInstallFailed(STATUS_FAILURE, ...)`.
- `UpdateManager.kt:45-59`: `cleanup()` swallows everything with a log; acceptable for a best-effort sweep.
- `UpdateManager.kt:186`: `Failed(e.message ?: ...)` surfaces raw `IOException` text to the user (OkHttp messages
  contain host:port, not the signed URL; `FileNotFoundException` from `conn.inputStream` would contain the full URL,
  but `download` checks `status == 200` first and `getText` uses `errorStream`, so no signed URL reaches logs or
  dialogs today). Consider a generic "Network error: <class>" for non-`UpdateException` causes to keep it that way.

### MINOR-12: dead `-debug` suffix handling

`app/src/main/java/dev/openfy/update/Version.kt:28, 49-53` and `PreInstallCheck.kt:29`: `removeSuffix("-debug")` is
a no-op now that the release pipeline has no `versionNameSuffix`, and `PreInstallCheck` only strips it from the
*archive* name in debug builds, where the archive is a release APK that never carries it. Correct either way; delete
`stripDebugSuffix`/`DEBUG_SUFFIX` and the `isDebug` parameter to avoid implying a behaviour that does not exist.

### MINOR-13: process death during install (verified acceptable)

Download: the `lifecycleScope` dies with the Activity, the file is deleted (`UpdateManager.kt:178-182`) or, on process
death, by `cleanup()` at the next start. After `commit`: the session belongs to the system; if the user confirms, the
install proceeds and the next start sees `last_installed_code != VERSION_CODE` and toasts (`UpdateManager.kt:50-55`,
`UpdateUi.kt:61-63`). The one rough edge is MINOR-4.

### MINOR-14: naming deviations

Receiver lives in `.update.` not `.updater.` (manifest and class agree; `AndroidManifest.xml:43-45`), log prefix is
`update:` not `updater:`. Update §3.1/§3.9 of the spec rather than the code.

---

## Verified without findings

- **PendingIntent**: explicit component, `FLAG_UPDATE_CURRENT | FLAG_MUTABLE` (31+), request code = session id
  (`Installer.kt:50-56`); the sender is handed only to `PackageInstaller`. Receiver `exported=false` in the release
  manifest. No exported entry point can start a check or an install; `MainActivity`'s `osp_url`/`osp_diag` extras
  (`MainActivity.kt:96-107`) are unrelated to the updater.
- **Debug-only test origin**: `UPDATER_TEST_BASE` is `""` in `defaultConfig` (`app/build.gradle.kts:103`) and only
  overwritten in the `debug` build type (`:126-127`); `UpdateHosts.forBuild` returns `PRODUCTION` when `!isDebug`
  (`UpdateHosts.kt:79`), `http` is only allowed when a `testOrigin` exists (`UpdateHosts.kt:30`), the cleartext
  network-security config exists only under `app/src/debug/` and the release merge has none. Three independent locks.
- **TLS**: default `HttpsURLConnection`, system trust store (user CAs excluded by the API 24+ default), no
  `Authorization` header, `X-GitHub-Api-Version` only on hop 0 to `api.github.com` (`UpdateHttp.kt:38-40`).
- **Path safety**: the cache file name derives from the tag, which must match the version grammar
  (`Version.kt:29-41`), so no traversal.
- **Dialog content**: `name`, `body` rendered as plain text, capped at 4000 chars (`ReleaseParser.kt:87-90`).
- **Downgrade / identity**: `packageName`, `longVersionCode` and `versionName` are read from the archive
  (`PackageSigners.kt:29-30`) and compared to the installed package and the tag (`PreInstallCheck.kt:21-32`); strict
  `>` on `versionCode`.

## Suggested order

1. MAJOR-1 (re-hash into the session) and MAJOR-2 (recover the release from state) are each a few lines.
2. MAJOR-3 (manual check breaks out of `Installing`) with MINOR-3 (session id in the receiver).
3. MINOR-1, MINOR-5, MINOR-6 tests, MINOR-11 `startActivity` guard, then the cosmetic ones.
