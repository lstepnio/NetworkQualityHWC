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

### Per-change loop

```bash
# 1. Make your code change.
# 2. Rebuild + bump versionCode + serve.
python3 scripts/dev-update-server.py --bump

# 3. On the STB:
#    - relaunch the app (or wait — manifest fetch runs in AppContainer.init)
#    - "Update & run" appears (forced, because minRequired = latest)
#    - tap it → progress → system "Install update?" dialog → tap Install
#    - app relaunches → cert auto-starts
#
# 4. iterate.
```

The script:
- builds `app/build/outputs/apk/debug/app-debug.apk` with the bumped
  `versionCode` / `versionName` injected via env (`FISION_VERSION_CODE`,
  `FISION_VERSION_NAME`)
- computes `apkSha256` over the bytes and `signingCertSha256` from the
  APK signature block (via `apksigner` if present, else `keytool`)
- stages a serve tree at `scripts/.serve-root/` shaped like the
  production API:
  - `v1/app/version` — the manifest JSON
  - `v1/app/download/app-debug-<versionCode>.apk` — the APK
- detects the LAN IP for the manifest's `apkUrl` so the STB can reach it
- runs a tiny `http.server`-based listener on the same port the debug
  build is hardcoded to poll (`18080` — chosen to avoid the very
  common `8080` clash with Docker/OrbStack)

### Flags

| Flag              | Default     | What it does |
|-------------------|-------------|--------------|
| `--bump`          | _off_       | Bump versionCode, rebuild, then serve. Use this every iteration. |
| `--serve-only`    | _off_       | Skip the Gradle build; serve whatever's already in `app/build/outputs/`. Useful if you've just built via Android Studio. |
| `--optional`      | _off_       | Manifest's `minRequired` stays at 1 so the cert button isn't blocked. Default is forced (`minRequired = latest`) which exercises the "Update & run" path. |
| `--host <ip>`     | auto-detect | Override the LAN IP baked into `apkUrl` if auto-detect picks a weird interface (VPN, multiple NICs). |
| `--port <p>`      | `18080`     | Listen port. Match the debug build's `BuildConfig.APP_UPDATE_URL`. |
| `--notes <str>`   | `dev build` | `releaseNotes` shown in the STB UI banner. |

### State

`scripts/.dev-update-state.json` tracks the most recent versionCode so
`--bump` is monotonic across sessions. Delete it to reset to `1001`.

### Networking notes

- The debug build's `APP_UPDATE_URL` is hardcoded to
  `http://192.168.10.233:18080/v1/app/version` in `app/build.gradle.kts`.
  If your dev Mac's LAN IP isn't 192.168.10.233, change that value (and
  update `network_security_config.xml` if needed) or run the script
  with `--host` matching the build config.
- The cert is **debug-keystore-signed** so `BuildConfig.APP_SIGNING_CERT_SHA256`
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

**"versionCode mismatch".** The script bumped to a code that's lower
than what's already installed (e.g., you wiped state). Edit
`scripts/.dev-update-state.json` to set `code` higher than the installed
version, or do another `adb install -r` with a higher code.
