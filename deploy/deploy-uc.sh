#!/usr/bin/env bash
# Render UC config from templates + uc.env, then start the UC server.
#
#   cp uc.env.example uc.env   # then fill in real values (mainly UC_HOME + Aliyun creds)
#   ./deploy-uc.sh             # render + start (foreground)
#
# Everything stateful lives UNDER UC_HOME: the rendered server.properties / hibernate.properties,
# the JWKS file, and the H2 metastore DB. Point UC_HOME at a persistent (cloud-disk) path and
# nothing is lost on restart.
#
# Env overrides: UC_ENV_FILE (default ./uc.env). Extra args are passed through to start-uc-server.
# --render-only renders the config and exits without starting the server.
set -euo pipefail

# Pull --render-only out of the args before anything else touches them: it must set UC_RENDER_ONLY
# but never itself reach start-uc-server. (The ${args[@]+...} form, not a bare "${args[@]}", is
# needed because bash 3.2 under `set -u` treats an empty array's expansion as unbound.)
UC_RENDER_ONLY="${UC_RENDER_ONLY:-}"
args=()
for a in "$@"; do
  case "$a" in
    --render-only) UC_RENDER_ONLY=1 ;;
    *) args+=("$a") ;;
  esac
done
set -- "${args[@]+"${args[@]}"}"

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
: "${UC_PORT:=8088}"                              # matches bin/start-uc-with-ui.sh and the e2e suites

# Derive the Entra OAuth endpoints from the tenant id unless explicitly overridden. These are
# consumed by the CLI, which reads the rendered server.properties directly, so they must be real
# values in the file rather than derived inside the server.
if [ -n "${UC_ENTRA_TENANT_ID:-}" ]; then
  UC_AUTHORIZATION_URL="${UC_AUTHORIZATION_URL:-https://login.microsoftonline.com/${UC_ENTRA_TENANT_ID}/oauth2/v2.0/authorize}"
  UC_TOKEN_URL="${UC_TOKEN_URL:-https://login.microsoftonline.com/${UC_ENTRA_TENANT_ID}/oauth2/v2.0/token}"
fi
UC_ENTRA_TENANT_ID="${UC_ENTRA_TENANT_ID:-}"
UC_CLIENT_ID="${UC_CLIENT_ID:-}"
UC_CLIENT_SECRET="${UC_CLIENT_SECRET:-}"
UC_AUTHORIZATION_URL="${UC_AUTHORIZATION_URL:-}"
UC_TOKEN_URL="${UC_TOKEN_URL:-}"

# Validate required values (paths are derived, so only real config/secrets are required).
missing=0
for v in UC_HOME ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY ALIYUN_MASTER_ROLE_ARN \
         UC_AUDIENCES; do
  if [ -z "${!v:-}" ]; then echo "ERROR: required variable not set: $v"; missing=1; fi
done
[ "$missing" -eq 0 ] || { echo "Fill the missing variables in $ENV_FILE and re-run."; exit 1; }

[ -d "$UC_HOME" ] || { echo "ERROR: UC_HOME does not exist: $UC_HOME"; exit 1; }
[ -x "$UC_HOME/bin/start-uc-server" ] || { echo "ERROR: $UC_HOME/bin/start-uc-server not found"; exit 1; }
[ -f "$UC_EXTERNAL_JWKS_FILE" ] || echo "WARN: JWKS file not found yet: $UC_EXTERNAL_JWKS_FILE (register keys before serving per-user traffic)"

# Substitute ${VAR} placeholders in a template -> output file (only the known keys).
export UC_AUTHORIZATION UC_ALLOWED_ISSUERS UC_EXTERNAL_JWKS_FILE UC_AUDIENCES UC_ACCESS_TOKEN_TTL \
       UC_ENTRA_TENANT_ID UC_CLIENT_ID UC_CLIENT_SECRET UC_AUTHORIZATION_URL UC_TOKEN_URL \
       ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY ALIYUN_MASTER_ROLE_ARN UC_DB_FILE
render() {
  local tpl="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  python3 - "$tpl" "$out" <<'PY'
import os, sys
tpl, out = sys.argv[1], sys.argv[2]
keys = ["UC_AUTHORIZATION", "UC_ALLOWED_ISSUERS", "UC_EXTERNAL_JWKS_FILE", "UC_AUDIENCES",
        "UC_ACCESS_TOKEN_TTL", "UC_ENTRA_TENANT_ID", "UC_CLIENT_ID", "UC_CLIENT_SECRET",
        "UC_AUTHORIZATION_URL", "UC_TOKEN_URL", "ALIYUN_REGION", "ALIYUN_ACCESS_KEY",
        "ALIYUN_SECRET_KEY", "ALIYUN_MASTER_ROLE_ARN", "UC_DB_FILE"]
s = open(tpl, encoding="utf-8").read()
for k in keys:
    s = s.replace("${%s}" % k, os.environ.get(k, ""))
open(out, "w", encoding="utf-8").write(s)
PY
}

render "$SP_TEMPLATE" "$UC_SERVER_PROPERTIES"
render "$HB_TEMPLATE" "$UC_HIBERNATE_PROPERTIES"

# A placeholder that survives rendering means a key is missing from the lists above. Left alone it
# would be read by the server as a literal value, so fail loudly instead.
for rendered in "$UC_SERVER_PROPERTIES" "$UC_HIBERNATE_PROPERTIES"; do
  if grep -q '\${' "$rendered"; then
    echo "ERROR: unsubstituted placeholder in $rendered:" >&2
    grep -n '\${' "$rendered" >&2
    exit 1
  fi
done

mkdir -p "$(dirname "$UC_DB_FILE")"   # ensure the H2 dir exists under UC_HOME
echo "Rendered:"
echo "  server.properties    -> $UC_SERVER_PROPERTIES (contains real secrets; do not commit)"
echo "  hibernate.properties -> $UC_HIBERNATE_PROPERTIES (H2 at $UC_DB_FILE)"

if [ "${UC_RENDER_ONLY:-}" = "1" ]; then
  echo "Render-only mode: not starting the server."
  exit 0
fi

# Start UC server (foreground). Extra args are passed through; an explicit -p/--port in "$@" wins
# (commons-cli keeps the FIRST occurrence, so we must not append our default after a user-supplied one).
cd "$UC_HOME"
port_given=0
for a in "$@"; do
  case "$a" in -p|--port|--port=*) port_given=1; break ;; esac
done
echo "Starting Unity Catalog server from $UC_HOME ..."
if [ "$port_given" -eq 1 ]; then
  exec bin/start-uc-server "$@"
else
  echo "  listening on port $UC_PORT (override with UC_PORT or --port)"
  exec bin/start-uc-server --port "$UC_PORT" "$@"
fi
