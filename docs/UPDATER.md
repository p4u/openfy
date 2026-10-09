# openfy — Release pipeline and in-app updater (v1)

Spec for two features: (A) a GitHub Actions workflow that builds, signs and attaches `openfy-<version>.apk`
to every published GitHub Release of `p4u/openfy`, and (B) an in-app updater that checks that repo's latest
release, downloads the APK, verifies it, and installs it through `PackageInstaller`.

Assumptions: the app has been renamed (`applicationId = "dev.openfy"`, namespace `dev.openfy`, root project
`openfy`), the repo is **public**, there is no Play Store distribution. Facts marked *(verified 2026-10)* were
checked against the Android reference / GitHub docs while writing this.

Implementation split: **Agent 1** = §1 (CI, Gradle signing/versioning). **Agent 2** = §2–§4 (updater).
Shared contract between them: §1.2 version grammar, §1.5 artifact names, §2.1 asset discovery.

---

## 1. Release pipeline

### 1.1 Trigger and dry run

File: `.github/workflows/release.yml`.

```yaml
on:
  release:
    types: [published]
  workflow_dispatch:
    inputs:
      version:
        description: "Version to build, e.g. 1.2.3 or 1.2.3-rc.1 (dry run: nothing is uploaded to a release)"
        required: true
```

- `release: published` is the real path. `github.event.release.tag_name` is the tag.
- `workflow_dispatch` is a **dry run**: same steps, version taken from the input, APK + checksum uploaded as a
  *workflow artifact* (`actions/upload-artifact`) only. Never calls `gh release upload`. Signing still runs if the
  secrets are present (so the dry run proves the keystore decodes and the fingerprint matches).
- Trigger from tags is deliberately *not* used: publishing the Release is the single human gate.

### 1.2 Versioning (the contract shared with the updater)

Tag grammar (regex, anchored): `^v(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?$`. Examples: `v0.2.0`, `v0.2.0-rc.1`.

- `versionName` = tag without the leading `v` (`0.2.0`, `0.2.0-rc.1`).
- `versionCode` = `X*1_000_000 + Y*10_000 + Z*100 + (final ? 99 : N)`, with `1 ≤ N ≤ 98`, `Y,Z ≤ 99`, `X ≤ 2000`.
  This is monotonic across pre-releases and finals of the same triple (`rc.1 < rc.2 < final`) and across
  triples. Test vectors (put them in both the shell test and the Kotlin test):

  | tag | versionName | versionCode |
  |---|---|---|
  | `v0.1.0` | `0.1.0` | 10099 |
  | `v0.2.0-rc.1` | `0.2.0-rc.1` | 20001 |
  | `v0.2.0-rc.12` | `0.2.0-rc.12` | 20012 |
  | `v0.2.0` | `0.2.0` | 20099 |
  | `v1.0.0` | `1.0.0` | 1000099 |
  | `v1.2.3` | `1.2.3` | 1020399 |

- **Pre-releases are allowed** (tag `-rc.N` **and** the GitHub release must be marked *pre-release*). The workflow
  fails if the tag has an `-rc` suffix and `github.event.release.prerelease` is false, or vice versa.
  The updater ignores pre-releases because it reads `releases/latest`, which GitHub defines as the most recent
  non-prerelease, non-draft release. So `rc` builds are only for people who sideload them by hand.
- Local builds without a version: `versionName = "0.0.0-dev"`, `versionCode = 1`. The updater treats `-dev` as
  lower than everything (§2.4), so a dev build always sees an update offer; the signer check then refuses it unless
  it was signed with the release key.

Where the computation lives:

- `app/build.gradle.kts`: a small function `versionFromName(name: String): Pair<String, Int>` applying the grammar
  above to the Gradle property `-PversionName=` (no `v`). Missing property → `0.0.0-dev` / 1. Invalid → fail the
  configuration with a message quoting the grammar.
- The workflow strips the `v` and passes `-PversionName=$VERSION`. The workflow also validates the tag with the
  same regex in bash before invoking Gradle, so a bad tag fails in one second, not after a build.
