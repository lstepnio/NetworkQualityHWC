# Dev scripts

## `dev-update-server.py`

Local stand-in for the production `/v1/app/version` endpoint, so STB
iteration runs through the **same self-update code path** the field
will use — no more `adb install` per change.

### One-time bootstrap

The very first install of the self-update-aware client still needs ADB
(because the version running on the STB has to know how to fetch the
manifest in the first place). After that, ADB is for logcat only.

```bash
# 1. Build any commit on main that contains the update module.
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    ./gradlew assembleDebug

# 2. Install once.
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Workflow

Two long-lived shells, one role each:

```bash
# Shell A — start once, leave running for the session.
python3 scripts/dev-update-server.py --no-build

# Shell B — run once per code change. Builds, bumps versionCode,
# writes the new manifest + APK into the serve root, then exits.
# Shell A's running listener picks the new files up on the next GET
# from the STB. No restart, no port-fight.
python3 scripts/dev-update-server.py --no-serve --bump
```

Then on the STB: tap **Run certification**. The pre-cert update check
fetches the new manifest, downloads the APK, the system **Install update?**
dialog appears, tap **Install**, the app relaunches on the new version,
and the cert auto-resumes.

The one-shot mode is still available for bring-up:

```bash
# Builds + serves in a single process — convenient for the very first
# run of a new session. To iterate after this you'd have to pkill and
# restart, which is the wart that --no-build / --no-serve fixes.
python3 scripts/dev-update-server.py --bump
```

### What it does

- Builds `app/build/outputs/apk/debug/app-debug.apk` with the bumped
  `versionCode` / `versionName` injected via env (`FISION_VERSION_CODE`,
  `FISION_VERSION_NAME`).
- Computes `apkSha256` over the bytes and `signingCertSha256` from the
  APK signature block (via `apksigner` if present, else `keytool`).
- Stages a serve tree at `scripts/.serve-root/` shaped like the
  production API:
  - `v1/app/version` — the manifest JSON
  - `v1/app/download/app-debug-<versionCode>.apk` — the APK
- Detects the LAN IP for the manifest's `apkUrl` so the STB can reach it.
- Runs a tiny `http.server`-based listener on port `18080`. Match the
  debug build's hardcoded `APP_UPDATE_URL`; the port is intentionally
  off the very common `:8080` to avoid Docker/OrbStack clashes.

### Versioning scheme

Aligned with the dashboard's release catalog so the dev-script's
output reads as if it could have come from the dashboard itself:

| versionCode | versionName  | Source |
|-------------|--------------|--------|
| 100         | `0.1.00-dev` | dashboard |
| 101         | `0.1.01-dev` | dashboard |
| 102         | `0.1.02-dev` | dashboard |
| 103         | `0.1.03-dev` | dashboard (last released) |
| 104         | `0.1.04-dev` | first `--bump` from this script |
| 105         | `0.1.05-dev` | next `--bump` |
| ...         | ...          | ... |

When the real backend's `/v1/app/version` endpoint ships, dev-iteration
artifacts are visually indistinguishable from real releases, so the
hand-off doesn't require people to relearn how to read version strings.

### Flags

| Flag             | What it does |
|------------------|--------------|
| `--bump`         | Bump versionCode before this publish. |
| `--no-build`     | Skip the Gradle build + manifest write — just run the listener over the current serve root. Use for the long-running serve shell. |
| `--no-serve`     | Build + write the manifest + APK, then exit. Use to push a new version against an already-running `--no-build` server. |
| `--serve-only`   | Deprecated alias for `--no-build`. |
| `--optional`     | Sets `minRequired = 1` so the cert isn't gated on this release. Default is forced (`minRequired = latest`). |
| `--host <ip>`    | Override the LAN IP baked into `apkUrl` if auto-detect picks the wrong interface. |
| `--port <p>`     | Listen port. Defaults to `18080`. Must match the build's `BuildConfig.APP_UPDATE_URL`. |
| `--notes <str>`  | `releaseNotes` string in the manifest. |

### State

`scripts/.dev-update-state.json` tracks the most recent versionCode so
`--bump` is monotonic across sessions. Delete it to start over at 104.

If the state file still holds a code in the legacy 4-digit band
(e.g. `1003` from before the dashboard alignment), the next `--bump`
resets to `104` and the STB will need one `adb install -r -d <apk>`
to cross the band (the OS rejects normal installs that lower the
`versionCode`).

### Networking notes

- The debug build's `APP_UPDATE_URL` is hardcoded to
  `http://192.168.10.233:18080/v1/app/version` in `app/build.gradle.kts`.
  If your dev Mac's LAN IP isn't 192.168.10.233, change that value (and
  update `network_security_config.xml` if needed) or run the script
  with `--host` matching the build config.
- The APK is **debug-keystore-signed** so `BuildConfig.APP_SIGNING_CERT_SHA256`
  is empty (the compile-time pin is skipped). The manifest's
  `signingCertSha256` still has to match the APK's actual signature; the
  script computes both from the same APK so they match by construction.
- The script doesn't serve `/v1/cert-config` — that's a separate
  concern. The app falls back to bundled defaults if cert-config 404s,
  which is fine for dev iteration on the update flow itself.

### Troubleshooting

**STB never sees the update.** Confirm the STB can reach the dev Mac:
```bash
adb shell curl -v http://192.168.10.233:18080/v1/app/version
```
If that fails, the STB and dev Mac aren't on the same subnet, or the
debug build's URL doesn't match your Mac's IP.

**"signing cert mismatch" in logcat.** Probably a debug-keystore change
between bootstrap install and dev-server build. Reinstall the bootstrap
APK and try again.

**"downgrade refused" / "versionCode mismatch".** The script bumped to a
code lower than what's installed (likely the legacy → aligned scheme
transition). Use `adb install -r -d <apk>` once to allow the downgrade,
then subsequent monotonic bumps work normally.
