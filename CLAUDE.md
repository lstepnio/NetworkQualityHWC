# CLAUDE.md — NetworkQualityHWC (Android STB client)

## Role

Kotlin/Compose Android TV client for the FisionTV+ Network Certifier. Installed on Hotwire set-top boxes; runs a full network certification on user tap (speed, latency, jitter, packet loss, playback probe, HDR/Widevine inspection) and publishes the result to the backend.

Self-updates via `GET /v1/app/version` + PackageInstaller. Tracks the dashboard's "Active" app-version manifest; downloads + integrity-checks + installs on every cert tap when a newer versionCode is available.

Brand modules:
- **Ookla speedtest** — embedded bundled binary, fronts the upload/download phases.
- **Media3** — playback probe (DASH manifest fetch + ABR ladder check).
- **PackageInstaller** — self-update install flow.

## Neighbors

This repo is one of four in the FisionTV+ system. All checked out under `/Users/lukasz.stepniowski/Development/`:

- **contract** — `fisiontv-cert-contract` — OpenAPI 3.1 + SPEC.md. Vendored here as the `contract/` git submodule, **pinned to an exact tag** (currently `v1.2.0`). The backend's pin must match.
- **backend** — `NetworkQualityHWCBackend` — Go server. Receives our POSTs at `/v1/certifications` and serves `/v1/cert-config` + `/v1/app/version`.
- **dashboard** — `NetworkQualityHWCDashboard` — SvelteKit 5 admin UI. The user manages app-version manifests there; what they activate is what we self-update to.

## Local dev

- **Build**: `./gradlew assembleDebug`. Output at `app/build/outputs/apk/debug/app-debug.apk`.
- **Release build (signed)**: `./gradlew assembleRelease` — needs `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` env vars. Normally only CI does this.
- **Run tests**: `./gradlew test`.
- **Install to lab STB**: `adb -s 192.168.10.189:5555 install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Logs**: `adb -s 192.168.10.189:5555 logcat -s NetworkQual:* CertEngine:* AppUpdateInstaller:* AppUpdateClient:* CertConfigClient:*`.
- **Inspect installed version**: `adb -s 192.168.10.189:5555 shell pm dump com.hotwire.fisiontv.networkqual | grep -E 'versionCode|versionName|signatures'`.

The debug build points at `http://192.168.10.233:18080/v1/...` (the dev Mac's Docker port-map). The release build points at `https://certifier-api.gethotwired.com/v1/...`. See `app/build.gradle.kts` `buildConfigField`s.

## Conventions

- **PR-only.** Sandbox blocks direct push to `main`. Workflow: `git checkout -b <type>/<name>` → commit → push → `gh pr create` → wait for CI green → `gh pr merge --merge --delete-branch` → fast-forward main → tag if releasing.
- **Conventional Commits.** `feat:`, `fix:`, `ci:`, `chore:`, `docs:`, `refactor:`, `test:`, `ui:` (for UI-only changes).
- **Contract pin discipline.** `git -C contract describe --tags --exact-match` must return a clean tag (no `-N-gSHA` suffix). CI enforces in `build.yml`. Backend + android pins must match.
- **No screen recording / no PII in logcat.** The STB is on Hotwire's lab LAN; PII handling rules from the backend still apply on the client side (don't log SSIDs, public IPs, MACs).

## Release flow

1. Open a PR with the change.
2. CI green → merge → fast-forward `main`.
3. `git tag -a vX.Y.Z -m "vX.Y.Z: <one-line>" HEAD && git push origin vX.Y.Z`.
4. The `release` workflow builds a signed release APK with **versionCode = 1000 + git rev-list --count HEAD**. Why the +1000: see the project memory on "Android versionCode +1000 offset" — it lifts the CI-driven track above the 100-series codes used during self-update bring-up.
5. APK is uploaded to the GitHub Release. Download URL: `https://github.com/lstepnio/NetworkQualityHWC/releases/download/vX.Y.Z/fisiontv-network-certifier-vX.Y.Z.apk`.
6. To roll it out to STBs: create + activate an app-version manifest in the dashboard pointing at the APK URL. See "Signing-cert policy" memory before doing this.

## Signing-cert policy (load-bearing)

- **Release builds** use the FisionTV keystore: `CN=FisionTV Network Certifier`, cert SHA-256 `fc87ab132146b369684f0cd6b0a2c08cd854c47aa35e31b52806de476230c9aa`.
- **Debug builds** use the stock Android Debug keystore: `CN=Android Debug`, cert SHA-256 `be0fae89e04e5c71660273c4084ed0c98879f567fe13b6d15f5df9b243586f87`.
- Android refuses to install an APK whose signing cert differs from the installed app's (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Upgrading across the two tracks **requires uninstall** (loses `device_id`, queue).
- `AppUpdateInstaller.kt` also pins the cert via compile-time `BuildConfig.APP_SIGNING_CERT_SHA256`. Empty in debug builds (no client-side enforcement); set to the release cert in release builds.

## Self-update pipeline (mental model)

`AppContainer.kt` wires `OkHttpAppUpdateClient` → `AppUpdateInstaller`. On every cert tap:

1. Fetch `/v1/app/version` (with `If-None-Match` for 304).
2. Parse manifest. Compare `latestVersionCode` to installed.
3. If newer → download APK (with progress), verify SHA-256 matches manifest, verify signing cert SHA-256 against compile-time pin + manifest value.
4. Hand off to `PackageInstaller.Session.commit()` (background) or user-prompted install.
5. After install, `MainActivity` is re-launched at the new versionCode.

If anything fails (parse, hash mismatch, install rejection) → `AppUpdateFetchOutcome.Error` or installer state machine surfaces error; the cert run proceeds anyway on the installed version. Don't block the cert on update failure.