- `app/src/main/.../updater/Version.kt` re-implements the parse/compare (Kotlin, pure, unit-tested; §2.4). It does
  **not** need the versionCode formula; the updater compares semver and then trusts the APK's own `versionCode`.

### 1.3 Runner

`ubuntu-latest`. The runner image ships the Android SDK with `ANDROID_HOME` set and licenses accepted; AGP 8.7
auto-downloads platform 35 / build-tools 35.0.0 if missing, so no `setup-android` action is needed. Steps, with
actions **pinned to commit SHAs** (resolved 2026-10-10; re-resolve when bumping):

```yaml
- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1          # v7.0.1
  with: { persist-credentials: false }
- uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6        # v6.0.1
  with: { distribution: temurin, java-version: "17" }
- uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6   # v6.4.0
  with: { cache-read-only: true }      # release builds never write the cache
- uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9   # v7.0.2 (dry run only)
```

Permissions: top-level `permissions: {}`; the single job sets `permissions: { contents: write }` (needed by
`gh release upload`). `concurrency: { group: release-${{ github.ref }}, cancel-in-progress: false }`.
Job `environment: release` so the signing secrets live in a GitHub *environment* that can require a reviewer
(see §3.1 for why that matters). Timeout 30 min.

### 1.4 Signing

Secrets (environment `release`):

| Secret | Content |
|---|---|
| `OPENFY_KEYSTORE_B64` | `base64 -w0 openfy-release.jks` (PKCS12 keystore) |
| `OPENFY_KEYSTORE_PASSWORD` | store password |
| `OPENFY_KEY_ALIAS` | key alias (`openfy`) |
| `OPENFY_KEY_PASSWORD` | key password |

Repository **variable** (not secret, it is public information anyway): `OPENFY_RELEASE_CERT_SHA256` = the
signing certificate's SHA-256 fingerprint, upper-case hex with colons, as printed by `apksigner`.

Keystore creation (once, offline, by the maintainer; keep an offline backup — losing it means every user must
uninstall to take the next update):

```sh
keytool -genkeypair -v -keystore openfy-release.jks -storetype PKCS12 -alias openfy \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=openfy, O=openfy"
```

CI decode step (secrets never echoed; `set +x`; the `.jks` goes to `$RUNNER_TEMP`, which is outside the
checkout and discarded with the runner):

```sh
echo "$OPENFY_KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.jks"
echo "OPENFY_KEYSTORE_FILE=$RUNNER_TEMP/release.jks" >> "$GITHUB_ENV"
```
and export `OPENFY_KEYSTORE_PASSWORD`, `OPENFY_KEY_ALIAS`, `OPENFY_KEY_PASSWORD` as job `env` from secrets.

Gradle (`app/build.gradle.kts`), resolution order, first hit wins:

1. Env vars `OPENFY_KEYSTORE_FILE`, `OPENFY_KEYSTORE_PASSWORD`, `OPENFY_KEY_ALIAS`, `OPENFY_KEY_PASSWORD`.
2. A properties file at `$OPENFY_KEYSTORE_PROPERTIES`, default `~/.config/openfy/keystore.properties`
   (keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; `storeFile` absolute). Never inside the repo;
   add `keystore.properties` and `*.jks` to `.gitignore` anyway.
