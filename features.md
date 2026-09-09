# Features

Feature history of this fork, by PR merge date.

## 2026-06-17

#1 (https://github.com/relytcloud/unitycatalog/pull/1)

- features: Aliyun OSS temporary-credential vending (static AK/SK or STS least-privilege, scoped per table/path), with per-user authorization via RFC 8693 token exchange against an external JWKS.
- bugfix: —

## 2026-07-13

#2 (https://github.com/relytcloud/unitycatalog/pull/2)

- features: Expose Prometheus metrics at `/metrics` (per-route request counts via Armeria + micrometer).
- bugfix: —

#3 (https://github.com/relytcloud/unitycatalog/pull/3)

- features: UI — External Data management (external locations & credentials, with a real credential-vending Validate probe), SCIM user management, permission grant/revoke, and creating tables bound to external locations.
- bugfix: —

## 2026-08-07

#4 (https://github.com/relytcloud/unitycatalog/pull/4)

- features: UI — simplified `read`/`create` permission model with auto-completed `USE_CATALOG`/`USE_SCHEMA` grants, per-user access views, and permission-aware button gating.
- bugfix: —

#5 (https://github.com/relytcloud/unitycatalog/pull/5)

- features: Standalone UI server (`ui/server.js`): serves the built UI and proxies the REST API with a server-side bearer token, so no CRA dev server is needed.
- bugfix: —

#6 (https://github.com/relytcloud/unitycatalog/pull/6)

- features: Trusted issuers hot-derived from the external JWKS file (onboard a new signer by appending its public key — no restart), plus user email validation on `createUser`.
- bugfix: Map unknown-signing-key errors (`JwkException`) to 401 instead of a bare 500, and map Aliyun assume-role failures to 4xx by error code (e.g. `AccessDenied`) instead of a blanket 500.

## 2026-09-08

#10 (https://github.com/relytcloud/unitycatalog/pull/10)

- features: UI — grant access straight from the table and user lists (row-level `⋯ → Grant access`, plus `Access details` for a user), backed by a read-only `auth/capabilities` endpoint so the UI knows whether the caller is a metastore admin instead of guessing.
- bugfix: —

## 2026-09-09

#12 (https://github.com/relytcloud/unitycatalog/pull/12)

- features: —
- bugfix: `deploy-uc.sh` no longer refuses to start when `UC_ALLOWED_ISSUERS` is empty, which is the documented default (trusted issuers come from the external JWKS file).

## ⚠️ Known gap: UI authentication is incomplete

The UI currently has no per-user login: the standalone UI server injects a single
server-side bearer token for all API calls, so everyone using the UI acts as that
one identity. A proper UI login & authentication system (per-user sign-in wired
into the server's token-exchange flow) still needs to be built.
