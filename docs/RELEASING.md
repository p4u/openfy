# Releasing openfy

Publishing a GitHub Release of `p4u/openfy` triggers `.github/workflows/release.yml`. It runs the tests, builds
and signs `openfy-<version>.apk`, checks the signature against the expected certificate and attaches the APK and
`openfy-<version>.apk.sha256` to that release. The spec is `docs/UPDATER.md` section 1; this file is the operator guide.

Nothing in this repository stores the signing key. The key lives in `~/.config/openfy/` on the maintainer's
machine and, as GitHub secrets, in the `release` environment.

## One-time setup

Run these once, from the maintainer's machine, with `gh` authenticated as a repository admin. The commands read
the keystore passwords from `~/.config/openfy/keystore.properties` without printing them (the `printf` is a shell
builtin, so the value never appears in a process list).

### 1. Create the `release` environment

```sh
gh api --method PUT repos/p4u/openfy/environments/release
```

Optional but recommended: require a reviewer, so that every run that can read the signing secrets needs a manual
approval. In the web UI: Settings -> Environments -> `release` -> Required reviewers -> add yourself. (A sole
maintainer can still approve their own runs; the gate protects against a compromised workflow change or a stray
dispatch, not against yourself.)

### 2. Add the four secrets to the environment

```sh
KS=~/.config/openfy/keystore.properties
prop() { sed -n "s/^$1=//p" "$KS"; }

base64 -w0 ~/.config/openfy/openfy-release.jks | gh secret set OPENFY_KEYSTORE_B64      --env release --repo p4u/openfy
printf '%s' "$(prop storePassword)"            | gh secret set OPENFY_KEYSTORE_PASSWORD --env release --repo p4u/openfy
printf '%s' "$(prop keyAlias)"                 | gh secret set OPENFY_KEY_ALIAS         --env release --repo p4u/openfy
printf '%s' "$(prop keyPassword)"              | gh secret set OPENFY_KEY_PASSWORD      --env release --repo p4u/openfy
```

Use environment secrets (`--env release`), not repository secrets: only jobs that declare `environment: release`
can read them, and the environment's protection rules apply.

### 3. Set the expected certificate fingerprint (a variable, not a secret)

The release certificate's SHA-256 is:

```
1D:B9:EE:4A:58:1C:20:D3:89:BD:DC:76:3D:27:C0:AF:03:63:C2:64:02:64:CF:FD:C3:44:4B:96:90:04:B2:63
```

```sh
gh variable set OPENFY_RELEASE_CERT_SHA256 --repo p4u/openfy \
  --body 1D:B9:EE:4A:58:1C:20:D3:89:BD:DC:76:3D:27:C0:AF:03:63:C2:64:02:64:CF:FD:C3:44:4B:96:90:04:B2:63
```

To re-derive it from the keystore (prompts for the store password):

```sh
keytool -list -v -keystore ~/.config/openfy/openfy-release.jks -alias openfy | grep 'SHA256:'
```

The workflow fails if the built APK is signed with any other certificate (for example a wrong keystore pasted into
the secret).

### 4. Dry run

Actions -> `release` -> Run workflow, version `0.0.1-rc.1` (any valid version). It does everything except publish:
the APK and checksum are kept as a workflow artifact for 7 days. A green dry run proves the keystore decodes, the
passwords are right and the fingerprint matches. Nothing is written to any release.

```sh
gh workflow run release.yml --repo p4u/openfy -f version=0.0.1-rc.1
```

## Cutting a release

Tags are `vX.Y.Z` (final) or `vX.Y.Z-rc.N` (pre-release). Nothing else is accepted. Limits: X <= 2000,
Y and Z <= 99, 1 <= N <= 98. The Android `versionCode` is `X*1000000 + Y*10000 + Z*100 + (final ? 99 : N)`, so
`v0.2.0-rc.1` (20001) < `v0.2.0-rc.2` < `v0.2.0` (20099) < `v0.2.1` (20199). `tools/version-code.sh v0.2.0` prints
the pair for any tag.

Final release (the in-app updater will offer this one to everyone):

