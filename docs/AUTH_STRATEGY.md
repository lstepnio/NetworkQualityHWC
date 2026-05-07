# Auth Strategy — Decision Document

The contract has placeholder auth (`NoAuthProvider` returns null). Real
auth needs to be picked before the backend goes to production.
This document lays out the two viable options with concrete tradeoffs so
HWC can sign off and we can ship.

## Option A — Per-install bearer token

Once on first launch, the app calls `POST /v1/devices/register` with its
generated `deviceId` (UUID) and gets back a long-lived bearer token. Token
goes in `Authorization: Bearer <token>` on every subsequent call.

**Implementation:**

| Side | Work |
|---|---|
| Android | One-time call on first launch; persist token in SharedPreferences (encrypted via Android Keystore). |
| Backend | New endpoint `/v1/devices/register`; token issuance + revocation table; per-token rate limit. |
| Contract | New endpoint added in v1.1.0 of `openapi.yaml`. |

**Pros:**

- Per-device revocation. Compromised STB → revoke its token, rest of
  fleet unaffected.
- Tokens never live on disk in the source tree or build pipeline.
- Standard pattern; auditable.
- Future-proof for adding scopes (admin tokens, read-only tokens).

**Cons:**

- Slightly more backend work (an extra endpoint, an extra table).
- First-launch needs network connectivity to register.
- Token recovery flow needed if SharedPreferences is wiped (re-register
  with same `deviceId` — server returns the same or a fresh token).

## Option B — HMAC + shared secret

A secret is baked into the APK at build time. Client signs each request
with `HMAC-SHA256(secret, deviceId + timestamp + body)`. Server verifies
the signature plus a 5-minute timestamp window.

**Implementation:**

| Side | Work |
|---|---|
| Android | Read secret from `BuildConfig` (injected from CI), compute HMAC per request, attach as header. |
| Backend | Verify HMAC; reject stale timestamps. |
| Contract | No new endpoint; spec just notes the auth header format. |

**Pros:**

- Faster to ship. No new endpoint.
- Stateless — no token table on the server.
- No first-launch network requirement.

**Cons:**

- **The secret is on every customer's STB.** One reverse-engineer with
  apktool extracts it, and they can mint requests forever.
- Rotation is painful: rotating the secret requires a coordinated app
  update; old clients with the old secret are locked out instantly.
- No per-device revocation. Compromised? Rotate the global secret →
  every STB has to update.
- Replay window — a captured request replays for 5 minutes.

## Recommendation

**Option A.** Worth the extra endpoint to get per-device revocation and
rotation that doesn't require a fleet-wide app update. The "first launch
needs network" concern doesn't matter for an STB that's wired into a
customer's home and online before it ever opens this app.

Use Option B only if:
- The pilot fleet is < 50 STBs and the lifetime is months, not years.
- HWC's security policy forbids a token store on the backend (unusual).

## Decision

- [ ] Option A — Per-install bearer token
- [ ] Option B — HMAC + shared secret
- [ ] Other (document below)

**Decided by**: _(name + date)_

**Once decided, the work to do:**

1. Update `contract/openapi.yaml` with the chosen scheme; bump version.
2. Update `app/.../publish/AuthProvider.kt` to implement the chosen
   strategy. `NoAuthProvider` stays as a development fallback.
3. Backend implements server-side validation.
4. Both projects update the contract submodule pin.
