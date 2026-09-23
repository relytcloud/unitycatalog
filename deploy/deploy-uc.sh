#!/usr/bin/env bash
# Render UC config from templates + uc.env, then start the UC server and the browser UI.
#
#   cp uc.env.example uc.env   # then fill in real values (mainly UC_HOME + Aliyun creds)
#   ./deploy-uc.sh             # render + start both (foreground)
#
# Both processes come up by default; a deployment nobody can open is not a deployment. The UI needs
# ui/build, which is not in the repo: this script builds it once if it is missing and yarn is
# available. Set UC_ENABLE_UI=false for a server-only run, and UI_PORT to move the UI off 3000.
#
# Everything stateful lives UNDER UC_HOME: the rendered server.properties / hibernate.properties,
# the JWKS file, and the H2 metastore DB. Point UC_HOME at a persistent (cloud-disk) path and
# nothing is lost on restart.
#
# Env overrides: UC_ENV_FILE (default ./uc.env). Extra args are passed through to start-uc-server.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${UC_ENV_FILE:-$HERE/uc.env}"
SP_TEMPLATE="$HERE/server.properties.template"
HB_TEMPLATE="$HERE/hibernate.properties.template"

[ -f "$ENV_FILE" ] || {
  echo "ERROR: env file not found: $ENV_FILE"
  echo "       cp '$HERE/uc.env.example' '$ENV_FILE' and fill in the values."
  exit 1
}
for t in "$SP_TEMPLATE" "$HB_TEMPLATE"; do
  [ -f "$t" ] || { echo "ERROR: template not found: $t"; exit 1; }
done

# Load uc.env into the environment.
set -a; . "$ENV_FILE"; set +a

# Defaults. The user normally only sets UC_HOME (+ Aliyun creds); all paths derive from UC_HOME so
# every stateful file sits under one persistent root.
: "${UC_HOME:=$(cd "$HERE/.." && pwd)}"
: "${UC_SERVER_PROPERTIES:=$UC_HOME/etc/conf/server.properties}"
: "${UC_HIBERNATE_PROPERTIES:=$UC_HOME/etc/conf/hibernate.properties}"
: "${UC_EXTERNAL_JWKS_FILE:=$UC_HOME/etc/conf/relyt_jwks.json}"
: "${UC_DB_FILE:=$UC_HOME/etc/db/h2db}"          # H2 metastore file (no .mv.db suffix)
: "${UC_ACCESS_TOKEN_TTL:=}"                      # blank = no expiry (opt-in)
: "${UC_AUTHORIZATION:=enable}"                   # enable = require auth; disable = no auth
: "${UC_ALLOWED_ISSUERS:=}"                       # blank = trust only the issuers derived from the JWKS
: "${UC_AUTHORIZATION_URL:=}"                     # hosted login (issue #15): all four blank = off
: "${UC_TOKEN_URL:=}"
: "${UC_CLIENT_ID:=}"
: "${UC_CLIENT_SECRET:=}"
: "${UC_EXTERNAL_URL:=}"                          # blank = derive redirect_uri from the request
: "${UC_ADMIN_PASSWORD:=}"                        # blank = administrator UI sign-in off
: "${ALIYUN_MASTER_ROLE_ARN:=}"                   # blank = advertise nothing; see the WARN below
: "${UC_PORT:=8088}"                              # matches bin/start-uc-with-ui.sh and the e2e suites
: "${UC_ENABLE_UI:=true}"                         # on by default; anything else = server only
: "${UI_PORT:=3000}"                              # browser-facing UI port, only read when UI is on

# Accept exactly the spellings bin/start-uc-with-ui.sh accepts, so uc.env means the same thing here
# and in the container image. (The image itself still defaults to OFF: other consumers of it expect
# a lone server. A deployment driven from here is meant to be usable, so it defaults to ON.)
ui_on=""
case "$UC_ENABLE_UI" in 1 | true | TRUE | yes | on) ui_on=1 ;; esac