3. Nothing → `release.signingConfig = null` (AGP emits `app-release-unsigned.apk`) **unless** the Gradle property
   `-PrequireSigning=true` is set, in which case configuration fails:
   `"Release signing is required (-PrequireSigning=true) but no keystore was configured. See docs/UPDATER.md §1.4."`
   The workflow always passes `-PrequireSigning=true`. Dry runs on forks without secrets therefore fail at
   configuration time, loudly, by design.

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(cfg.storeFile); storePassword = cfg.storePassword
        keyAlias = cfg.keyAlias; keyPassword = cfg.keyPassword
        enableV1Signing = false   // minSdk 26: v2+v3 are enough; v1 (JAR) signing is legacy
        enableV2Signing = true; enableV3Signing = true; enableV4Signing = false
    }
}
```

Debug builds keep the default debug keystore and get `applicationIdSuffix = ".debug"` and
`resValue("string", "app_name", "openfy (debug)")` (see §4.3 for why).

### 1.5 Job steps in order

1. Checkout, JDK, Gradle (above).
2. **Resolve version**: from `github.event.release.tag_name` or the dispatch input; validate with the regex; check
   the pre-release flag consistency (§1.2); `echo "VERSION=…" >> $GITHUB_ENV`.
3. **Tests first**: `./gradlew --no-daemon testReleaseUnitTest lintRelease -PversionName=$VERSION`
   (JS conformance test too: `node --test tools/test/*.test.mjs`, Node 22 is on the runner). Any failure stops the
   job before signing material is decoded.
4. Decode keystore (§1.4).
5. **Build**: `./gradlew --no-daemon assembleRelease -PversionName=$VERSION -PrequireSigning=true`.
6. **Rename**: `cp app/build/outputs/apk/release/app-release.apk "dist/openfy-$VERSION.apk"`.
7. **Verify signature**:
   `"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs dist/openfy-$VERSION.apk`
   and assert it reports `Verified using v2 scheme: true`, `v3 scheme: true`, and that the printed
   `Signer #1 certificate SHA-256 digest` equals `vars.OPENFY_RELEASE_CERT_SHA256` (normalise case/colons). Also
   run `keytool -printcert -jarfile dist/openfy-$VERSION.apk` and print it to the log (human-readable fingerprint
   in the run log for the thesis appendix). Mismatch → fail (wrong keystore uploaded as secret).
8. **Verify version**: `aapt2 dump badging` (same build-tools dir) must show `package: name='dev.openfy'`,
   `versionName='$VERSION'` and the `versionCode` the formula predicts (bash re-implements the formula; use the
   §1.2 vectors in a tiny `tools/version-code.sh` with a self-test).
9. **Checksum**: `(cd dist && sha256sum openfy-$VERSION.apk > openfy-$VERSION.apk.sha256)` (format
   `<hex>  openfy-<version>.apk`, two spaces, `sha256sum -c` compatible).
10. **Upload** (release event only):
    `gh release upload "$TAG" dist/openfy-$VERSION.apk dist/openfy-$VERSION.apk.sha256 --clobber`
    with `env: GH_TOKEN: ${{ github.token }}` (`gh` is preinstalled). Dry run: `upload-artifact` of `dist/`.
11. Always (even on failure): `rm -f "$RUNNER_TEMP/release.jks"`.

Artifact names are **exact** and the updater depends on them: `openfy-<versionName>.apk`,
`openfy-<versionName>.apk.sha256`. One APK per release (universal, no ABI splits: the app has no native code).

### 1.6 Supply-chain hygiene checklist

- All actions pinned to full commit SHAs with a version comment (above). Dependabot `github-actions` ecosystem
  enabled for bumps.
- `permissions: {}` at the top; `contents: write` only on the job that uploads.
- `persist-credentials: false` on checkout; no `GITHUB_TOKEN` in Gradle.
- Secrets only via `env:` on the steps that need them (decode step, Gradle build), not job-wide.
- `set -euo pipefail` and never `set -x` in steps that touch secrets. Gradle runs with `--no-daemon` and without
  `--info`/`--debug`.
- No third-party actions beyond `actions/*` and `gradle/actions`. No `curl | sh`.
- The Gradle wrapper jar is in the repo; `gradle/actions/setup-gradle` (v4+) validates its checksum by default
  (`validate-wrappers: true`), so no separate wrapper-validation step is needed. Do not disable it.
- `apksigner verify` + fingerprint assert (§1.5 step 7) is the last line of defence against a swapped keystore.

### 1.7 R8 / minify: **off** for release in v1

Decision: `isMinifyEnabled = false`, `isShrinkResources = false`. Reasons: the APK is a few MB and mostly
androidx; the project's value is debuggability and reproducible measurements (stack traces in logcat without a
mapping file, `onRenderProcessGone` diagnostics, `diag()` output), and R8 adds a build-time variable with no
research benefit. The JS bridge itself is not the obstacle: `WebMessageListener` is an interface implementation
(kept by R8 automatically), `addDocumentStartJavaScript` reads an asset (R8 does not touch assets), and there is no
`@JavascriptInterface`. If minify is turned on later, the rules needed are only:

```
-keepattributes SourceFile,LineNumberTable
-keep class dev.openfy.updater.InstallStatusReceiver { *; }   # manifest-declared, already kept by AGP defaults
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }  # only if a JS interface is ever added
```
plus retaining `mapping.txt` as a release asset. Not now.

---

## 2. Updater: security model

### 2.1 Source of truth

`GET https://api.github.com/repos/p4u/openfy/releases/latest` (repo from `BuildConfig.UPDATER_REPO`, default
`"p4u/openfy"`, overridable with `-PupdaterRepo=owner/repo` for testing, §5.2). Headers:
`Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`,
`User-Agent: openfy/<versionName> (Android <SDK_INT>)` (GitHub rejects requests without a UA). Unauthenticated;
the 60 req/h/IP limit is irrelevant at one call per day. Timeouts 10 s connect / 15 s read. Response cap 1 MiB.
`404` → "no release yet", not an error dialog.

From the JSON take: `tag_name`, `name`, `body`, `prerelease`, `draft`, `html_url`, and in `assets[]` the entry
whose `name` matches `^openfy-(.+)\.apk$` (exactly one; zero or more than one → "release has no usable APK",
treated as no update). From that asset: `browser_download_url`, `size`, `digest` (string `"sha256:<hex>"`, present
on assets uploaded since mid-2025 *(verified)*; may be `null` on older releases), and the sibling asset named
`<apk name>.sha256` if present. The version inside the asset name must equal `tag_name` minus `v`; if not, ignore
the release (log `updater: asset/tag mismatch`).

`prerelease == true` or `draft == true` can never come back from `/latest`, but check anyway and ignore.

### 2.2 Transport

- HTTPS only. Any `http://` URL (initial or redirect) → abort.
- Redirects are followed **manually** (`instanceFollowRedirects = false`, loop ≤ 5, only 301/302/303/307/308,
  `Location` resolved against the current URL). Every hop must pass the host allow-list below; a `Location` outside
  it → abort with `updater: redirect to disallowed host <host>` (host logged, never the full signed URL, which
  carries a token).
- Allow-list (exact host match, case-insensitive, no port or port 443 only):
  `api.github.com`, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`.
  *(verified: `browser_download_url` on `github.com` currently 302s to either
  `objects.githubusercontent.com` or `release-assets.githubusercontent.com`; GitHub says the latter is permanent
  and may add more `*.githubusercontent.com` subdomains over time.)* Keep the list exact (no wildcard) and accept
  that a new GitHub host means a release that updates the list; the manual check shows the host in the error so
  the user can report it. Do **not** send any `Authorization` header anywhere.
- System trust store only (default `HttpsURLConnection`); no pinning, no `skipTlsVerification`. The cleartext
  block from `targetSdk 28+` is already in effect.

### 2.3 Integrity and the threat model (read this before touching the install path)

Layers, weakest to strongest:

1. **SHA-256** of the download versus the release's own `digest` (or `.sha256` asset). This catches corruption,
   truncation and tampering *in transit or on the asset host*, since the hash comes from the API host and the bytes
   from a different host. It does **not** protect against someone who controls the GitHub repo/account: they
   publish a matching hash with the malicious APK. So the checksum is an integrity check, not an authentication.
   Policy: if the API gives a `digest`, use it; else download `<apk>.sha256` (same allow-list, cap 4 KiB) and parse
   the first hex token; if neither exists → **refuse** ("release has no checksum").
2. **Package identity**: `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES or GET_SIGNATURES)` → `packageName`
   must equal `context.packageName`, `longVersionCode` must be **strictly greater** than ours (downgrade protection;
   Android enforces this too for non-debuggable apps, we fail earlier with a clearer message), and `versionName`
   must equal the release version.
3. **Same signer (the actual authentication)**. Android refuses to update an installed package with an APK signed by
   a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); that rule, not our code, is what makes a GitHub
   compromise unable to push code to existing installs *as long as the signing key is not also compromised* (§3.1).
   We add our own pre-install comparison so the failure is explained before the system dialog and before any
   `PackageInstaller` session is created:
   - installed: `packageManager.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)` on 28+
     (`signingInfo.apkContentsSigners`; if `hasMultipleSigners()` use all), `GET_SIGNATURES` → `signatures` on 26–27.
   - archive: `getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES or GET_SIGNATURES)` on 28+ and read
     `signingInfo?.apkContentsSigners ?: signatures` *(verified: some API 28–33 builds leave `signingInfo` null
     unless `GET_SIGNATURES` is also passed, issue 243324820)*; `GET_SIGNATURES` → `signatures` on 26–27.
   - compare as **sets of SHA-256(DER)** of each `Signature.toByteArray()`; must be equal and non-empty.
     No key rotation support (we never rotate; a rotation would need `signingCertificateHistory` logic, out of scope).
4. **Size cap**: refuse `size > 64 MiB` from the API before downloading; abort if the stream exceeds the declared
   `size` or the cap; after download the file length must equal `size`.
5. **Storage**: `File(context.cacheDir, "updates")`, mode private (default), never `getExternalCacheDir()`. The
   file is deleted on any failure and on the next app start (`UpdateManager.cleanup()` in `Application.onCreate`).
   Between verification and install nothing else can write there (app-private dir), so TOCTOU is not a concern
   within our sandbox; still, hash and parse the **same `File`** object, and copy it into the session with
   `openWrite` immediately after the checks on the same thread.

What this model does *not* give: protection against a leaked signing key, and freshness (a GitHub compromise can
withhold updates, which is harmless). Both are documented limitations.

---

## 3. Updater: mechanics and UX

### 3.1 Permissions and manifest

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<receiver android:name=".updater.InstallStatusReceiver" android:exported="false" />
```

`REQUEST_INSTALL_PACKAGES` is a normal (install-time) permission, but on API 26+ the user must additionally allow
"install unknown apps" **per app**: check `packageManager.canRequestPackageInstalls()`; if false, show an
explanation dialog, then `startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))`
through `registerForActivityResult(StartActivityForResult())`; on return re-check and continue (or stop with
"Permission not granted"). Do this **before** downloading, so the user is not asked after a 5 MB download.

Side note for the thesis: the signing key lives in GitHub environment secrets. Anyone with write access to
the environment can sign. Mitigation in v1: the `release` environment requires the maintainer's review before the
job can access secrets; the fingerprint assert (§1.5) is logged on every release. Moving signing offline (CI builds
unsigned, maintainer signs with `apksigner` locally and uploads) is the next step if the project outgrows "one
maintainer".

### 3.2 Check cadence

- Automatic: in `MainActivity.onStart`, after the feature gate passes, if `!BuildConfig.DEBUG` and
  `now - prefs.last_check_ms >= 24 h` (`System.currentTimeMillis()`; `last_check_ms` is written when the check
  *completes*, success or failure, so a flaky network does not hammer GitHub). Silent on failure (log only). A
  result "newer version and `tag != prefs.skipped_tag`" shows the dialog.
- Manual: debug menu entry **"Check for updates"** (always present, also in debug builds). Shows a result in all
  cases: "Update available" dialog, "You have the latest version (x.y.z)", or the error string.
- SharedPreferences file `openfy_updater`: `last_check_ms: Long`, `skipped_tag: String?`.
- The check runs regardless of playback state (it is one small request). Only the *install* step cares about
  playback (§3.5).

### 3.3 Dialog

`AlertDialog` (appcompat, as the existing ones):
- Title: `Update available: <versionName>` (from the tag). Body: `name` line, then release `body` (markdown shown
  as plain text, trimmed, max 4000 chars, "…" if cut), then a footer `Size: <size in MB>`.
- Buttons: **Install** (positive), **Later** (negative), **Skip this version** (neutral → `skipped_tag = tag`).
- Debug build: footer adds `Debug build: the installer will refuse this APK (different signing key).` and Install
  still works up to the signer check, which is the useful part for development (§5.2).

### 3.4 Download: `HttpURLConnection` on `Dispatchers.IO`, not `DownloadManager`

Why not `DownloadManager`: it follows redirects itself (defeats §2.2 host checking), writes to shared storage
or app-external dirs, can be disabled on some devices (the enqueue then throws), has its own notification UX, and
survives process death — a property we do not want for a 5 MB file whose verification state lives in our process.
`HttpURLConnection` is already the project's only HTTP client (`ArtworkLoader`), so no new dependency or pattern.

Rules: run in `lifecycleScope` of the Activity (cancel on destroy; a cancelled download deletes the file); progress
posted to a `ProgressDialog`-style `AlertDialog` with a `ProgressBar` (determinate, `size` known) and a Cancel
button; `MessageDigest("SHA-256")` updated while writing (`DigestOutputStream`), so the hash is of exactly the
bytes on disk; 8 KiB buffer; timeouts 10 s / 30 s; `Content-Length`, if present, must equal `size`.

### 3.5 Pre-install

In order, each failure shows a dialog with the reason and deletes the file:
1. SHA-256 == expected (§2.3-1).
2. Archive info: package, version, signer (§2.3-2/3). `getPackageArchiveInfo` returning null → "not a valid APK".
3. `CookieManager.getInstance().flush()` so the Spotify login survives the process kill that follows.
4. If `MediaBridge.state.isPlaying`: tell the user in the same confirmation (`Playback will stop while the update
   installs.`), then `MediaBridge.invoke("pause")` and `PlaybackService` stop (`stopSelf` via the existing close
   path), **before** committing the session. The install kills the process: the WebView, the foreground service and
   the notification all die; nothing needs saving because state is cookies (flushed) and the page.

### 3.6 Install: `PackageInstaller` session API

Not `ACTION_VIEW`/`ACTION_INSTALL_PACKAGE` (deprecated, needs a `FileProvider`, no status callback, no silent path).

```kotlin
val pi = context.packageManager.packageInstaller
val params = PackageInstaller.SessionParams(MODE_FULL_INSTALL).apply {
    setAppPackageName(context.packageName)
    setSize(file.length())
    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(USER_ACTION_NOT_REQUIRED)
    if (Build.VERSION.SDK_INT >= 33) setPackageSource(PACKAGE_SOURCE_DOWNLOADED_FILE)
}
val sessionId = pi.createSession(params)
pi.openSession(sessionId).use { s ->
    s.openWrite("openfy.apk", 0, file.length()).use { out ->
        file.inputStream().use { it.copyTo(out) }; s.fsync(out)
    }
    val intent = Intent(context, InstallStatusReceiver::class.java)   // EXPLICIT: component set
        .setAction(InstallStatusReceiver.ACTION).putExtra(EXTRA_SESSION_ID, sessionId)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    s.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
}
```

- `FLAG_MUTABLE` is required on 31+ because the system fills the status extras into the intent; on Android 14+
  (targetSdk 34+, we target 35) a mutable `PendingIntent` **must** wrap an explicit intent or creation throws
  *(verified: Android 14 behaviour change)* — hence the component-explicit intent above.
- Silent install (`USER_ACTION_NOT_REQUIRED`) actually takes effect only when *(verified against the
  `SessionParams.setRequireUserAction` reference)*: Android 12+, the installed app targets ≥ 33 on Android 15 (we
  target 35; the bar rises one level per Android release, so keep `targetSdk` current), and the caller is the
  **installer of record / update owner** of the installed app, i.e. a previous version was installed *by openfy
  itself*. The first update after a sideload (`adb install`, Files app) therefore always shows the system confirm
  sheet; from the second update on it is silent. Never assume it: always handle `STATUS_PENDING_USER_ACTION`.
- Session timeouts / orphan sessions: on app start, `pi.mySessions.forEach { pi.abandonSession(it.sessionId) }`
  before any new check (cleanup, cheap).

### 3.7 Status receiver

`InstallStatusReceiver : BroadcastReceiver` (manifest, `exported=false`; the PendingIntent is sent as our own
app so non-exported is fine):

```kotlin
when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_FAILURE)) {
    STATUS_PENDING_USER_ACTION -> {
        val confirm = if (SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                      else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        UpdateManager.onUserActionRequired(confirm)        // see below
    }
    STATUS_SUCCESS -> { /* process is about to die; nothing to do. Log. */ }
    else -> UpdateManager.onInstallFailed(status, intent.getStringExtra(EXTRA_STATUS_MESSAGE))
}
```

`UpdateManager` (process singleton, like `WebViewHolder`) exposes a `MutableStateFlow<UpdateState>`
(`Idle, Checking, Available(release), Downloading(progress), Verifying, Installing, NeedsUserAction(intent),
Failed(message)`). `MainActivity` collects it with `repeatOnLifecycle(STARTED)`. On `NeedsUserAction` it calls
`startActivity(confirm)` **from the Activity** (the receiver must not start activities: background-activity-start
rules on 10+ would drop it when the app is not in the foreground). If the Activity is not started when the status
arrives, the state is kept and the confirm intent launched at the next `onStart`; the pending session survives.
On `Failed` show the message (`EXTRA_STATUS_MESSAGE` is human-readable, e.g. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`)
and delete the file. `STATUS_FAILURE_ABORTED` (user cancelled the sheet) → `Idle`, no error dialog.

