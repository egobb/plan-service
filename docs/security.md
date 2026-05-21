# Security

This document summarizes the main security considerations for this service and the protections already in place.

## Threat model
The service has two main inputs:

1. **Public HTTP requests** to `GET /search` (untrusted client input).
2. **Provider XML snapshots** fetched over the network (untrusted external data).

The primary risks are:

- Parameter abuse (very large limits/offsets, invalid dates).
- XML parser vulnerabilities (XXE, entity expansion, large payloads).
- Denial of service via slow provider responses or excessive retries.
- SQL injection (if query construction is unsafe).

## API input validation

- `/search` clamps pagination parameters to safe bounds (`limit` capped, `offset` non-negative).
- Date parsing uses strict ISO-8601 instants.
- Errors return well-defined responses (see contract tests).

## SQL safety

- SQL uses parameterized queries via `NamedParameterJdbcTemplate`.
- Dynamic SQL only controls which predicates are present; values are always bound parameters.

## XML hardening
Provider snapshots are parsed with a **streaming StAX parser** and explicitly hardened against XML attacks:

- DTD support is disabled.
- External entities are disabled.
- Entity replacement is disabled.
- Coalescing is enabled.

This reduces the blast radius of XXE and entity expansion attacks and keeps memory usage stable.

## Network timeouts and retries
Provider calls are protected by:

- **Connect timeout** and **read timeout**.
- Bounded retries with exponential backoff + jitter.
- No retries for non-retryable 4xx (except 429).
- Optional retry on parse errors (disabled by default).

This prevents the provider integration from consuming unbounded time and resources.

## Operational safeguards

- `/search` is provider-independent, so provider downtime does not cascade to user-facing requests.
- Advisory locks prevent overlapping fetch runs that could amplify provider load.
- Staging + processing retries are bounded via `maxAttempts`.
- Stuck `PROCESSING` rows are requeued using `claimed_at` TTL.

## Recommended production extensions
If this were production, typical next steps would be:

- **Rate limiting** / request quotas on `/search`.
- WAF / API gateway protections.
- TLS everywhere and strict outbound egress policies.
- Secrets management (no plaintext env vars for DB credentials).
- Dependency scanning (SCA) + container image scanning.
- Structured audit logs for administrative endpoints (if any are added).
