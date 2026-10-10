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


## 2026-09-24

#19 (https://github.com/relytcloud/unitycatalog/pull/19)

- features: Deactivating and deleting a user are now separate operations. Deactivation is reversible and leaves grants, owned objects and the email untouched; purging (`DELETE ...?purge=true`) is the irreversible one and the only thing that frees an email for reuse, guarded by typing the principal back, requiring the account to be deactivated first, refusing your own and the bootstrap administrator, and requiring an heir when the user owns securables -- those objects change hands as part of it. `GET .../ownedObjects` lists what is at stake beforehand. Metastore administrators can be appointed and stood down from the Users page, under the invariant that at least one *enabled* administrator always remains. The Users list separates active from deactivated accounts and carries each one's state.
- bugfix: `PATCH /scim2/Users/{id}` carried no authorization annotation, and the access decorator lets a method without one through -- any signed-in account could deactivate any other, an administrator included; it now requires metastore OWNER. The administrator password sign-in never consulted the user table, so a deactivated `admin` still received a session whose every request was then refused; it now checks the account exists and is enabled. The bootstrap administrator could be deactivated by three separate routes and stripped of its administrator status, either of which closes the password sign-in that is the way back in when the identity provider is unavailable. The PatchOp schema typed `value` as a boolean, so the UI sent a scalar where RFC 7644 3.5.2.1 requires an object and the SCIM library rejected it while parsing; both forms the RFC allows are now accepted. In the UI, choosing any action from a user's menu -- or clicking inside a dialog it opened -- also opened the user drawer behind it. Test servers raced for their port, failing whole test classes with `Address already in use` on a loaded runner (this is what turned `test (Spark 4.1.0)` red on `main` after #17).

## 2026-10-08

#21 (https://github.com/relytcloud/unitycatalog/pull/21)

- features: —
- bugfix: Behind a reverse proxy that serves this server under a path prefix and strips it before forwarding, hosted login failed at the callback with "Missing login state". `/auth/login` scoped the `UC_OAUTH_STATE` cookie to the mount path the server sees (`/api/1.0/unity-control/auth`), while the browser comes back to `/callback` under the prefix, so the cookie was never sent. The state cookie is now set and cleared on the path the browser actually uses -- the path of `server.external-url` followed by the mount path -- which is the directory of the `redirect_uri` the provider is handed. Deployments whose `server.external-url` carries no path are unaffected, and `UC_TOKEN` stays at `Path=/`. Reaching this through the bundled deploy scripts additionally needs #22, which lifts the host-only check `deploy-uc.sh` applies to `UC_EXTERNAL_URL`.

#24 (https://github.com/relytcloud/unitycatalog/pull/24)

- features: —
- bugfix: `deploy-uc.sh` accepts a path in `UC_EXTERNAL_URL` (`https://api.example.com/unitycatalog`), which the #21 state-cookie fix depends on and which the script rejected outright, so the fix could not be reached through the repo's own deployment path. Trailing slash, empty segment, query, fragment and a missing scheme are still rejected. The sample file and both deployment guides now describe the behind-a-prefix case and the redirect URI to register.

## 2026-10-10

#25 (https://github.com/relytcloud/unitycatalog/pull/25)

- features: Read access at the schema and catalog level. `SELECT` (and `MODIFY`) granted on a schema covers every table beneath it, tables created later included, and granted on a catalog -- together with `USE SCHEMA` on the catalog, which inherits the same way -- covers every schema's tables, schemas created later included; the reach is that subtree and nothing else. Because the reach is wide, granting or revoking `SELECT`/`MODIFY` on a schema or catalog is reserved to metastore administrators (the built-in `admin` and anyone appointed through `PUT /api/1.0/unity-control/metastore/admins/<email>`); owners keep `USE_*`, `CREATE_*` and table-level grants, and a request mixing `USE_*` with `SELECT` is refused as a whole. The UI offers `read` on a schema or catalog page to administrators, lists `read` and `create` rows separately, applies the same gate to the Users page's *Grant access* dialog, and shows the Users page to administrators only. Both deployment guides gain an *Authorization model* section: the two kinds of privilege, inheritance and its limits, the schema-wide and catalog-wide recipes, who may grant what and why `USE_*` is not reserved, and who sees the user directory.
- bugfix: —
