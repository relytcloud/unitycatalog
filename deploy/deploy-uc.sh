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

# Validate required values (paths are derived, so only real config/secrets are required).
missing=0
for v in UC_HOME ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY ALIYUN_MASTER_ROLE_ARN \
         UC_ALLOWED_ISSUERS UC_AUDIENCES; do
  if [ -z "${!v:-}" ]; then echo "ERROR: required variable not set: $v"; missing=1; fi
done
[ "$missing" -eq 0 ] || { echo "Fill the missing variables in $ENV_FILE and re-run."; exit 1; }

[ -d "$UC_HOME" ] || { echo "ERROR: UC_HOME does not exist: $UC_HOME"; exit 1; }
[ -x "$UC_HOME/bin/start-uc-server" ] || { echo "ERROR: $UC_HOME/bin/start-uc-server not found"; exit 1; }
[ -f "$UC_EXTERNAL_JWKS_FILE" ] || echo "WARN: JWKS file not found yet: $UC_EXTERNAL_JWKS_FILE (register keys before serving per-user traffic)"

# Substitute ${VAR} placeholders in a template -> output file (only the known keys).
export UC_AUTHORIZATION UC_ALLOWED_ISSUERS UC_EXTERNAL_JWKS_FILE UC_AUDIENCES UC_ACCESS_TOKEN_TTL \
       ALIYUN_REGION ALIYUN_ACCESS_KEY ALIYUN_SECRET_KEY ALIYUN_MASTER_ROLE_ARN UC_DB_FILE
render() {
  local tpl="$1" out="$2"
  mkdir -p "$(dirname "$out")"
  python3 - "$tpl" "$out" <<'PY'
import os, sys
tpl, out = sys.argv[1], sys.argv[2]
keys = ["UC_AUTHORIZATION", "UC_ALLOWED_ISSUERS", "UC_EXTERNAL_JWKS_FILE", "UC_AUDIENCES",
        "UC_ACCESS_TOKEN_TTL", "ALIYUN_REGION", "ALIYUN_ACCESS_KEY", "ALIYUN_SECRET_KEY",
        "ALIYUN_MASTER_ROLE_ARN", "UC_DB_FILE"]
s = open(tpl, encoding="utf-8").read()
for k in keys:
    s = s.replace("${%s}" % k, os.environ.get(k, ""))
open(out, "w", encoding="utf-8").write(s)
PY
}

render "$SP_TEMPLATE" "$UC_SERVER_PROPERTIES"
render "$HB_TEMPLATE" "$UC_HIBERNATE_PROPERTIES"
mkdir -p "$(dirname "$UC_DB_FILE")"   # ensure the H2 dir exists under UC_HOME
echo "Rendered:"
echo "  server.properties    -> $UC_SERVER_PROPERTIES (contains real secrets; do not commit)"
echo "  hibernate.properties -> $UC_HIBERNATE_PROPERTIES (H2 at $UC_DB_FILE)"

# Start UC server (foreground). Pass --port etc. through.
cd "$UC_HOME"
echo "Starting Unity Catalog server from $UC_HOME ..."
exec bin/start-uc-server "$@"
