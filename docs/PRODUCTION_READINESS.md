# Production Readiness Checklist

What's in place vs. what's still on the TODO before turning this loose
on a real customer fleet. Roughly priority-ordered within each section.

## In place ✅

- [x] All tunables config-driven via `RuntimeConfig`; cert-config API
      seam ready.
- [x] Probe-interface architecture — Ookla SDK swap is a one-day job.
- [x] 42-test unit + integration suite covering tier eval, headroom math,
      Wi-Fi quality logic, JSON payload shape, config validation, engine
      orchestration end-to-end with fake probes.
- [x] CI builds + tests on every PR; release pipeline produces signed
      APKs from `v*` tags.
- [x] Dependabot watches Gradle + GitHub Actions versions.
- [x] Backend contract pinned via git submodule; semver discipline.
- [x] Defensive `*.jks` / `*.keystore` gitignore so signing material
      can't accidentally land in the public repo.

## Before pilot launch ❗

These block any production use, not just polish.

- [ ] **Auth strategy decided + implemented.** See
      `docs/AUTH_STRATEGY.md`. App and backend both block on this.
- [ ] **PII policy decided.** What does the backend store raw vs. hash?
      `bssid`, `ssid`, `publicIp`, `gatewayIp`, `ethernetMac`, `hsn`.
      Default to hash-on-ingest unless legal explicitly clears the raw
      values.
- [ ] **TLS for the backend.** Real cert (Let's Encrypt or HWC's
      managed CA), not the trust-all hack the bandwidth probes use.
- [ ] **Backup strategy for the signing keystore.** Right now there's
      one local copy + GitHub Secrets. Add: 1Password attachment + a
      second person on the team holds a copy.

## Before customer support sees it 🟡

These don't block launch but make support 10× more effective.

- [ ] **Crash reporting.** Firebase Crashlytics or Sentry. Worth
      ~30 min of integration; surfaces field crashes that would
      otherwise be invisible. (Sentry is preferred — self-hostable, no
      Firebase BoM tax.)
- [x] ~~**Persistent result-publish queue.**~~ Done in v0.4.0
      (`publish/PublishQueue.kt` + Room `pending_publish` table). Drains
      on app launch and after every completed run, exits-on-transient-
      failure to avoid burning retries during a network outage, prunes
      rows that exhaust 8 attempts.
- [ ] **Server-side dashboard.** Grafana on the certifications table;
      panels for tier distribution, marginal-metric histogram, fleet
      Widevine-L1 ratio, thermal events. Out of scope for app team.
- [ ] **Server-side ingest validation.** Run incoming payloads through
      the OpenAPI schema; reject malformed requests with structured
      400s. Backend's job; mention in the kickoff prompt.

## Before fleet rollout (>1000 STBs) 🟢

Operational concerns that don't matter at pilot scale.

- [ ] **Rate limiting + abuse protection on the backend.** Per-device
      and per-IP buckets. A misbehaving STB shouldn't be able to flood
      `/v1/certifications`.
- [ ] **App-side telemetry on the publish step.** Track success /
      transient / permanent ratios as a histogram; Crashlytics custom
      keys are enough.
- [ ] **Server-side deletion path for HSN-targeted records.** Customer
      privacy requests will arrive eventually; build the API now.
- [ ] **Firebase App Distribution or Play Console internal testing
      track.** Replace the "tech installs the APK over ADB" flow with
      something that updates over the air.
- [ ] **CODEOWNERS + branch protection on `main`.** Required once a
      second engineer touches the repo.
- [ ] **Localization.** Strings live in a single `strings.xml` already.
      If FisionTV+ ever ships outside English-speaking markets, swap
      hardcoded "Run again" / "Headroom" / etc. in Compose for string
      resources.

## Things deliberately *not* on this list

- **Play Store publish.** Not the distribution path for STB software.
  If it ever becomes one, that's a small workflow add (`bundleRelease`
  + Play Publisher action).
- **Multi-language support.** Same as above — only matters if scope
  expands beyond US English.
- **In-app config editor.** Anti-pattern; the cert-config API is the
  control plane, not on-device knobs.
- **A/B testing framework.** Premature; the cert-config `weight` field
  on each server already lets you do simple traffic-shifting if needed.