# The UI is served from ui/build, which the repo does not carry. Build it once rather than starting
# half a deployment: the UI process would exit on the missing directory and take the server with it.
if [ -n "$ui_on" ] && [ ! -f "$UC_HOME/ui/build/index.html" ]; then
  if command -v yarn >/dev/null 2>&1; then
    echo "UI assets not built yet; building once (a few minutes) ..."
    (cd "$UC_HOME/ui" && yarn install --frozen-lockfile && yarn build) || {
      echo "ERROR: building the UI failed. Fix it, or set UC_ENABLE_UI=false to start the server alone."
      exit 1
    }
  else
    echo "ERROR: the UI needs $UC_HOME/ui/build, which is missing, and yarn is not installed."
    echo "       Build it on a machine that has yarn (cd ui && yarn install && yarn build) and copy"
    echo "       ui/build across, or set UC_ENABLE_UI=false to start the server alone."
    exit 1
  fi
fi

# Validate required values (paths are derived, so only real config/secrets are required).
missing=0
for v in UC_HOME ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY UC_AUDIENCES; do
  if [ -z "${!v:-}" ]; then echo "ERROR: required variable not set: $v"; missing=1; fi
done
[ "$missing" -eq 0 ] || { echo "Fill the missing variables in $ENV_FILE and re-run."; exit 1; }

[ -d "$UC_HOME" ] || { echo "ERROR: UC_HOME does not exist: $UC_HOME"; exit 1; }
[ -x "$UC_HOME/bin/start-uc-server" ] || { echo "ERROR: $UC_HOME/bin/start-uc-server not found"; exit 1; }
[ -f "$UC_EXTERNAL_JWKS_FILE" ] || echo "WARN: JWKS file not found yet: $UC_EXTERNAL_JWKS_FILE (register keys before serving per-user traffic)"
# Not required to start or to vend credentials: the server only echoes it back as a credential's
# unity_catalog_ram_arn, so the caller knows which principal to trust in their own RAM role.
[ -n "$ALIYUN_MASTER_ROLE_ARN" ] || \
  echo "WARN: ALIYUN_MASTER_ROLE_ARN is blank -- credentials will not advertise which RAM principal UC calls AssumeRole as"

# Check what the UI needs before anything starts. Worth failing early: start-uc-with-ui.sh ties the
# two processes together, so a UI that cannot boot takes the server down with it and the visible
# symptom is a server that exits seconds after coming up.
if [ -n "$ui_on" ]; then
  [ -f "$UC_HOME/ui/build/index.html" ] || {
    echo "ERROR: the UI is enabled but its build is missing: $UC_HOME/ui/build"
    echo "       Build it once (ui/build is deliberately not in the repo):"
    echo "         cd '$UC_HOME/ui' && yarn install && yarn build"
    echo "       Or set UC_ENABLE_UI=false in $ENV_FILE to run the server on its own."
    exit 1
  }
  command -v node >/dev/null 2>&1 || {
    echo "ERROR: the UI is enabled but 'node' is not on PATH; the UI server is a Node process."
    echo "       Install Node, or set UC_ENABLE_UI=false in $ENV_FILE to run the server on its own."
    exit 1
  }
fi
login_set=0; login_blank=0
for v in UC_AUTHORIZATION_URL UC_TOKEN_URL UC_CLIENT_ID UC_CLIENT_SECRET; do
  if [ -n "${!v}" ]; then login_set=1; else login_blank=1; fi
done
[ "$login_set" -eq 1 ] && [ "$login_blank" -eq 1 ] && \
  echo "WARN: hosted login needs all of UC_AUTHORIZATION_URL/UC_TOKEN_URL/UC_CLIENT_ID/UC_CLIENT_SECRET; only some are set, so it stays OFF"
