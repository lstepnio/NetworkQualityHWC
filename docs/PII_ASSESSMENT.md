# PII / Data Handling Assessment — FisionTV+ Network Certifier

**Purpose**: provide HWC SecOps with a complete inventory of personally
identifying data the FisionTV+ Network Certifier collects, sends, and
stores; the data flow end-to-end; and the open policy questions that
need a SecOps decision before pilot launch.

Companion sample payloads live in `docs/sample-payloads/`.

**Status**: pre-pilot. Backend is being built; PII handling on the
server side has placeholders pending this assessment. The Android client
collects everything described below today and stores it locally; nothing
is transmitted yet because `resultsPublishing.enabled` defaults to
`false` (a kill switch flippable via the cert-config API).

---

## 1. Executive summary

The certifier produces ~6 KB of structured JSON per run. Roughly half is
non-identifying network metrics (throughput, latency, DNS times). The
other half includes a mix of **direct identifiers** (HSN, hardware
serial, public IP, gateway IP, private IP), **quasi-identifiers** (Wi-Fi
band/frequency/standard, display capabilities, codec inventory, locale,
timezone), and **operational diagnostics** (boot reason, uptime, thermal
state).

We need SecOps to decide:

1. Which fields are stored raw vs hashed at the backend boundary.
2. Whether `bssid` / `ssid` (currently always `null` because we don't
   request `ACCESS_FINE_LOCATION`) should be enabled in a future
   system-privileged build.
3. Retention period for stored certifications.
4. Whether CPNI / customer-data deletion APIs need building now or later.
5. Encryption-at-rest requirements for the Postgres store.

The architecture is designed so the answer to (1) plumbs in cleanly:
hashing happens at the server boundary on ingest, never on the client.
Apps in the field don't need to update for a policy change.

---

## 2. Data inventory

Each row classifies a field by sensitivity (H/M/L), where it's
collected, where it goes, and any framework relevance.

### 2.1 High sensitivity — direct identifiers

| Field | Source | On device | Wire | Server | Notes |
|---|---|---|---|---|---|
| `identity.hsn` | `ro.product.hsnt` | logcat, history DB, queue DB | HTTPS | Postgres | **Hotwire Serial Number** — links a STB to a customer account; CPNI-adjacent |
| `identity.hardwareSerial` | `ro.serialno` / `Build.getSerial()` | same | HTTPS | Postgres | Often equals HSN on HWC hardware; not contractual |
| `identity.deviceId` | App-generated UUID v4, persisted in `SharedPreferences` | app private storage | HTTPS | Postgres | Not derived from any hardware ID; opaque to anyone outside this app |
| `identity.ethernetMac` | `/sys/class/net/eth0/address` | same | HTTPS (when readable) | Postgres | **Currently always `null`** — sysfs blocked except on system-privileged image |
| `identity.wifiMac` | `/sys/class/net/wlan0/address` | same | HTTPS (when readable) | Postgres | **Currently always `null`** — same reason |
| `network.publicIp` | server-side derivation (planned) OR client probe | logcat | HTTPS | Postgres | Geolocates the home; ISP-linked |

### 2.2 Medium sensitivity — quasi-identifiers / household fingerprint

| Field | Source | Notes |
|---|---|---|
| `network.privateIp` | `LinkProperties` | Internal network mapping; identifying if the LAN topology is unique |
| `network.gatewayIp` | `LinkProperties` | Same |
| `network.dhcp.serverAddress` / `gateway` / `ipAddress` / `dns1`/`dns2` | `WifiManager.getDhcpInfo()` | DHCP server + DNS pair fingerprints the customer's CPE |
| `wifi.ssid` | `WifiInfo.getSSID()` (gated on `ACCESS_FINE_LOCATION`) | **Currently always `null`** — permission not requested. Identifies the household by network name |
| `wifi.bssid` | `WifiInfo.getBSSID()` (same gate) | **Currently always `null`**. AP MAC, geolocatable via Apple/Google wardriving DBs |
| `wifi.frequencyMhz` / `band` / `standard` | `WifiInfo` | AP/STB combo fingerprint |
| `capabilities.display.*` (resolution, refreshRate, supportedModes, hdrTypes) | `DisplayManager` | TV model fingerprint |
| `capabilities.videoCodecs[]` | `MediaCodecList` | STB hardware fingerprint (~17 entries on Amlogic STBs) |
| `capabilities.drm.widevineSystemId` / `widevineVersion` | `MediaDrm` | Widevine instance identifier; per-device but stable |
| `capabilities.locale.*` | `Locale.getDefault()`, `TimeZone.getDefault()` | Timezone alone narrows household to a region |
| `device.buildFingerprint` | `Build.FINGERPRINT` | Identifies firmware build, not specific device |