```sh
gh release create v0.1.0 --repo p4u/openfy --target main \
  --title "openfy 0.1.0" --notes "First signed release."
```

Pre-release (the tag has `-rc.N` and the release must be marked pre-release; the workflow fails on a mismatch in
either direction). The updater ignores pre-releases (`releases/latest` skips them), so these are for sideloading:

```sh
gh release create v0.2.0-rc.1 --repo p4u/openfy --target main --prerelease \
  --title "openfy 0.2.0-rc.1" --notes "Release candidate."
```

Publishing the release starts the workflow (approve it if the environment requires a reviewer). A few minutes
later the release has `openfy-<version>.apk` and `openfy-<version>.apk.sha256`. Check:

```sh
gh release download v0.1.0 --repo p4u/openfy --dir /tmp/openfy-check
(cd /tmp/openfy-check && sha256sum -c openfy-0.1.0.apk.sha256)
apksigner verify --print-certs /tmp/openfy-check/openfy-0.1.0.apk
```

If the run fails, fix the cause, then either re-run the failed workflow run (the upload uses `--clobber`) or delete
the release and tag and publish again. A failed run never uploads an unsigned or wrongly signed APK: the uploads are
the last steps, after all checks.

The workflow builds the commit the tag points at. Do not publish a release for a tag whose commit has not passed
CI on `main`.

## Local release builds

```sh
source tools/env.sh
./gradlew assembleRelease -PversionName=0.1.0 -PrequireSigning=true   # app/build/outputs/apk/release/app-release.apk
```

Signing configuration is resolved in this order, first hit wins:

1. env vars `OPENFY_KEYSTORE_FILE`, `OPENFY_KEYSTORE_PASSWORD`, `OPENFY_KEY_ALIAS`, `OPENFY_KEY_PASSWORD`
   (all four required once any is set);
2. the properties file `$OPENFY_KEYSTORE_PROPERTIES`, default `~/.config/openfy/keystore.properties`
   (keys `storeFile` (absolute path), `storePassword`, `keyAlias`, `keyPassword`);
3. nothing: the release APK is left unsigned, unless `-PrequireSigning=true` is set, which fails the build.

Without `-PversionName` the build is `0.0.0-dev` / versionCode 1. APKs are signed with v2 and v3 only (no v1), so
`keytool -printcert -jarfile` does not work on them; use `apksigner verify --print-certs`.
`./gradlew :app:checkVersionVectors` and `tools/version-code.sh --selftest` check the version formula against the
spec vectors.

## Keystore: back it up, and never commit it

- **Back up `~/.config/openfy/openfy-release.jks` and its passwords offline now**, in at least two places (for
  example an encrypted USB stick and a password manager). The Android package manager only accepts an update signed
  by the same key as the installed app. **If the key is lost, every installed copy must be uninstalled (losing app
  data) before a differently signed build can be installed, and the in-app updater cannot deliver the next update.**
- The key is valid for 10000 days and cannot be rotated in v1.
- If the key leaks, anyone can publish an APK that existing installs accept. Delete the GitHub secrets, tell users,
  and ship a new package name or a new key with a manual reinstall.
- `*.jks`, `*.keystore` and `keystore.properties` are in `.gitignore`. Never copy them into the repository, an issue
  or a chat. The workflow decodes the keystore to `$RUNNER_TEMP` (outside the checkout) and removes it in a final
  `always()` step.
- The one-time key creation command, for reference (do not run it again unless you intend to replace the key):

  ```sh
  keytool -genkeypair -v -keystore openfy-release.jks -storetype PKCS12 -alias openfy \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=openfy, O=openfy"
  ```

## Bumping pinned actions

All actions in the workflow are pinned to full commit SHAs with the version in a comment. Dependabot
(`.github/dependabot.yml`) opens pull requests for bumps. To resolve a tag by hand (annotated tags need the second
call):

```sh
gh api repos/actions/checkout/git/ref/tags/v7.0.1 --jq '.object'
gh api repos/<owner>/<repo>/git/tags/<sha-from-above> --jq '.object.sha'   # only if .object.type is "tag"
```
