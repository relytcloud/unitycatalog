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

## 2026-09-23

#17 (https://github.com/relytcloud/unitycatalog/pull/17)

- features: UI sign-in, per user. Three entry points, each at an address of its own: `/login` with **Sign in with Microsoft** (Entra ID, through an OAuth flow the server hosts end to end, so the client secret never reaches the browser), `/login/admin` for the administrator password, and `/login/token` for an access token the server issued. The application is no longer an entry point and the UI server injects nothing; what a signed-in person sees is filtered by their grants. On the server: the static JWKS file and OIDC discovery now coexist (routed by whether the issuer is registered in the file), the caller resolves through an ordered claim chain (`email`, `preferred_username`, `upn`, `sub`), discovery is cached per issuer and bounded, and Entra's `{tenantid}` issuer template is matched segment-wise. `deploy-uc.sh` derives the audience and issuer from the Microsoft settings and starts the UI with the server.
- bugfix: The session cookie is now one a browser keeps -- `Secure` only when the browser is on HTTPS, and `SameSite=Lax` rather than `Strict`, which was withheld on the cross-site navigation back from the identity provider. The URL transcoder passes a redirect back instead of following it (it relayed the provider's error page as a 400) and ends a bodyless response on its headers instead of leaving the caller waiting. A refused authorization code now names the provider's error code, which separates an expired client secret from a mismatched redirect URI.