### 2.3 Low sensitivity — operational / behavioral

| Field | Notes |
|---|---|
| `metrics.download.*` / `metrics.upload.*` / `metrics.latency.*` / `metrics.dns.*` | Network performance only; not identifying alone |
| `metrics.playback.*` | Streaming behaviour metrics |
| `result.*` (achievedTier, headroom, breakdown) | Derived signals |
| `capabilities.thermal` / `memory` / `storage` / `power` / `bootReason` | Operational diagnostics |
| `device.uptimeMs` / `capabilities.bootTimeEpochMs` | Stability signals |
| `wifi.rssiDbm` / `linkSpeedMbps` / `signalLevel` | Link quality |

### 2.4 What we deliberately do **not** collect

- **GPS / location coordinates.** Useless on a fixed STB; would require `ACCESS_FINE_LOCATION` and is a privacy minefield.
- **Full traceroute / hop list.** Android blocks raw ICMP without root.
- **App inventory / running-process list.** Restricted by Android since API 30.
- **Browsing or streaming history outside the cert run.** Not in scope.
- **Personal account credentials.** The app has no login.
- **Wi-Fi MCS index** (per modulation symbol). Not exposed to apps.

---

## 3. Data flow

```
┌───────────────────────────────────────────────────────────────────────┐
│                          STB (Android TV)                             │
│                                                                       │
│   CertificationEngine ──▶ NetworkDiagnosticsCollector                 │
│           │                        │                                  │
│           ▼                        ▼                                  │
│   CertificationResult  ◀── reads /sys/class/net (MAC, blocked)        │
│           │                                                           │
│           ├──▶ logcat  (TAG: NetDiagnostics, CertPayload)             │
│           │              full payload, debug builds only              │
│           │                                                           │
│           ├──▶ Room: history (local-only, denormalized columns)       │
│           │                                                           │
│           └──▶ Room: pending_publish (full JSON, drained over HTTPS)  │
│                          │                                            │
└──────────────────────────┼────────────────────────────────────────────┘
                           │ HTTPS POST /v1/certifications
                           │ (real CA chain, bearer auth)
                           ▼
┌───────────────────────────────────────────────────────────────────────┐
│                          Backend (TBD)                                │
│                                                                       │
│   API gateway ──▶ ingest validator ──▶ [redaction policy] ──▶ DB      │
│                         │                       │                     │
│                         │                       └── recommended:      │
│                         │                          hash hsn,          │
│                         │                          serialNumber,      │
│                         │                          publicIp,          │
│                         │                          bssid, ssid        │
│                         │                          before persist     │
│                         ▼                                             │
│              GET /v1/certifications/{id}  (support tooling)           │
└───────────────────────────────────────────────────────────────────────┘
```

### 3.1 On-device storage

- **App-private app data** under `/data/data/com.hotwire.fisiontv.networkqual/`.
  Not readable by other apps without root.
- **Two SQLite tables** in `fisiontv-nq.db`:
  - `history` — denormalized cert results, no full payload. Local-only.
  - `pending_publish` — holding queue with full JSON payload, deleted on
    successful POST or after 8 failed attempts.
- **`SharedPreferences`** stores the per-install `deviceId` UUID.
- **No encryption at rest on device today.** Standard Android app data
  is protected by the OS user-private boundary; no FBE/FDE override.
  Acceptable for an STB locked behind a customer's home; revisit if
  hardware is ever in a public/shared location.

### 3.2 Wire transport

- **HTTPS only.** The publisher uses a stock OkHttp client with default
  trust managers; cert chain validated against system CA store. Distinct
  from the bandwidth-probe client which deliberately skips chain
  validation against speedtest endpoints (separate code path,
  `cert/probes/internal/Insecure.kt`, never used for data-carrying
  traffic).
- **Bearer auth.** Strategy not yet finalized — see `AUTH_STRATEGY.md`.
  Default proposal is per-install token issued by `POST /v1/devices/register`.
- **No PII in URL or query string.** All identifying fields are in the
  request body or in the `X-Device-Id` header.
- **Idempotent.** `certificationId` UUID is the dedupe key; the same
  payload can be sent multiple times safely.

### 3.3 Logging surface

- **logcat on device.** Debug builds emit the full JSON payload under
  tag `CertPayload`. **Release builds also emit this** today; recommend
  gating it behind `BuildConfig.DEBUG` before pilot.
