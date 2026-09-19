# Microsoft Entra ID as a token-exchange issuer (server side)

Date: 2026-09-19
Status: approved design, not yet planned or implemented
Scope: server only. The UI sign-in flow is a separate, later spec.

## Context

This fork already supports per-user authorization through RFC 8693 token exchange at
`POST /api/1.0/unity-control/auth/tokens`. A caller presents a token signed by a trusted
external issuer; the server verifies it and returns a UC access token.

Trusted issuers come from two sources, unioned in `AuthService.java:147`:

- `knownIssuers()` — derived from the hot-reloaded external JWKS file
  (`server.external-jwks-file`), where each key carries an `issuer` member. This is how Relyt
  DWSU instances are onboarded: append a key, no restart.
- `server.allowed-issuers` — a startup-snapshot allow-list.

The goal is to let Microsoft Entra ID act as an additional issuer, so a user holding an
Entra-issued ID token can exchange it for a UC access token.

## Problem

Three things block this today.

**1. JWKS resolution is all-or-nothing.** In `JwksOperations.loadJwkProvider`
(`server/src/main/java/io/unitycatalog/server/utils/JwksOperations.java:110-115`), if
`server.external-jwks-file` is set and the file exists, *every* external issuer is resolved
against that static file and the OIDC-discovery branch below it is unreachable. Every deployment
produced by `deploy/deploy-uc.sh` sets that file, so an Entra-signed token fails signature lookup
and returns 401. Entra's keys rotate and cannot live in a hand-maintained file, so the
file-vs-discovery choice has to become per-issuer.

**2. Principal mapping depends on an `email` claim.** `AuthService.java:248` and
`SecurityContext.java:77` both compute the subject as the `email` claim falling back to `sub`,
then require `getUserByEmail(subject)` to find an ENABLED user. Entra emits `email` only when the
user's mail attribute is populated or the claim is configured on the app registration. Without it
the subject becomes an opaque GUID and every user is rejected with `User not allowed: <guid>`.

**3. Discovery is not production-shaped.** The discovery path uses `.cached(false)` and re-fetches
`/.well-known/openid-configuration` on every exchange (`JwksOperations.java:115,137,164`), with no
timeout and no response-status check. That is correct for a small local file and wrong for a remote
IdP: it puts a round trip to Microsoft on every token exchange, blocks a thread when Microsoft is
slow, and reports a non-200 as a confusing JSON parse error.

## Scope

### In scope

- Per-issuer routing between the static JWKS file and OIDC discovery.
- Caching, rate limiting, timeout and status handling on the discovery path.
- Three-value Entra configuration and the values derived from it.
- Distinguishable errors for the dominant failure modes.
- Tests, deploy templates, and operator documentation.

### Out of scope

- The UI sign-in flow and the replacement of the shared admin-token injection in `ui/server.js`.
  That is the known gap recorded in `features.md` and is the subject of the next spec.
- JIT user provisioning. Users remain pre-provisioned via SCIM.
- Per-issuer audience lists (see Accepted risks).
- Multi-tenant or sovereign-cloud Entra support.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Surfaces to support | Server first, UI as a second spec | Strictly sequential: the UI cannot sign anyone in via Entra until the server will exchange an Entra token. |
| Identity mapping | Pre-provisioned, `email` claim required | Leaves the mapping layer untouched; the tenant admin adds `email` as an optional claim on the app registration. |
| Tenancy | One tenant per deployment | The operator's own tenant. Not multi-tenant, not sovereign clouds. |
| Network egress | Outbound HTTPS to `login.microsoftonline.com` is available | Real OIDC discovery rather than mirrored keys. |
| JWKS routing | Route by what the JWKS file declares | Reuses `knownIssuers()`, adds no config, keeps one declarative source of truth. |
| Issuer and audience | Derived from the tenant id | Keeps operator config at three values. |
| IdP unreachable | 503, distinct from a rejected token | An outage is not the caller's fault and is retryable. |

## Design

### 1. Configuration

The operator sets three values in `uc.env`:

```
UC_ENTRA_TENANT_ID=<tenant-guid>
UC_CLIENT_ID=<application-client-id>
UC_CLIENT_SECRET=<client-secret>
```

`deploy-uc.sh` renders these into `server.properties` as `server.entra.tenant-id` and the two
existing upstream keys `server.client-id` and `server.client-secret`.

Four values are derived from the tenant id, so no operator hand-writes a Microsoft URL. The
authority is fixed at `login.microsoftonline.com`:

| Derived | Value |
|---|---|
| Issuer | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| Audience | the value of `server.client-id` |
| `server.authorization-url` | `https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/authorize` |
| `server.token-url` | `https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token` |

Derivation composes rather than overrides:

- The derived issuer is unioned into the trusted-issuer set alongside `knownIssuers()` and
  `server.allowed-issuers`, extending the union already at `AuthService.java:147`.
- The derived audience is unioned into `server.audiences`.
- An explicitly configured `server.authorization-url` or `server.token-url` wins over the derived
  value.
- When `server.entra.tenant-id` is unset, nothing is derived and behavior is exactly as today.