### 3.8 After the update

- `Application.onCreate`: `UpdateManager.cleanup(context)` deletes `cacheDir/updates`; if
  `prefs.last_installed_code != BuildConfig.VERSION_CODE` → write it and let `MainActivity` show a one-line toast
  `Updated to <versionName>`.
- `ACTION_MY_PACKAGE_REPLACED` is not needed (the process is killed and the next launch handles it).

### 3.9 Debug builds and the first release install

- `applicationIdSuffix ".debug"` (§1.4) means **debug and release are different packages** (`dev.openfy.debug`
  vs `dev.openfy`), coexist, and the updater's package-equality check keeps them apart. No uninstall is needed to
  move from a debug build to the release line; the user simply logs in once in the new package (cookies are
  per-app). The old `dev.openspotify.shell` PoC package is likewise untouched by the rename.
- If someone had installed a *release-variant* build signed with a local/debug key under `dev.openfy`, the update
  fails at the signer check with `Installed app was signed with a different key; uninstall and install the release
  APK from GitHub.` This is the only case where the login is lost once.
- Debug builds: automatic check off, manual check on, install refused by the signer check (expected). The log tag
  prefix for everything in this feature is `updater:` under the existing `TAG`.

---

## 4. DESIGN.md impact (to be applied by the owner, not by the implementers)

