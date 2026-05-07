# FisionTV+ Network Certifier

Android TV (Leanback) app that certifies a customer broadband connection
against streaming-quality tiers (SD / HD / 4K / 4K HDR) for Hotwire
Communications' FisionTV+ product.

A run measures DNS resolution, server selection RTT, latency + jitter,
sustained download / upload throughput, and real DASH playback against the
fastest reachable HWC Ookla speedtest server. The result is shown on screen
with a headroom indicator, a Wi-Fi link quality advisory (when applicable),
and the full per-tier pass/fail breakdown. The same data is serialized as a
backend-spec JSON payload ready to POST to `/v1/certifications` once the
contract API is live.

## Architecture

```
config/                ← All tunables. RuntimeConfig + RuntimeConfigDefaults.
                         Designed to be replaced by a remote-fetched copy
                         from /v1/cert-config without an APK push.
cert/                  ← Domain types + orchestration only. No transport code.
cert/probes/           ← Probe interfaces and concrete implementations.
                         Engine talks only to the interfaces — to swap to
                         the Ookla SDK, write new implementations and edit
                         ProbeFactory.
diagnostics/           ← Device, network, Wi-Fi, capability collection.
                         No new permissions beyond what the manifest declares.
ui/                    ← Compose for TV.
data/                  ← Room history (local-only; future: results POST).
docs/BACKEND_API_SPEC  ← Contract the backend implements. Read this first
                         before touching the payload schema.
```

## Building locally

Requires JDK 17 (Android Studio's bundled JBR works).

```bash
# Debug APK (sideload-friendly, no signing setup needed):
./gradlew assembleDebug

# Release APK locally (without the production keystore):
# falls back to debug signing automatically.
./gradlew assembleRelease
```

Output lands at `app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`.

If `JAVA_HOME` isn't set, point it at Android Studio's bundled JBR:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Sideloading to a STB

```bash
adb connect <stb-ip>:5555
adb -s <stb-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

The app will appear in the Leanback launcher with the pink Fision tile.

## Releasing

Releases are tag-driven and produce a signed APK attached to a GitHub
Release. Trigger one by tagging:

```bash
git tag v0.2.0
git push origin v0.2.0
```

GitHub Actions runs `.github/workflows/release.yml`, which:

1. Decodes the release keystore from repo secrets.
2. Derives `versionName` from the tag and `versionCode` from
   `git rev-list --count HEAD`.
3. Builds a signed release APK.
4. Creates a GitHub Release named after the tag with auto-generated notes.
5. Attaches the APK as `fisiontv-network-certifier-<tag>.apk`.

The keystore lives in GitHub Secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`). It's never committed and never echoed
into logs. Losing it means losing the ability to publish updates to
existing installs — keep an offline backup.

## Per-PR CI

Every PR and push to `main` runs `.github/workflows/build.yml`:

- `./gradlew assembleDebug` + `./gradlew lint`
- Lint reports are uploaded as workflow artifacts.

## Dependencies

Dependabot opens grouped PRs weekly for Gradle deps and monthly for GitHub
Actions versions. See `.github/dependabot.yml`.

## Backend contract

The backend that consumes results lives in a separate project. The schema
both ends agree on is checked into a third repository — see
`docs/BACKEND_API_SPEC.md` for the human-readable narrative and the
contract submodule (added separately) for the OpenAPI source of truth.