The client secret is not used by the server. `server.client-id`, `server.client-secret`,
`server.authorization-url` and `server.token-url` are consumed only by the CLI
(`examples/cli/src/main/java/io/unitycatalog/cli/utils/Oauth2CliExchange.java:61-64`), which runs
the authorization-code flow. The server only verifies signatures, which needs public keys alone.
The secret is carried in deployment config for the consumers that perform a code exchange: the
CLI today, the UI in the next spec.

A startup log line records the derived issuer and audience so the effective trust set is
auditable even though it is not spelled out in the file.

### 2. JWKS routing

In `loadJwkProvider`, the external branch becomes:

```java
if (knownIssuers().contains(issuer)) {
    return new IssuerScopedJwkProvider(fileProvider, issuer);  // the file declares it
}
return discoveryProvider(issuer);                              // otherwise, OIDC discovery
```

The file is consulted when it *declares* the issuer, rather than whenever it merely exists.
DWSU issuers keep resolving from the file, preserving the hot onboarding story in
`deploy/README.md`. The Entra issuer, which is never in the file, routes to discovery.

This reads the JWKS file twice per exchange, once in `AuthService` and once here. The file is
small and already read per request, and keeping `loadJwkProvider` self-contained is worth more
than threading the issuer set through the call chain.

### 3. Caching

The two resolution paths have opposite requirements and are treated differently.

- **File path:** stays `.cached(false)`. This is what makes appending a DWSU key take effect
  without a restart.
- **Discovery path:** the built `JwkProvider` itself is cached per issuer, keyed by the normalized
  issuer, with a TTL. `JwkProviderBuilder.build()` returns three fresh objects on every call:
  `new GuavaCachedJwkProvider(new RateLimitedJwkProvider(new UrlJwkProvider(...), bucket), cacheSize, expiresIn)`.
  The key cache lives in the outer `GuavaCachedJwkProvider`, the token bucket in the middle
  `RateLimitedJwkProvider`. Caching the built provider itself, not just the resolved `jwks_uri`,
  is what lets that cache and bucket persist across exchanges: a cache hit
  within the TTL returns the same provider without re-running discovery, so its internal key cache
  and rate limiter see repeated use across calls instead of starting empty and full each time. A
  cache miss re-runs discovery and builds a new provider with fresh `.cached(...)` and
  `.rateLimited(...)` settings. Starting values, fixed in code rather than made configurable until
  there is a reason: key cache of 10 entries with a 24-hour TTL, rate limit of 10 fetches per
  minute, a 24-hour TTL on the cached provider itself, and a 5-second HTTP timeout on both the
  discovery and JWKS fetches. A cache miss on an unknown `kid` still triggers a live fetch, which is
  what makes key rotation work; the rate limit is what stops that being abused. A failed
  resolution — non-200, timeout, malformed discovery document — is never cached, so the next
  exchange retries instead of being stuck behind a cached failure.

> **Correction (post-implementation).** This section originally specified caching only the
> resolved `jwks_uri` per issuer while rebuilding the `JwkProvider` on every call. That did not
> work: `JwkProviderBuilder.build()` constructs a fresh cache and a fresh rate-limit bucket on
> every call, so a per-call build gave every exchange an empty key cache and a full bucket and
> still made a live HTTP fetch — the `.cached(...)` and `.rateLimited(...)` settings were present
> but inert, and this section's own stated goal of removing the per-exchange round trip to
> Microsoft was not met. The design above is the correction, made during implementation: cache the
> built provider itself, per issuer, with a TTL.

### 4. Failure modes and status codes

`ErrorCode` already carries the HTTP status (`ErrorCode.java`), so no new exception class is
needed — only the right code on the existing `OAuthInvalidRequestException`.

