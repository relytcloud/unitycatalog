# UC deployment (per-user token-exchange + Aliyun OSS)

**English** | [简体中文](README.zh-CN.md)

One command renders `server.properties` and `hibernate.properties` **from templates** and starts the
Unity Catalog server. Real secrets stay in a local, gitignored `uc.env` — **never in the repo**; the
repo only carries placeholder templates.

**Core idea:** you normally set a single variable, `UC_HOME`, pointing at a **persistent (cloud-disk)**
path. `server.properties`, `hibernate.properties`, `relyt_jwks.json` and the **H2 metastore DB**
(catalogs/schemas/tables, external locations, credentials, users, permissions) all live under
`UC_HOME`, so a restart or container rebuild loses nothing.

> Other ways to run Unity Catalog live in [`../docker`](../docker) and [`../helm`](../helm).
> This directory is the script-based deployment used for the Relyt integration.

## Prerequisites

| Requirement | Notes |
|---|---|
| **JDK 17+** | `deploy-uc.sh` falls back to an sbt build when the server jar is missing |
| **Python 3** | used to substitute `${VAR}` placeholders in the templates |
| **A persistent path** for `UC_HOME` | see [Persistence](#persistence); required in production |
| **Aliyun RAM user + role** | a master RAM user (AK/SK) that can `AssumeRole`, plus one role per external location |
| **Node.js 18+** and `yarn` | only if you also want the web UI — see [Running the UI](#running-the-ui) |

## Quick start

```bash
cd deploy
cp uc.env.example uc.env      # first time only
vi uc.env                     # set UC_HOME (persistent path) + Aliyun credentials + audience
./deploy-uc.sh                # render config and start UC in the foreground
# background: setsid nohup ./deploy-uc.sh >/tmp/uc.log 2>&1 </dev/null & disown
```

`deploy-uc.sh` validates the required values, renders `server.properties` and
`hibernate.properties` (all paths default to somewhere under `UC_HOME`), creates the H2 directory,
then runs `cd $UC_HOME && bin/start-uc-server --port $UC_PORT`.

### Verify it is up

The server listens on **`UC_PORT` (default `8088`)**:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8088/api/2.1/unity-catalog/catalogs
```

**`401` is the expected answer** with `UC_AUTHORIZATION=enable`: the endpoint is reachable and
authorization is on. `200` means authorization is **off** — check `UC_AUTHORIZATION`. Anything else
(connection refused, timeout) means the server is not up; see [Troubleshooting](#troubleshooting).

> `8088` matches `bin/start-uc-with-ui.sh`, the UI's default proxy target, and the phoenix/presto
> e2e suites. Pass `--port` to `deploy-uc.sh`, or set `UC_PORT`, to use a different one — an explicit
> `--port` wins over `UC_PORT`.

## Running the UI

`deploy-uc.sh` starts the **server only**. The bundled web UI is served by `ui/server.js`, which
proxies `/api` to the UC server. It adds no credentials: every visitor signs in for themselves.

The container image entrypoint [`bin/start-uc-with-ui.sh`](../bin/start-uc-with-ui.sh) runs both
processes when `UC_ENABLE_UI` is truthy (`1`/`true`/`yes`/`on`); otherwise it runs the server alone:

```bash
UC_ENABLE_UI=true UC_PORT=8088 UI_PORT=3000 bin/start-uc-with-ui.sh
```

To run the UI next to a script-based deployment, build the assets once (`ui/build` is not committed)
and start the UI server yourself:

```bash
cd ui && yarn install && yarn build && cd ..
UC_TARGET=http://localhost:8089 \
PORT=3000 HOST=0.0.0.0 node ui/server.js
```

> ⚠️ **With authorization off, the server asks for nothing and neither does the UI.** Keep port
> `3000` on a trusted network; never expose it publicly.

## Files

| File | Committed | Purpose |
|---|---|---|
| `server.properties.template` | ✅ | `server.properties` template with `${VAR}` placeholders |
| `hibernate.properties.template` | ✅ | H2 metastore config template (H2 path = `${UC_DB_FILE}`, under `UC_HOME`) |
| `uc.env.example` | ✅ | sample parameters (no secrets); copy to `uc.env` and fill in |
| `uc.env` | ❌ **gitignored** | **real secrets/parameters**, local only, never committed |
| `deploy-uc.sh` | ✅ | renders both configs and starts UC |
| `uc_add_jwks_key.sh` | ✅ | registers/rotates a Relyt instance public key into the JWKS file (validates + binds issuer) |
| `README.md` / `README.zh-CN.md` | ✅ | this document |

## Configuration (`uc.env`)

| Variable | Required | Meaning / example |
|---|---|---|
| `UC_HOME` | no¹ | **persistent root (cloud disk)**. Defaults to one level up from `deploy/`. Every stateful file lives under it |
| `UC_PORT` | no | port the server listens on. Default `8088` |
| `UC_AUTHORIZATION` | no | `enable` (default) / `disable` |
| `UC_ALLOWED_ISSUERS` | no | **optional** extra trusted issuers (comma-separated). Trusted issuers are derived primarily from the JWKS file (each key's `issuer` member, hot-reloaded); this list is a **union** on top, only for issuers **not** in the local JWKS (e.g. OIDC discovery). **Leave empty for Relyt** — the JWKS alone governs trust, and onboarding a DWSU means appending a key (hot-reloaded, no restart, no change here) |
| `UC_AUDIENCES` | **yes** | audience of the subject token, e.g. `unitycatalog-server` (must match the signer's `unity.audience`) |
| `UC_ACCESS_TOKEN_TTL` | no | lifetime of exchanged tokens, **ISO-8601** (`PT12H`, `PT30M`; `12h` is rejected). Blank = **no expiry**. One value for every caller — the coordinator and the UI. `uc.env.example` ships `PT12H`, which is the value to deploy: the coordinator's exp-based refresh path had not run in production when this was chosen, so the default leaves headroom. Shorten it only once that path has been observed in your own deployment |
| `UC_AUTHORIZATION_URL` | no | provider's OAuth authorization endpoint, e.g. `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/authorize`. **All four** login variables blank = hosted login off (see [Microsoft Entra ID sign-in](#microsoft-entra-id-sign-in-optional)) |
| `UC_TOKEN_URL` | no | provider's OAuth token endpoint, e.g. `https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token` |
| `UC_CLIENT_ID` | no | client id of the application registered for the UI login; must also be listed in `UC_AUDIENCES` |
| `UC_CLIENT_SECRET` | no | that application's client secret — stays on the server, never sent to the browser |
| `UC_EXTERNAL_URL` | no | browser-facing base URL of UC (`https://uc.example.com`) when a proxy in front does not send `X-Forwarded-Proto`/`X-Forwarded-Host`; blank = derived from the request |
| `UC_ADMIN_PASSWORD` | no | password of the built-in `admin` for the UI's administrator sign-in at `<ui>/login/admin`. Blank = that entry point off. See [UI sign-in](#ui-sign-in) |
| `ALIYUN_REGION` | **yes** | e.g. `cn-hangzhou` |
| `ALIYUN_ACCESS_KEY` | **yes** | master RAM user AK (used for STS `AssumeRole`) |
| `ALIYUN_SECRET_KEY` | **yes** | master RAM user SK |
| `ALIYUN_MASTER_ROLE_ARN` | **yes** | the master RAM principal, e.g. `acs:ram::<account-id>:user/<user>`. ⚠️ Despite `ROLE` in the name this is normally the RAM **user** ARN — the identity that *calls* `AssumeRole` — **not** the per-location role that gets assumed |

¹ `UC_HOME` can technically be left unset (it falls back to the install root), but **always point it at
a cloud disk in production**, or a container rebuild loses your metadata.

### Path overrides (optional, rarely needed)

Everything defaults to a location under `UC_HOME`; set these only to relocate one file:

| Variable | Default |
|---|---|
| `UC_SERVER_PROPERTIES` | `$UC_HOME/etc/conf/server.properties` |
| `UC_HIBERNATE_PROPERTIES` | `$UC_HOME/etc/conf/hibernate.properties` |
| `UC_EXTERNAL_JWKS_FILE` | `$UC_HOME/etc/conf/relyt_jwks.json` |
| `UC_DB_FILE` | `$UC_HOME/etc/db/h2db` (H2 file, without the `.mv.db` suffix) |

## Persistence

- `UC_HOME` is both the **install root** (it must contain `bin/start-uc-server`, build output and the
  dependency cache — the script checks) and the **state root**. The parts that cannot be regenerated
  are only `etc/conf` (config + JWKS + signing identity) and `etc/db` (H2).
  ⚠️ In containers, **mount only those two subdirectories**. Mounting the whole `UC_HOME` as a volume
  shadows the binaries baked into the image, so after a tag upgrade you would still run the old build.
- ⚠️ H2 is a **single-process file database** and does not support multiple UC instances / HA. For HA,
  switch to an external **PostgreSQL/MySQL**: replace `connection.url` / `driver` in
  `hibernate.properties.template` (see `etc/db/postgres-example.yml` / `mysql-example.yml` in the
  repository) and parameterise the connection string into `uc.env` as needed.

### `etc/conf` must be persisted as a whole

⚠️ Besides the regenerable rendered configs (`server.properties` / `hibernate.properties`), `etc/conf`
holds the **non-regenerable signing identity**: `private_key.der`, `public_key.der`, `key_id.txt`.
UC **checks these three on every start: it reuses them only if all three exist, and regenerates the
key pair and a new `key_id` if any is missing** (`certs.json` / `token.txt` are rewritten with it).
In other words, if this directory is not on durable storage, a single restart swaps the key set — and
once the keys change, **every previously issued access token and admin service token fails
verification immediately** (downstream `401`).

### `etc/db` must be persisted as a whole

⚠️ **All** UC metadata (catalogs/schemas/tables, external locations, credentials, users, permissions)
lives only in the H2 file database `$UC_DB_FILE` (default `$UC_HOME/etc/db/h2db.mv.db`). Lose the
directory and you are back to an empty instance with everything to recreate.

## Logging

- Server log: **`$UC_HOME/etc/logs/server.log`** (rotated to `server-<time>-<n>.log.gz`); CLI log
  `etc/logs/cli.log`. Paths are relative to the working directory, and the script starts UC from
  `UC_HOME`, so pointing `UC_HOME` at a cloud disk persists the logs too.
- Config file: **`etc/conf/server.log4j2.properties`** (log4j2). Common knobs:

  | Setting | Meaning | Default |
  |---|---|---|
  | `appender.rollingFile.fileName` | current log file | `etc/logs/server.log` |
  | `appender.rollingFile.policies.size.size` | size that triggers rotation | `10MB` |
  | `appender.rollingFile.policies.time.interval` | time-based rotation interval | `1` (day) |
  | `appender.rollingFile.strategy.max` | archives to keep (oldest deleted beyond this) | `5` |
  | `rootLogger.level` | log level (trace/debug/info/warn/error) | `info` |

  **Restart UC to apply** (log4j2 supports hot reload, but restarting is simplest here). For more
  retention raise `size` to `50MB` and `strategy.max` to `20`; when debugging set
  `rootLogger.level = debug` temporarily.
- `var/log/observation.log` is not a business log (an empty file created by default by the Armeria
  observability component); it is gitignored and can be ignored.

## Registering storage location credentials (important)

UC requires external location URLs to **not overlap** in hierarchy (identical, parent or child all
count as overlapping). So for one layer of data, register **either** at database level **or** at table
level — do not mix the two.

- ❌ Wrong (fails):
  - `oss://bucket/db`            → credential A
  - `oss://bucket/db/table1`     → credential B  ← parent/child overlap with the previous one; **the
    second create is rejected outright** (whichever is created second loses).
- ✅ Correct (pick one, keep the level consistent):
  - database level: a single `oss://bucket/db`;  ## recommended
  - table level: `oss://bucket/db/table1`, `oss://bucket/db/table2`, … one each (mutually disjoint).

Why: vending works by "find the external location **that covers** this data path → take the role from
its credential → `AssumeRole`". With both a db-level and a table-level location present, one table
path is covered by two locations and UC **cannot tell which credential (which role) to use** — so the
overlap is rejected at creation time instead.

> In one sentence: **within a bucket, keep credential granularity entirely at db level or entirely at
> table level — never mixed.**

## Onboarding a new DWSU (hot, no restart)

Each DWSU (Relyt instance) signs its JWTs with its own instance private key, and UC verifies them with
the registered public key. Onboarding one only takes appending its public key to the JWKS file
(`UC_EXTERNAL_JWKS_FILE`, default `$UC_HOME/etc/conf/relyt_jwks.json`): UC re-reads that file on every
verification, and derives trusted issuers live from each key's `issuer` member — **the change takes
effect immediately, with no UC restart and no edit to `uc.env` / `server.properties`**. This is
independent of deployment shape: bare process, docker and K8s behave the same; a K8s ConfigMap is just
one way of delivering "edit this file".

Three fields must line up, or UC returns `401`: the JWK's `issuer` == instance id == the JWT's `iss`;
the JWK's `kid` == the instance signing key's key id; `UC_AUDIENCES` == the JWT's `aud`.

1. **Export the public key** (on the new DWSU): export the **public JWK entry** from the instance
   signing key (a single object with `kty`/`crv`/`kid`/`x`/`y` plus `issuer`) and save it as
   `<instance-id>.jwk.json`. Relyt operators run `decrypt_uc_instance_key.sh <instance-id>` on the
   instance master and take the `jwks-entry` from its output. **The private key stays on the instance
   — do not copy it out.**
2. **Register** (on the UC host; the script de-duplicates by `kid`, is idempotent, and writes via an
   atomic replace so in-flight requests never read a half-written file):
   ```bash
   cd deploy
   ./uc_add_jwks_key.sh <instance-id>.jwk.json "$UC_EXTERNAL_JWKS_FILE" <instance-id>
   ```
   Passing a full `{"keys":[...]}` document is rejected — pass the single JWK object.
3. **Verify** (on that DWSU):
   ```sql
   SELECT * FROM relyt_get_external_schema_tables('<catalog>.<schema>');
   ```
   Listing the tables proves signing, verification and UC authorization all work. On `401` /
   `Invalid issuer` / `Token verification failed`, check the JWKS entry's `issuer` / `kid` against the
   instance signing key's issuer / key id.

**Offboarding a DWSU:** delete that `kid`'s entry from the JWKS `keys` array — also effective immediately.

Notes:

- **Do not** add the new instance id to `UC_ALLOWED_ISSUERS`. That setting is a startup snapshot
  (changing it needs a restart), and trusted issuers already come from the JWKS; Relyt deployments do
  not need it.
- In containers, mount the JWKS file **as a directory**, not as a single file (docker single-file
  bind-mount, K8s `subPath`): the atomic replace swaps the file inode, and a single-file mount will
  never see the update.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ERROR: required variable not set: X` | `X` is missing in `uc.env`; see [Configuration](#configuration-ucenv) |
| `ERROR: env file not found` | run from `deploy/`, or `cp uc.env.example uc.env` first |
| `ERROR: $UC_HOME/bin/start-uc-server not found` | `UC_HOME` points somewhere that is not a UC install root — it must hold the binaries as well as the state |
| Server does not start, sbt build errors | JDK older than 17, see [Prerequisites](#prerequisites) |
| `curl` on the port is refused / times out | the server is not up — check the console output and `$UC_HOME/etc/logs/server.log` |
| `curl` returns `200` instead of `401` | authorization is off; set `UC_AUTHORIZATION=enable` and restart |
| UC returns `401`, `Invalid issuer` or `Token verification failed` | the JWKS entry's `issuer`/`kid` do not match the instance, or `UC_AUDIENCES` differs from the token's `aud`; see [Onboarding a new DWSU](#onboarding-a-new-dwsu-hot-no-restart) |
| Everything worked, then every token fails after a restart | `etc/conf` was not persisted, so the signing keys were regenerated; see [`etc/conf` must be persisted](#etcconf-must-be-persisted-as-a-whole) |
| Creating an external location fails with an overlap error | a db-level and a table-level location overlap; see [Registering storage location credentials](#registering-storage-location-credentials-important) |
| OSS access denied when reading a table | the role in the credential lacks `oss:ListObjects` on the bucket (with an `oss:Prefix` condition) — `oss:GetObject` alone is not enough |
| After a tag upgrade the old build still runs | the whole `UC_HOME` is mounted as a volume, shadowing the image binaries; mount only `etc/conf` and `etc/db` |

## Security notes

- **`uc.env` is gitignored**, so real secrets are never committed.
- The rendered `server.properties` / `hibernate.properties` contain real values and are **runtime
  artifacts**: keep the copies in the repository as placeholders and **do not commit rendered
  versions** (scrub before pushing, or
  `git update-index --skip-worktree etc/conf/server.properties`).
- UC currently **stores credentials in plaintext** (`// TODO: encrypt the credential` in the code) →
  the H2 file / database needs **encryption at rest plus strict access control**.
- The admin service token in `etc/conf/token.txt` **never expires**, and a restart writes a new one
  without invalidating the old (the signing keys persist). Treat it as a privileged credential;
  revoking it means rotating the keys in `etc/conf` and restarting.
- Registering/rotating an instance public key (script provided in this directory, full steps under
  [Onboarding a new DWSU](#onboarding-a-new-dwsu-hot-no-restart)):
  `./uc_add_jwks_key.sh <public_key.jwk.json> $UC_EXTERNAL_JWKS_FILE <issuer>`
  (the third argument binds the issuer to that key, so UC only uses it to verify tokens from that issuer).


## UI sign-in

The UI requires sign-in. Each way in has an address of its own; the application's address is not one
of them, and without a session it redirects to `<ui>/login`. Which ways exist is decided by the
server's configuration at runtime — the UI asks `GET /auth/providers` on load, so one UI build serves
every deployment:

| Address | Who | Enabled by |
|---|---|---|
| **`<ui>/login`** — the default page, offering **Sign in with Microsoft** | employees, with their M365 account | the four `UC_AUTHORIZATION_URL` … `UC_CLIENT_SECRET` settings |
| **`<ui>/login/admin`** — a password form, deliberately not linked from the login page | whoever runs the server | `UC_ADMIN_PASSWORD` |
| **`<ui>/login/token`** — paste an access token this server issued | whoever runs the server | always available |

All three end in the same `UC_TOKEN` cookie, so nothing downstream distinguishes them. After sign-in the
catalog / schema / table lists are filtered to what that user has been granted (`filterCatalogs` and
friends run server-side); the administrator, as metastore owner, sees everything.

`deploy-uc.sh` warns when authorization is on but neither entry point is configured, since nobody
could sign in.

**Signing in with an access token.** Whoever runs the server can sign in with the token it writes
to `etc/conf/token.txt` at `<ui>/login/token`, pasting it into the form or opening
`<ui>/login/token?token=<token>`; the page drops the token from the address bar once it is used.
The server verifies the token the way it verifies any request and hands it back as the session
cookie, so the session ends when the token does.

Earlier versions had the UI server inject that token into every API call instead, which made the
UI's own address a way into an authorized server with no sign-in at all. The UI server now adds no
credentials of its own.

### Microsoft Entra ID (M365)

#### 1. Entra side

Register one application for the UI:

| Item | |
|---|---|
| Application registration → `tenant id`, `client id`, `client secret`. The first two sit side by side on the app's overview page and are **different** GUIDs: `Directory (tenant) ID` identifies the organization and goes into the two URLs; `Application (client) ID` identifies this app and goes into `UC_CLIENT_ID` | ✅ |
| **`email` optional claim** — without it accounts cannot be matched to UC users | ✅ |
| Redirect URI: `<browser-facing UC URL>/api/1.0/unity-control/auth/callback` | ✅ |

Who can do this: a member of the tenant can register an application and create its secret while the
tenant leaves "users can register applications" and user consent on, which many organizations turn
off. The scopes UC asks for — `openid`, `profile`, `email` — need no administrator consent by
themselves, but an application that requires assignment does. Ask the tenant administrator unless
the customer says otherwise; it is a ten-minute job, and it also settles who owns the application
and therefore who rotates its secret.

The tenant id and both endpoint URLs are public. Given the customer's email domain you can read them
without signing in to anything:

```bash
curl -s https://login.microsoftonline.com/<customer-domain>/v2.0/.well-known/openid-configuration
```

The `issuer` field states the tenant GUID, and `authorization_endpoint` / `token_endpoint` are the
two URLs below.

#### 2. UC side (`uc.env`)

```bash
UC_AUTHORIZATION_URL=https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/authorize
UC_TOKEN_URL=https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token
UC_CLIENT_ID=<ui-client-id>
UC_CLIENT_SECRET=<ui-client-secret>
UC_ACCESS_TOKEN_TTL=PT12H
```

`deploy-uc.sh` derives the rest from those four and prints each addition, leaving whatever is
already configured in place:

| Derived | From | When |
|---|---|---|
| `UC_AUDIENCES` += the client id | `UC_CLIENT_ID` | always; an id_token is addressed to the application |
| `UC_ALLOWED_ISSUERS` += `https://login.microsoftonline.com/<tenant-id>/v2.0` | `UC_AUTHORIZATION_URL` | only when it names a tenant GUID on the v2.0 endpoint |

A v1.0 endpoint issues as `https://sts.windows.net/<tenant-id>/`, a tenant named by domain does not
state its GUID, and `common` / `organizations` issue per tenant. The script does not guess those: it
warns and points at the provider's `.well-known/openid-configuration`, whose `issuer` field is the
value to put in `UC_ALLOWED_ISSUERS`.

The static JWKS for Relyt instances keeps working alongside this: an issuer with a key in the JWKS
file is verified from the file, any other trusted issuer through OIDC discovery.

Multi-tenant (`common` / `organizations`) endpoints advertise the literal issuer template
`https://login.microsoftonline.com/{tenantid}/v2.0`; UC accepts the real tenant GUID in that position.
`UC_ALLOWED_ISSUERS` is still an exact match, so list each tenant's issuer.

The UI proxy forwards `X-Forwarded-Proto`/`X-Forwarded-Host` so UC can build the callback URL the
browser actually uses; set `UC_EXTERNAL_URL` if another proxy in front strips them.

#### 3. Provisioning users

There is no just-in-time provisioning: every employee who should be able to sign in must exist in UC
first (`POST /scim2/Users`) with their **real Microsoft email, byte-for-byte equal to the token's
`email` claim**. UC matches a token by `email`, then `preferred_username` / `upn` (Entra's sign-in
name), then `sub` — all against the user's email. Grants are then given to that UC user like any
other. Deleting and recreating a user drops every grant (grants are stored against the user's UUID).

#### 4. The client secret expires

Entra issues client secrets with an end date — 6, 12 or 24 months, whichever the application's owner
picked — and says nothing when it approaches. Know the blast radius before the day arrives.

**What stops, and what does not.** The secret is used in exactly one request: the server redeeming
the authorization code for an id_token inside `/auth/callback`. Everything else is untouched:

| Path | On an expired secret |
|---|---|
| Programs reading and writing through Relyt | unaffected — the coordinator authenticates with its instance key from the local JWKS file, never through Entra |
| Spark reading metadata with a UC token | unaffected, same reason |
| People already signed in to the UI | keep working until their token expires (`UC_ACCESS_TOKEN_TTL`, `PT12H` by default): `UC_TOKEN` is issued and verified by UC itself, statelessly |
| Signing in again with a Microsoft account | **fails** |
| `<ui>/login/admin` and `<ui>/login/token` | unaffected — neither touches Entra, so whoever runs the server is never locked out |

**What it looks like, and the trap in diagnosing it.** The failure lands *after* Entra has
authenticated the person: they sign in at Microsoft successfully and the error appears on the way
back. The browser shows

```
Identity provider rejected the authorization code: 401 Unauthorized (invalid_client, AADSTS7000222)
```

The code in brackets is what separates the three causes that all fail here with the same status: a
lapsed secret (`AADSTS7000222`), a redirect URI that no longer matches the registration
(`AADSTS50011`), and an authorization code already used (`AADSTS54005`). The provider's full
description, with its correlation id, is logged at `WARN` — read the server log before touching the
configuration.

**Rotating, without interrupting a single sign-in.** An application may hold two valid secrets at
once, so the order matters more than the timing:

1. In Entra, under *Certificates & secrets*, create the new secret **before the old one lapses**. Both are now valid.
2. Put it in `UC_CLIENT_SECRET` and re-render (`deploy-uc.sh`).
3. **Restart the UC server.** This step cannot be skipped: `ServerProperties` reads the file once at startup and never reloads it, so an edited `server.properties` does nothing until the process restarts.
4. Sign in with a Microsoft account to confirm the new secret works.
5. Delete the old secret in Entra.

The restart is invisible to people already signed in — their cookie is verified statelessly and
survives it. Only new sign-ins are refused, for as long as the process takes to come back.

**Operational advice.** Choose the maximum lifetime Entra offers (24 months) and record the end date
somewhere that gets read, because nothing will remind you. For a real alert, poll the application's
`passwordCredentials[].endDateTime` through the Graph API and warn a month ahead.

Note the current limit: UC supports a client secret only. There is no configuration for a
certificate or a federated credential, and the code exchange always sends a `client_secret` form
field. If your security policy forbids long-lived static secrets, that is a code change worth
tracking as its own issue rather than a configuration option waiting to be found.

#### 5. Verifying the permission filter

Sign in as two Microsoft accounts with different grants and confirm the catalog / schema / table
lists differ. The filter is server-side, so no UI configuration is involved.

### Administrator sign-in

Set `UC_ADMIN_PASSWORD` and open `<ui>/login/admin`. The username is the built-in `admin`. The
password is stored in the rendered `server.properties` alongside the other secrets and compared in
constant time; a failed attempt is delayed by a second, which slows but does not stop guessing — use
a long random value and keep the UI behind your usual network controls.

### How programs reach UC

Customer programs do not sign in with Microsoft identities. They go through Relyt, whose coordinator
authenticates to UC with its instance key registered in the static JWKS file (see [Onboarding a new
DWSU](#onboarding-a-new-dwsu-hot-no-restart)); Spark reads metadata directly with a UC token. The
Microsoft integration above is for people using the UI.