[ "$UC_AUTHORIZATION" = "enable" ] && [ "$login_set" -eq 0 ] && [ -z "$UC_ADMIN_PASSWORD" ] && \
  echo "WARN: authorization is on but neither Microsoft sign-in nor UC_ADMIN_PASSWORD is set: nobody can sign in to the UI"

# UC_EXTERNAL_URL overrides what the server would otherwise derive, and two things depend on it:
# the redirect_uri handed to the identity provider, which must equal the registered one byte for
# byte, and whether the session cookie is marked Secure. A path here silently breaks the first; the
# property's own validator only checks that the value parses as a URL, so check the shape.
if [ -n "$UC_EXTERNAL_URL" ] && ! [[ "$UC_EXTERNAL_URL" =~ ^https?://[^/]+$ ]]; then
  echo "ERROR: UC_EXTERNAL_URL must be scheme://host[:port] with no path, e.g. https://uc.example.com"
  echo "       got: $UC_EXTERNAL_URL"
  exit 1
fi
[ -n "$ui_on" ] && [ "$UC_AUTHORIZATION" != "enable" ] && \
  echo "WARN: authorization is off and the UI is on, so the UI asks nobody to sign in. Keep port $UI_PORT on a trusted network -- never expose it to the internet."

# An identity provider's tokens are only accepted when its issuer is trusted and the token's
# audience is expected. Both are lists that the Relyt chain already uses, so the provider's values
# are appended to what is configured rather than replacing it, and each addition is printed.
append_to_list() {  # append_to_list VAR value
  local var="$1" value="$2" current existing
  current="${!var}"   # assigned separately: `local` does not have `var` in scope for ${!var} yet
  local IFS=','
  for existing in $current; do
    if [ "${existing// /}" = "$value" ]; then
      return 0   # already configured, by us or by whoever wrote the file
    fi
  done
  printf -v "$var" '%s' "${current:+$current,}$value"
  echo "  $var += $value"
}

if [ "$login_set" -eq 1 ] && [ "$login_blank" -eq 0 ]; then
  echo "Microsoft sign-in is configured; deriving what the token check needs:"
  # The id_token is addressed to the application, so the client id is the audience to expect.
  append_to_list UC_AUDIENCES "$UC_CLIENT_ID"
  # The issuer is only derived where the authorization URL states it unambiguously: a tenant GUID
  # on the v2.0 endpoint. A v1.0 endpoint issues as sts.windows.net, a tenant named by domain does
  # not name its GUID, and common/organizations issue a per-tenant value -- guessing any of those
  # would put a value nobody wrote into the configuration and fail later as an untrusted issuer.
  if [[ "$UC_AUTHORIZATION_URL" =~ ^https://login\.microsoftonline\.com/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/oauth2/v2\.0/authorize/?$ ]]; then
    append_to_list UC_ALLOWED_ISSUERS "https://login.microsoftonline.com/${BASH_REMATCH[1]}/v2.0"
  else
    echo "WARN: cannot derive the issuer from UC_AUTHORIZATION_URL ($UC_AUTHORIZATION_URL)."
    echo "      Add the provider's issuer to UC_ALLOWED_ISSUERS yourself. It is the \"issuer\" field of"
    echo "      <authority>/.well-known/openid-configuration, e.g."
    echo "      https://login.microsoftonline.com/<tenant-id>/v2.0 for the Microsoft v2.0 endpoint."
  fi
fi

# Substitute ${VAR} placeholders in a template -> output file (only the known keys).
export UC_AUTHORIZATION UC_ALLOWED_ISSUERS UC_EXTERNAL_JWKS_FILE UC_AUDIENCES UC_ACCESS_TOKEN_TTL \
       UC_AUTHORIZATION_URL UC_TOKEN_URL UC_CLIENT_ID UC_CLIENT_SECRET UC_EXTERNAL_URL UC_ADMIN_PASSWORD \
       ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY ALIYUN_MASTER_ROLE_ARN UC_DB_FILE
render() {
  local tpl="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  python3 - "$tpl" "$out" <<'PY'
import os, sys
tpl, out = sys.argv[1], sys.argv[2]
keys = ["UC_AUTHORIZATION", "UC_ALLOWED_ISSUERS", "UC_EXTERNAL_JWKS_FILE", "UC_AUDIENCES",
        "UC_ACCESS_TOKEN_TTL", "UC_AUTHORIZATION_URL", "UC_TOKEN_URL", "UC_CLIENT_ID",
        "UC_CLIENT_SECRET", "UC_EXTERNAL_URL", "UC_ADMIN_PASSWORD", "ALIYUN_REGION", "ALIYUN_ACCESS_KEY",
        "ALIYUN_SECRET_KEY", "ALIYUN_MASTER_ROLE_ARN", "UC_DB_FILE"]
s = open(tpl, encoding="utf-8").read()
for k in keys:
    s = s.replace("${%s}" % k, os.environ.get(k, ""))
open(out, "w", encoding="utf-8").write(s)
PY
}

# Rendering writes real secrets. With UC_HOME left at the install root, that is the checked-out
# copy of etc/conf, and the next `git add` picks it up: say so rather than leaving it to be noticed
# in a diff. A real deployment points UC_HOME at a persistent path outside any work tree, where
# this check finds nothing.
if command -v git >/dev/null 2>&1 \
  && git -C "$(dirname "$UC_SERVER_PROPERTIES")" ls-files --error-unmatch \
       "$UC_SERVER_PROPERTIES" >/dev/null 2>&1; then
  echo "WARN: $UC_SERVER_PROPERTIES is tracked by git, and it is about to hold real secrets."
  echo "      Point UC_HOME at a path outside the repository, or restore the file when you are done:"
  echo "      git checkout -- etc/conf/server.properties etc/conf/hibernate.properties"
fi

render "$SP_TEMPLATE" "$UC_SERVER_PROPERTIES"
render "$HB_TEMPLATE" "$UC_HIBERNATE_PROPERTIES"
mkdir -p "$(dirname "$UC_DB_FILE")"   # ensure the H2 dir exists under UC_HOME
echo "Rendered:"
echo "  server.properties    -> $UC_SERVER_PROPERTIES (contains real secrets; do not commit)"
echo "  hibernate.properties -> $UC_HIBERNATE_PROPERTIES (H2 at $UC_DB_FILE)"

# Start UC server (foreground). Extra args are passed through; an explicit -p/--port in "$@" wins
# (commons-cli keeps the FIRST occurrence, so we must not append our default after a user-supplied one).
cd "$UC_HOME"
port_given=0
for a in "$@"; do
  case "$a" in -p|--port|--port=*) port_given=1; break ;; esac
done
if [ -n "$ui_on" ]; then
  # bin/start-uc-with-ui.sh owns both processes: it waits for the server to write its admin token,
  # starts the UI against the server's own port (UC_PORT+1, the Armeria port the transcoder fronts),
  # and takes one down when the other exits. It reads the port from UC_PORT, so a --port in "$@"
  # would leave the UI proxying to a server that is not there -- refuse instead of half-starting.
  [ "$port_given" -eq 0 ] || {
    echo "ERROR: with the UI enabled, set the port through UC_PORT, not --port."
    exit 1
  }
  echo "Starting Unity Catalog server + UI from $UC_HOME ..."
  echo "  server on port $UC_PORT, UI on port $UI_PORT -- browse to the UI"
  exec env UC_ENABLE_UI=true UC_HOME="$UC_HOME" UC_PORT="$UC_PORT" UI_PORT="$UI_PORT" \
    bin/start-uc-with-ui.sh "$@"
fi

echo "Starting Unity Catalog server from $UC_HOME ..."
if [ "$port_given" -eq 1 ]; then
  exec bin/start-uc-server "$@"
else
  echo "  listening on port $UC_PORT (override with UC_PORT or --port)"
  exec bin/start-uc-server --port "$UC_PORT" "$@"
fi