| Failure | Today | After |
|---|---|---|
| Subject token has no `email` claim | `User not allowed: <guid>` | A message naming the missing claim and pointing at the app registration's optional claims. |
| `email` present, no matching UC user | `User not allowed: <email>` | A message saying the user is not provisioned. |
| Key not found for a declared issuer | 401 (mapped in PR #6) | Unchanged, plus a log line naming the resolution path. |
| Discovery or JWKS non-200 / unreachable | `ErrorCode.ABORTED`, which maps to **409** | `ErrorCode.UNAVAILABLE` → **503**. |
| Discovery times out | blocks indefinitely, no timeout | `ErrorCode.DEADLINE_EXCEEDED` → **504**. |
| JWKS fetch times out | blocks indefinitely, no timeout | The auth0 library wraps it as `NetworkException`, which `GlobalExceptionHandler` maps to `ErrorCode.UNAVAILABLE` → **503**, not 504. |

The `email` fallback to `sub` is retained, because DWSU tokens legitimately rely on it. Only the
*error* is split by cause. Messages describe configuration and never include token contents.

One log line at resolution names whether the key came from the file or from discovery. Without it,
a typo'd `issuer` member in the JWKS silently reroutes to discovery and the operator has nothing
to go on.

### 5. Security invariants

These hold before and after the change and must be preserved by the implementation.

- **Discovery is never pointed at an attacker-chosen URL.** The external branch of
  `loadJwkProvider` is reachable only from `AuthService.grantTokenExchange`, and only after the
  issuer has cleared the allow-list union. `AuthDecorator.java:79` rejects any issuer that is not
  `INTERNAL` before reaching JWKS resolution.
- **A key is only valid for the issuer it was registered to.** `IssuerScopedJwkProvider` binds each
  file-held key to its `issuer` member, preventing a confused-issuer attack across DWSU instances.
- **Trust is still fail-closed.** An issuer absent from both `knownIssuers()` and the allow-list is
  rejected before any key lookup.

### Accepted risks

Deriving the audience unions the Entra client id into the global `server.audiences`, and
verification uses `withAnyOfAudience`. That audience is therefore accepted for every trusted
issuer, not only Entra. This is not exploitable: a DWSU-issued token must still be signed by a key
registered to its own issuer and that issuer must still be allow-listed. Audience is a second
filter here, not the trust boundary. Per-issuer audiences would be stricter and are deliberately
deferred.

## Testing

Discovery is tested for real with no new dependency. Armeria is already a main dependency, so
tests stand up a local `Server` serving `/.well-known/openid-configuration` and a JWKS, and point
an `http://127.0.0.1:<port>` issuer at it. The scheme-prefixing at `JwksOperations.java:125` leaves
an explicit `http://` alone, so the genuine HTTP path is exercised, including the
`configIssuer.equals(issuer)` match.

Extending `JwksOperationsTest` and `AuthServiceExternalJwksTest`:

- **Routing** — an issuer declared in the file resolves from the file; an issuer absent from it
  goes to discovery; both cases in one JWKS file, proving the choice is per-issuer not global.
- **Regression guard** — with no Entra configuration, DWSU resolution and trust are unchanged.
  This is the guarantee that matters most for a live deployment and gets an explicit test.
- **Caching** — request counting on the test server: N exchanges produce one discovery fetch.
- **Failure modes** — non-200 and unreachable produce 503; timeout produces 504; a token with no
  `email` claim and a token with an unmatched `email` produce the two distinct messages.
- **Config derivation** — tenant id produces the right issuer, audience and URLs; union semantics
  with existing values; an explicit `authorization-url` / `token-url` wins.

## Manual acceptance

Not automatable, and required before this is considered done: exchange a real token from a real
tenant. The `email` optional claim must actually be emitted by the app registration, and no unit
test can prove that. The CLI's existing code flow is the cheapest end-to-end exercise once the
derived `authorization-url` and `token-url` are in place.

## Delivery

- `deploy/uc.env.example` gains the three keys.
- `deploy/server.properties.template` gains `server.entra.tenant-id=${UC_ENTRA_TENANT_ID}`, and its
  currently-empty `server.client-id=` / `server.client-secret=` lines become
  `${UC_CLIENT_ID}` / `${UC_CLIENT_SECRET}`.
- `deploy/deploy-uc.sh` renders only a hardcoded allow-list of keys, duplicated in two places: the
  `export` at `deploy/deploy-uc.sh:56` and the Python `keys` list at `:64`. Both must gain the three
  new names. A placeholder missing from that list is not an error — it renders as the literal text
  `${UC_ENTRA_TENANT_ID}` into `server.properties`, which the server then reads as a real value. The
  cheap safeguard is for `deploy-uc.sh` to fail after rendering if the output still contains a
  `${` sequence, which catches this class of mistake for every key, not just the new ones.
- `deploy/README.md` gains an Entra app-registration section: the v2.0 endpoint, adding `email` as
  an optional claim, and where the tenant id and client id come from.
- `features.md` gains an entry, following the fork's existing convention.

## Known follow-ups

Recorded here, deliberately not in this spec:

- **CLI redirect URI.** Entra requires an exact redirect-URI match for confidential clients, while
  `Oauth2CliExchange.findAvailablePort()` falls back to a random port. The CLI does read
  `server.redirect-port` and honours it when it is set; what was missing was the deploy path
  rendering it, so it was always blank. CLI login against Entra needs that value set and the
  matching `http://localhost:<port>` registered on the app registration.
- **Okta and Keycloak are not wired.** `ui/src/pages/Login.tsx:68` passes an Okta `onSuccess` that
  only calls `console.log`, and `KeycloakAuthButton` is entirely commented out. Only Google has
  ever exercised the login path.
- **`authEnabled` is keyed off Google.** `ui/src/App.tsx:45` gates the whole UI auth flow on
  `REACT_APP_GOOGLE_AUTH_ENABLED`, which must be generalized before any second UI IdP works.

## Next spec

UI sign-in with Entra, which must also address the known gap in `features.md`: `ui/server.js`
injects a single server-side bearer token for all `/api` calls, so every UI user acts as one
identity. A Microsoft sign-in button changes nothing until that is replaced with per-user session
handling. `AuthService` already supports `ext=cookie`, which sets the UC token as a secure
`UC_TOKEN` cookie; forwarding that cookie instead of injecting a token is the thread to pull.