§1 Non-goals, replace the bullet

> No private Spotify APIs and no Spotify Web API keys. Native code's only network access is fetching the
> artwork URL the page itself published through `navigator.mediaSession`.

with

> No private Spotify APIs and no Spotify Web API keys. Native code's only network access **towards Spotify** is
> fetching the artwork URL the page itself published through `navigator.mediaSession`. The only other native
> network access is the self-updater (`docs/UPDATER.md`): it talks exclusively to GitHub (`api.github.com` and the
> release-asset hosts) over HTTPS to check for and download new releases of this app, at most once a day, sends no
> identifiers beyond its own version string, and never touches the Spotify session.

Also add to §3 Tech stack: "Permission `REQUEST_INSTALL_PACKAGES` for the self-updater." The README "Ethics and
non-goals" list has the same sentence and needs the same edit. Add to §5 risks: "7. The updater's trust root is the
release signing key held in GitHub environment secrets (UPDATER.md §3.1)."

---

## 5. Test plan

### 5.1 Unit tests (JVM, `app/src/test`, JUnit 4 + `org.json` as the existing tests)

Keep the pure logic in classes with no Android imports except `org.json`:

- `Version.kt`: `parse("0.2.0-rc.1")`, `parse("v0.2.0")` (leading `v` accepted), `parse("0.0.0-dev")`,
  rejects `"0.2"`, `"0.2.0-beta"`, `"0.2.0-rc.0"`, `"0.2.0-rc.99"`. Ordering: `dev < rc.1 < rc.2 < final`,
  `0.9.9 < 0.10.0`, `1.0.0-rc.1 < 1.0.0`. The §1.2 vectors for `versionCode` live in the Gradle-side script test,
  not here.