- **No Crashlytics / Sentry yet.** When added, scrub identity fields
  from breadcrumbs.
- **GitHub Actions logs**: build/test pipelines run against synthetic
  fixtures — no real customer data lands here.

---

## 4. Frameworks and obligations

These are HWC's call, not the app team's. Listed so SecOps can confirm
they've been considered:

- **47 CFR §222 (CPNI)** — "Customer Proprietary Network Information"
  for US telecom carriers. Customer usage data of telecom services. The
  HSN + tier-certification record likely qualifies. Implications:
  retention policy, customer right of access, breach-notification rules.
- **GDPR** — applies if any EU customer touches the system. Not
  expected for a US-regional ISP, but if HWC has international staff
  who'd run the certifier on their own STBs, scope it.
- **California CCPA / CPRA** — California-resident customers have
  deletion rights regardless of where HWC is HQ'd.
- **PCI** — n/a, no payment data here.
- **HIPAA** — n/a.

---

## 5. Open questions — please decide

### 5.1 What gets hashed at the backend boundary?

| Field | Recommendation |
|---|---|
| `identity.hsn` | **Hash with HMAC-SHA256, server-side pepper.** Keep the hash for fleet-correlation queries; lose the raw value on ingest. Inverse-lookup at customer-care request rather than on every record. |
| `identity.hardwareSerial` | Same as HSN. |
| `network.publicIp` | **Don't store the literal IP.** Derive ASN + city-resolution server-side, store those instead. |
| `network.gatewayIp` / `dhcp.serverAddress` | RFC1918 addresses only — store raw, low risk. |
| `wifi.bssid` / `wifi.ssid` | Currently null. **If ever enabled, hash both.** |
| Everything else | Store as-is. |

### 5.2 Retention period

- **Recommended**: 18 months for full payloads, 5 years for the
  denormalized columns (achieved tier, marginal metric, transport,
  device_id) for trend analysis.
- **Right to deletion**: implement `DELETE /v1/certifications?hsn=...`
  early; cheaper than retrofitting.

### 5.3 Wi-Fi MAC / SSID / BSSID — collect or never?

The fields exist in the schema (always `null` today) so a future
system-privileged build *could* populate them. SecOps decision:

- **Recommend collect** if HWC's deployment image grants location
  permission to system apps and the values are hashed at backend
  ingest.
- **Recommend never** if there's any uncertainty.

The schema field stays either way — null is benign. The decision
affects one Manifest permission in a later build.

### 5.4 Encryption at rest

- **App-side**: standard Android app-private storage. Recommend
  switching to `EncryptedSharedPreferences` for the deviceId only.
- **Backend**: Postgres TDE or volume-level encryption. SecOps to
  specify which.

### 5.5 Logging policy

- Gate the `CertPayload` JSON log behind `BuildConfig.DEBUG` before
  pilot. Acceptable for ad-hoc tech-on-site debugging only.
- Or: scrub identity / publicIp / dhcp from the logged JSON, keep the
  rest. Less useful for support, more privacy-conservative.

---

## 6. Sample payloads

Three files in `docs/sample-payloads/`, all derived from a real run on
the lab STB (with identifying values replaced by realistic placeholders):

1. **`certification-post-raw.json`** — what the app currently builds and
   would POST to `/v1/certifications`. Shows every field as collected,
   nothing redacted. **This is what SecOps reviews to make redaction
   decisions.**

2. **`certification-post-stored-recommended.json`** — what we recommend
   the backend persists after applying §5.1 redactions. Same shape, but
   `hsn` / `hardwareSerial` are SHA-256 hex digests, `publicIp` is
   replaced with `{asn, city}`. Send this to anyone asking "what
   actually lives in our database?"

3. **`cert-config-response.json`** — what the backend serves on
   `GET /v1/cert-config`. **Contains no PII** — just config values for
   the app to consume. Included for completeness.

---

## 7. Quick reference — what to point a SecOps reviewer at

- **This document** for the high-level inventory + flow.
- **`contract/openapi.yaml`** (in the
  [contract repo](https://github.com/lstepnio/fisiontv-cert-contract))
  for the precise schema with field types and required/optional flags.
- **`docs/sample-payloads/`** for representative data.
- **`docs/AUTH_STRATEGY.md`** for the auth approach (relevant to
  ingest-side access controls).
- **`app/src/main/java/.../diagnostics/`** for the on-device collection
  source if they want to verify what we're saying we collect matches
  what the code actually reads.

Decisions go into a follow-up `PII_DECISIONS.md` (not yet written —
populated after SecOps review). Both client and server pin to those
decisions; future code-review discipline catches drift.
