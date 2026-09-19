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
proxies `/api` to the UC server and injects the admin token so the browser needs no login.

The container image entrypoint [`bin/start-uc-with-ui.sh`](../bin/start-uc-with-ui.sh) runs both
processes when `UC_ENABLE_UI` is truthy (`1`/`true`/`yes`/`on`); otherwise it runs the server alone:

```bash
UC_ENABLE_UI=true UC_PORT=8088 UI_PORT=3000 bin/start-uc-with-ui.sh
```

To run the UI next to a script-based deployment, build the assets once (`ui/build` is not committed)
and start the UI server yourself:

```bash
cd ui && yarn install && yarn build && cd ..
UC_TARGET=http://localhost:8088 \
UC_TOKEN_FILE="$UC_HOME/etc/conf/token.txt" \
PORT=3000 HOST=0.0.0.0 node ui/server.js
```

> ⚠️ **The UI injects the admin token into every API call, so anyone who can reach it is a Unity
> Catalog admin with no login.** Keep port `3000` on a trusted network; never expose it publicly.

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
| `UC_ACCESS_TOKEN_TTL` | no | lifetime of exchanged tokens (ISO-8601, e.g. `PT1H`). A **blank** value means **no expiry**; `uc.env.example` ships `PT1H` deliberately — keep it unless you have a reason not to |
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

## Microsoft Entra ID sign-in

Alongside the JWKS file, UC can trust Microsoft Entra ID (Azure AD) directly as a token-exchange
issuer, so a Microsoft 365 tenant's users can exchange an Entra ID token for a UC access token
without an instance signing key. This is separate from, and coexists with, DWSU onboarding above.

### 1. App registration

Create an app registration in the target tenant (**App registrations → New registration**). You
need two values off its **Overview** page:

- **Directory (tenant) ID** → `UC_ENTRA_TENANT_ID`
- **Application (client) ID** → `UC_CLIENT_ID`

Then create a client secret under **Certificates & secrets** → `UC_CLIENT_SECRET`. The server never
uses this secret itself — it only ever verifies signatures with Entra's public keys — but a client
running the authorization-code flow (the CLI today) needs it.

If you intend to sign in with the CLI, also register a redirect URI now — **Authentication → Add a
platform → Mobile and desktop applications** — of the form `http://localhost:<port>`, and use that
same port as `UC_REDIRECT_PORT` (see step 4). Entra matches the redirect URI of a confidential
client exactly, so the port has to be pinned on both sides.

### 2. Add `email` as an optional claim — this is required

Under **Token configuration → Add optional claim → ID**, add `email`. Without it, the ID token
carries only `sub` (an opaque GUID) and `preferred_username`; UC falls back to `sub` as the
principal, that GUID never matches a provisioned user, and every exchange for that tenant fails.
The server distinguishes this case explicitly — a token with no `email` claim fails with:

```
The subject token has no 'email' claim, so the principal fell back to 'sub'. For a
Microsoft Entra ID token, add 'email' as an optional claim on the app registration so
the token carries the address the user is provisioned under.
```

That message names the fix directly. Depending on the tenant, you may also need to grant admin
consent for the `email` claim, or configure a verified domain, before Entra will emit it.

### 3. Users must already exist in UC — there is no JIT provisioning

UC does not create users on first sign-in. Provision each user in UC via SCIM first, using the
**same address** the `email` claim carries. A resolved-but-unprovisioned subject fails with
`User not provisioned: <email>` — a distinct message from the missing-claim case above, so you
can tell "fix the app registration" apart from "provision this user" at a glance.

### 4. Configure the Entra values in `uc.env`

```
UC_ENTRA_TENANT_ID=<directory-tenant-id>
UC_CLIENT_ID=<application-client-id>
UC_CLIENT_SECRET=<client-secret-value>
# Only for CLI login; see below.
UC_REDIRECT_PORT=8020
```

**`UC_REDIRECT_PORT` is what makes CLI login against Entra possible.** It is rendered as
`server.redirect-port`, which the CLI reads to decide where its login callback listens
(`Oauth2CliExchange.findAvailablePort()`); left blank, the CLI takes a random free port instead.
Entra requires an exact redirect-URI match for a confidential client, and no registered URI can
match a random port, so CLI login needs both a fixed `UC_REDIRECT_PORT` and the matching
`http://localhost:<port>` registered as a redirect URI on the app registration (step 1). The
server itself does not use this value — leave it blank if you only exchange raw tokens.

Restart UC to pick them up (unlike the JWKS file, this is a startup snapshot, not hot-reloaded).
`deploy-uc.sh` derives the CLI's authorization/token URLs
(`https://login.microsoftonline.com/<tenant>/oauth2/v2.0/{authorize,token}`) from the tenant id
automatically; only set `UC_AUTHORIZATION_URL` / `UC_TOKEN_URL` yourself to override that.

**`UC_ALLOWED_ISSUERS` does not need the Entra issuer.** Setting `UC_ENTRA_TENANT_ID` makes the
server derive `https://login.microsoftonline.com/<tenant>/v2.0` and trust it — and accept
`UC_CLIENT_ID` as an audience — automatically, unioned on top of whatever the JWKS file and
`UC_ALLOWED_ISSUERS` already provide. Leave `UC_ALLOWED_ISSUERS` exactly as documented above.

### 5. Coexistence with the static JWKS file

The two trust sources are routed per issuer, not by "does a JWKS file exist": the static file
(`UC_EXTERNAL_JWKS_FILE`) is authoritative only for the issuers it declares (each key's `issuer`
member); every other issuer — including Entra — resolves by OIDC discovery instead. A deployment
can run DWSU token-exchange and Entra sign-in at the same time, and the
[DWSU hot-onboarding flow](#onboarding-a-new-dwsu-hot-no-restart) is unchanged.

The two sources also behave differently under the hood, by design: the JWKS file is re-read on
every verification (uncached, so a newly appended DWSU key takes effect with no restart), while for
Entra the server caches the **built key provider** per issuer for 24h — not just the discovery
document — so a token exchange normally does not re-fetch either the discovery document or Entra's
JWKS. The underlying key lookup is additionally rate limited per issuer: a burst of 10 lookups,
then one more every 6 seconds — 10 per minute. Each *successful* discovery fetch is logged at
`info` (`resolved signing keys by OIDC discovery`, once per issuer per cache window, not on every
exchange), so it is visible at the shipped `rootLogger.level = info`
default: seeing that line once per issuer and not again is the confirmation that caching works.
It reports a completed resolution on purpose: a failed discovery is never cached, so a line logged
before the fetch would repeat on every exchange for as long as the provider was down. A failing
issuer is reported by the `warn` below instead, at most once a minute per issuer.

When an issuer falls back to discovery **while `UC_EXTERNAL_JWKS_FILE` is configured**, the server
also logs a `warn` naming that issuer and the file. For a DWSU issuer that is the signal that its
JWK's `issuer` member does not match the token's `iss` (a typo reroutes it silently to discovery,
where it then fails); for the Entra issuer in a mixed deployment it is expected and harmless.

### 6. Network requirement

UC needs outbound HTTPS to `login.microsoftonline.com` to fetch Entra's OIDC discovery document and
JWKS. If that endpoint is unreachable, returns a non-2xx status, or the key lookup is rate-limited,
the token exchange fails with **503** (`UNAVAILABLE`), not 401 — an outage is reported as an outage,
not as a rejected token. A discovery request that exceeds the 5-second timeout instead fails with
**504** (`DEADLINE_EXCEEDED`). Only a genuinely unknown signing key (a `kid` that Entra itself does
not recognize) still maps to 401.

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