- `ReleaseParser.kt`: a captured `releases/latest` JSON fixture (assets with `digest`, one without, `.sha256`
  sibling, two APKs → rejected, draft/prerelease → rejected, asset version ≠ tag → rejected, 404 body).
- `UpdateHosts.kt`: `isAllowed(url)` for the four hosts, `HTTP` scheme rejected, `api.github.com.evil.com`
  rejected, `GITHUB.COM` accepted, `github.com:8443` rejected, userinfo trick `github.com@evil.com` rejected
  (parse with `java.net.URI`; GitHub URLs are plain, unlike Spotify's).
- `SignerCheck.kt`: `interface SignerSource { fun installed(): List<ByteArray>; fun archive(path: String): List<ByteArray>? }`,
  pure `verdict(installed, archive)` tested with byte arrays: equal sets pass, subset fails, empty fails,
  null archive fails, order-independent.
- `Sha256File`: streaming digest over a temp file vs a known vector.
- `UpdateDecision`: given installed version, latest release, `skipped_tag`, `last_check_ms`, `now`, `isDebug` →
  `Check/Skip/Offer`.

### 5.2 Manual end-to-end on a device (document the run in `docs/poc-results.md`)

Setup: a scratch public repo `p4u/openfy-updater-test` and a local test keystore. Build two **release** variants
with that keystore: `-PversionName=0.9.0` and `-PversionName=0.9.1 -PupdaterRepo=p4u/openfy-updater-test`
(the first also with `-PupdaterRepo=…`). Publish `0.9.1` as a GitHub Release on the scratch repo with the CI
workflow (dry-run-upload by hand: `gh release create v0.9.1 openfy-0.9.1.apk openfy-0.9.1.apk.sha256`).

1. `adb install openfy-0.9.0.apk`, open, menu → Check for updates → dialog shows 0.9.1 with notes and size.
2. Install → "install unknown apps" settings page → allow → back → download progress → system confirm sheet
   (expected: first update after a sideload) → app restarts on launch, toast `Updated to 0.9.1`, still logged in.
3. Publish `0.9.2` (built the same way), install via the app again: on Android 12+ **no** confirm sheet (openfy
   is now the installer of record); verify with `adb shell dumpsys package dev.openfy | grep -i installer`.
4. Negative cases, each must end in a clear dialog and no session: an APK signed with a different key (build
   `0.9.3` with the debug keystore and publish it); a checksum mismatch (the API `digest` always matches what
   GitHub stores, so exercise this path with a temporary debug-only Gradle property `-PupdaterExpectedSha=<wrong>`
   that overrides the expected hash, or rely on the unit test); a release with `v0.8.0` (older) → "up to date";
   a release with a `-rc` tag marked pre-release → not offered.
5. Playing a track, then installing: playback stops, notification disappears, app relaunches logged in.
6. Airplane mode → manual check → error dialog; automatic check → one log line, no UI.
7. Finally, on the real repo: publish `v0.1.0`, then `v0.1.1`, with the real key, and update a device from one to
   the other through the app. Record device, Android version, whether the confirm sheet appeared, and the time
   from "Install" to relaunch.
