#!/usr/bin/env bash
#
# Tests for build/sbt-launch-lib.bash.
#
# Run directly: dev/test-sbt-launch-lib.sh
#
# The launcher jar is gitignored, so every CI job fetches it from Maven Central
# before sbt can start. A single transient failure there fails the whole job, so
# the fetch must survive one.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="${REPO_ROOT}/build/sbt-launch-lib.bash"
SBT_VERSION="$(awk -F "=" '/sbt\.version/ {print $2}' "${REPO_ROOT}/project/build.properties")"

failures=0
server_pid=""
workspaces=()

cleanup() {
  [[ -n "${server_pid}" ]] && kill "${server_pid}" 2>/dev/null
  for dir in "${workspaces[@]+"${workspaces[@]}"}"; do
    rm -rf "${dir}"
  done
}
trap cleanup EXIT

fail() {
  echo "  FAIL: $*"
  failures=$((failures + 1))
}

pass() {
  echo "  PASS: $*"
}

wait_for_port() {
  local port_file="$1" what="$2"
  for _ in $(seq 1 50); do
    [[ -s "${port_file}" ]] && return 0
    sleep 0.1
  done
  echo "${what} failed to start" >&2
  return 1
}

# Serves $1 failing responses (HTTP 503) before serving the jar successfully.
start_flaky_server() {
  local fail_count="$1" jar="$2" port_file="$3"

  python3 - "${fail_count}" "${jar}" "${port_file}" <<'FLAKY' &
import http.server, socketserver, sys

fail_count, jar_path, port_file = int(sys.argv[1]), sys.argv[2], sys.argv[3]
hits = {"n": 0}

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def do_GET(self):
        hits["n"] += 1
        if hits["n"] <= fail_count:
            self.send_response(503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with open(jar_path, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("127.0.0.1", 0), Handler) as httpd:
    with open(port_file, "w") as f:
        f.write(str(httpd.server_address[1]))
    httpd.serve_forever()
FLAKY
  server_pid=$!
  # Keep the shell from reporting the job when cleanup kills it.
  disown "${server_pid}" 2>/dev/null || true
  wait_for_port "${port_file}" "flaky server"
}

# Resets $1 connections before serving the jar successfully. A reset is not an
# HTTP status, so plain --retry does not cover it.
start_resetting_server() {
  local fail_count="$1" jar="$2" port_file="$3"

  python3 - "${fail_count}" "${jar}" "${port_file}" <<'RESET' &
import socket, struct, sys

fail_count, jar_path, port_file = int(sys.argv[1]), sys.argv[2], sys.argv[3]

sock = socket.socket()
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("127.0.0.1", 0))
sock.listen(8)

with open(port_file, "w") as f:
    f.write(str(sock.getsockname()[1]))

seen = 0
while True:
    conn, _ = sock.accept()
    seen += 1
    if seen <= fail_count:
        # SO_LINGER with a zero timeout makes close() send RST, not FIN.
        conn.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        conn.close()
        continue
    conn.recv(65535)
    with open(jar_path, "rb") as f:
        body = f.read()
    conn.sendall(b"HTTP/1.0 200 OK\r\nContent-Length: %d\r\n\r\n" % len(body) + body)
    conn.close()
RESET
  server_pid=$!
  disown "${server_pid}" 2>/dev/null || true
  wait_for_port "${port_file}" "resetting server"
}

# acquire_sbt_jar needs project/build.properties and a build/ directory relative
# to the working directory.
make_workspace() {
  local dir
  dir="$(mktemp -d)"
  workspaces+=("${dir}")
  mkdir -p "${dir}/project" "${dir}/build"
  cp "${LIB}" "${dir}/build/sbt-launch-lib.bash"
  echo "sbt.version=${SBT_VERSION}" > "${dir}/project/build.properties"
  # Stand-in for the real launcher: acquire_sbt_jar only fetches bytes, it does
  # not inspect them.
  printf 'launcher-jar-contents' > "${dir}/upstream.jar"
  echo "${dir}"
}

# acquire_sbt_jar resolves paths relative to the working directory and calls
# `exit` on failure, so run it in a subshell rooted in the fixture.
run_acquire() {
  local dir="$1" port="$2"
  (
    cd "${dir}" || exit 1
    export DEFAULT_ARTIFACT_REPOSITORY="http://127.0.0.1:${port}"
    # shellcheck disable=SC1091
    source build/sbt-launch-lib.bash
    acquire_sbt_jar
  ) > "${dir}/out.log" 2>&1
}

check_downloaded() {
  local dir="$1" exit_code="$2" desc="$3"
  local jar="${dir}/build/sbt-launch-${SBT_VERSION}.jar"

  if [[ ${exit_code} -ne 0 ]]; then
    fail "expected exit 0 after the server recovered, got ${exit_code}"
    sed 's/^/        /' "${dir}/out.log"
  elif [[ ! -f "${jar}" ]]; then
    fail "expected ${jar##*/} to be downloaded"
  elif [[ "$(cat "${jar}")" != "launcher-jar-contents" ]]; then
    fail "downloaded jar has unexpected contents"
  else
    pass "${desc}"
  fi
}

echo "acquire_sbt_jar recovers from transient HTTP errors"
work="$(make_workspace)"
start_flaky_server 2 "${work}/upstream.jar" "${work}/port" || exit 1
run_acquire "${work}" "$(cat "${work}/port")"
check_downloaded "${work}" $? "retried past 2 responses of HTTP 503"
kill "${server_pid}" 2>/dev/null; server_pid=""

echo "acquire_sbt_jar recovers from a connection reset"
work="$(make_workspace)"
start_resetting_server 2 "${work}/upstream.jar" "${work}/port" || exit 1
run_acquire "${work}" "$(cat "${work}/port")"
check_downloaded "${work}" $? "retried past 2 connection resets"
kill "${server_pid}" 2>/dev/null; server_pid=""

echo
if [[ ${failures} -gt 0 ]]; then
  echo "${failures} test(s) failed"
  exit 1
fi
echo "All tests passed"
