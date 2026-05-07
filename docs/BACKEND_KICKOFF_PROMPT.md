# Backend Kickoff Prompt

Drop the contents below into a fresh Claude Code session at the start of
the backend project. It's self-contained and includes everything that
session needs.

---

## Prompt to paste

> I'm starting the backend for the **FisionTV+ Network Certifier**. The
> Android client and the API contract are already written; you're
> implementing the server side.
>
> **Inputs you should pull and read first:**
>
> 1. The contract repo:
>    [`lstepnio/fisiontv-cert-contract`](https://github.com/lstepnio/fisiontv-cert-contract)
>    pinned to tag `v1.0.0`.
>    - `openapi.yaml` is the source of truth for endpoints + schemas.
>    - `SPEC.md` explains the *why* — read this before the YAML.
>    - `fixtures/` contains representative request/response JSON.
> 2. The Android client repo:
>    [`lstepnio/NetworkQualityHWC`](https://github.com/lstepnio/NetworkQualityHWC).
>    `app/.../publish/` shows what the client sends and how it expects
>    the server to respond.
>
> **What to build:**
>
> Three endpoints described in `openapi.yaml`:
> 1. `GET /v1/cert-config` — serves the active config; supports ETag.
> 2. `POST /v1/certifications` — idempotent on `certificationId`,
>    handles dedupe (200) vs conflict (409) on payload-hash mismatch.
> 3. `GET /v1/certifications/{id}` — single-record lookup for support.
>
> Plus the storage layer (Postgres reference schema is in `SPEC.md` §7),
> auth scheme, and a CI pipeline matching the Android repo's pattern
> (build + test on PR, tag-driven release).
>
> **Decisions you'll need to make early:**
>
> - **Stack.** SPEC.md §3 lists sane defaults (Go or Node/Fastify, Postgres).
>   Pick one. Default to whatever the team already uses; if no preference,
>   default to Go for the simpler deployment story or Node for faster
>   iteration. Either is fine; document the choice in the README.
> - **Auth strategy.** SPEC.md §5 has two options: per-install bearer
>   (Option A) or HMAC + shared secret (Option B). Default to Option A
>   for any non-trivial pilot. If choosing Option A, you also need to
>   implement `POST /v1/devices/register` — add it to the contract repo
>   as a v1.1.0 spec bump.
> - **Hostname.** `certifier-api.gethotwired.com` is the proposed name
>   in SPEC.md §10; confirm with the HWC team or pick another.
>
> **Constraints to respect:**
>
> - **Schema additions only, no removals.** The Android app is in the
>   field; breaking schema changes need a coordinated client release.
>   Adding optional fields is always safe (clients ignore unknowns).
> - **PII policy is unresolved.** SPEC.md §10 lists `bssid`, `ssid`,
>   `publicIp`, `gatewayIp` as identifying. Default behaviour: hash on
>   ingest. Coordinate with whoever owns customer-data policy before
>   shipping; don't store these fields raw in v1.
> - **The contract repo is shared.** If you need a schema change, edit
>   `openapi.yaml` in the contract repo, bump the version per
>   `CHANGELOG.md`, tag, then bump the submodule pin in both client and
>   server. Don't unilaterally diverge.
>
> **Build the smallest correct thing first.** v1 is: three endpoints,
> Postgres, basic bearer auth, no dashboard, no webhooks, no admin
> tooling. Once that's running and the Android app is POSTing real
> results, layer the rest on.
>
> Start by reading both repos, then propose your stack choice, auth
> approach, hostname, and a concrete v1 implementation plan. Don't write
> code until I confirm the approach.

---

## Useful one-liners for that session

```bash
# Pull the contract spec
gh repo clone lstepnio/fisiontv-cert-contract /tmp/cert-contract
cd /tmp/cert-contract && git checkout v1.0.0

# Validate generated payloads from the Android app against the spec
# (the app logs the JSON under tag CertPayload via adb logcat)
npx --yes @redocly/cli@latest lint /tmp/cert-contract/openapi.yaml

# Inspect the schema visually
npx --yes @redocly/cli@latest preview-docs /tmp/cert-contract/openapi.yaml
```
