#!/usr/bin/env bash
# Entrypoint for the Unity Catalog image (see uc.dockerfile).
#
# Always runs the UC server. When UC_ENABLE_UI is truthy it ALSO runs the
# bundled UI server (ui/server.js: serves ui/build and proxies /api to the
# local UC server with the admin token injected) as a second process in the
# SAME container — allinone sets UC_ENABLE_UI=true. When it is unset/false the
# image behaves exactly as before (UC server only), so other consumers of this
# image are unaffected.
#
# tini is PID 1 (ENTRYPOINT ["tini","-g","--", ...]); `-g` forwards signals to
# the whole process group so `docker stop` tears both processes down cleanly.
# start-uc-server runs java in the foreground (it does not exec), so the UC
# process is started under its own session via setsid: that lets us signal the
# script AND its java child as one group.
set -uo pipefail

UC_PORT="${UC_PORT:-8088}"
UI_PORT="${UI_PORT:-3000}"
UC_HOME="${UC_HOME:-/opt/unitycatalog}"
cd "$UC_HOME"

# --- UI disabled: original single-process behaviour (exec = server is PID) ---
case "${UC_ENABLE_UI:-}" in
  1 | true | TRUE | yes | on) ;;
  *) exec bin/start-uc-server --port "$UC_PORT" ;;
esac

# --- UI enabled: two processes in one container ---
setsid bin/start-uc-server --port "$UC_PORT" &
UC_PID=$!

# The server writes its admin token on first start; wait for it (bounded).
TOKEN_FILE="$UC_HOME/etc/conf/token.txt"
for _ in $(seq 1 90); do
  [ -s "$TOKEN_FILE" ] && break
  kill -0 "$UC_PID" 2>/dev/null || {
    echo "[uc-ui] UC server exited before writing $TOKEN_FILE" >&2
    exit 1
  }
  sleep 2
done
[ -s "$TOKEN_FILE" ] || {
  echo "[uc-ui] timed out waiting for $TOKEN_FILE" >&2
  kill -- "-$UC_PID" 2>/dev/null || true
  exit 1
}

UC_TARGET="http://localhost:${UC_PORT}" UC_TOKEN_FILE="$TOKEN_FILE" \
  PORT="$UI_PORT" HOST=0.0.0.0 node ui/server.js &
UI_PID=$!
echo "[uc-ui] UI on :${UI_PORT} -> UC :${UC_PORT}"

shutdown() {
  trap - TERM INT
  kill -- "-$UC_PID" 2>/dev/null || true # UC process group (start-uc-server + java)
  kill "$UI_PID" 2>/dev/null || true
}
trap shutdown TERM INT

# Exit as soon as either process dies; take the other down with it so the
# container stops (and gets restarted / reported) instead of running half-up.
wait -n
code=$?
shutdown
wait 2>/dev/null || true
exit "$code"
