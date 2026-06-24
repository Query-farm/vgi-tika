#!/usr/bin/env bash
# Copyright 2026 Query Farm LLC - https://query.farm
#
# Run this repo's sqllogictest suite (test/sql/*.test) against the vgi-tika
# VGI worker, using a prebuilt standalone `haybarn-unittest` and the signed
# community `vgi` extension — no C++ build from source. See ci/README.md.
#
# Parameterized by TRANSPORT (the same suite, a different VGI transport):
#   subprocess  (default) — VGI_TIKA_WORKER is a stdio command; DuckDB spawns
#                           the fat JAR per attach and speaks Arrow-IPC over its
#                           stdio. cwd of the spawned worker is the stage dir.
#   http        — we start the fat JAR once in `--http` mode bound to a fixed
#                 high port, wait for `/health` to return 200, then point
#                 VGI_TIKA_WORKER at http://127.0.0.1:<port>.
#   unix        — we start the fat JAR once in `--unix <sock>` (AF_UNIX
#                 launcher) mode, wait for the socket to appear, then point
#                 VGI_TIKA_WORKER at unix://<sock>.
#
# For http/unix the worker is started by THIS script (not by DuckDB), so it is
# launched with cwd = the stage dir — the .test files reference fixtures by the
# relative path test/sql/data/*, which the worker resolves against its own cwd.
# (The stage dir is where we cp the committed fixtures.)
#
# Required environment:
#   HAYBARN_UNITTEST   path to the haybarn-unittest binary
#   VGI_TIKA_WORKER    for subprocess: the stdio command (e.g.
#                      `java -jar /abs/path/vgi-tika-<ver>-all.jar`).
#                      For http/unix it is computed here, so set
#                      VGI_TIKA_WORKER_JAR (or VGI_TIKA_WORKER as the bare jar
#                      command) instead — see below.
# Optional:
#   TRANSPORT          subprocess | http | unix          (default: subprocess)
#   VGI_TIKA_WORKER_JAR  abs path to the fat JAR (required for http/unix; if
#                        unset, derived from VGI_TIKA_WORKER by stripping a
#                        leading `java -jar `).
#   HTTP_PORT          fixed port for http mode           (default: 18110)
#   STAGE              scratch dir for the preprocessed test tree (default: mktemp)
set -euo pipefail

TRANSPORT="${TRANSPORT:-subprocess}"

: "${HAYBARN_UNITTEST:?path to the haybarn-unittest binary}"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
STAGE="${STAGE:-$(mktemp -d)}"

echo "Transport: $TRANSPORT"

# For the http leg, inject `INSTALL httpfs FROM core; LOAD httpfs;` after each
# `LOAD vgi;` — the vgi HTTP transport routes worker RPC through DuckDB's httpfs
# (an http:// ATTACH without it throws "VGI HTTP transport requires the httpfs
# extension"). Harmless no-op on subprocess/unix, so we only inject on http.
AWK_TRANSPORT="$TRANSPORT"

echo "Staging preprocessed tests into $STAGE ..."
mkdir -p "$STAGE/test/sql"
for f in "$REPO"/test/sql/*.test; do
  awk -v transport="$AWK_TRANSPORT" -f "$HERE/preprocess-require.awk" "$f" \
    > "$STAGE/test/sql/$(basename "$f")"
done

# The full suite runs on ALL THREE transports — no per-transport gate. The
# table-in-out `extract_all` was made HTTP-safe by giving its per-exchange state
# only serializable fields (the non-serializable TikaEngine + Arrow Schema are
# transient + rebuilt; the schema is persisted as bytes): over http the framework
# CBOR-serializes the state into the continuation token between exchanges, so a
# non-serializable field previously made `/init` return 500 "state serialize
# failed". See ExtractAllFunction.State and ci/README.md.

# The .test files reference committed fixtures under test/sql/data via relative
# paths; stage them alongside the preprocessed tests so the runner (which cd's
# into $STAGE) and the out-of-band http/unix worker (started with cwd=$STAGE)
# both resolve them.
if [ -d "$REPO/test/sql/data" ]; then
  cp -R "$REPO/test/sql/data" "$STAGE/test/sql/data"
fi

# ---------------------------------------------------------------------------
# Bring up the worker transport and export VGI_TIKA_WORKER for the .test files.
# ---------------------------------------------------------------------------
WORKER_PID=""
cleanup() {
  rc=$?
  if [ -n "$WORKER_PID" ]; then
    kill "$WORKER_PID" 2>/dev/null || true
    wait "$WORKER_PID" 2>/dev/null || true
  fi
  return "$rc"
}
trap cleanup EXIT INT TERM

# Resolve the fat JAR command for http/unix. Prefer VGI_TIKA_WORKER_JAR; else
# strip a leading `java -jar ` from VGI_TIKA_WORKER.
resolve_jar() {
  if [ -n "${VGI_TIKA_WORKER_JAR:-}" ]; then
    printf '%s' "$VGI_TIKA_WORKER_JAR"
    return
  fi
  if [ -n "${VGI_TIKA_WORKER:-}" ]; then
    printf '%s' "${VGI_TIKA_WORKER#java -jar }"
    return
  fi
  echo "::error::http/unix transport needs VGI_TIKA_WORKER_JAR (or VGI_TIKA_WORKER as the jar command)" >&2
  exit 1
}

case "$TRANSPORT" in
  subprocess)
    : "${VGI_TIKA_WORKER:?worker LOCATION (stdio command) for subprocess transport}"
    echo "subprocess worker: $VGI_TIKA_WORKER"
    ;;

  http)
    JAR="$(resolve_jar)"
    PORT="${HTTP_PORT:-18110}"
    echo "Starting fat JAR in --http mode on 127.0.0.1:$PORT (cwd=$STAGE) ..."
    ( cd "$STAGE" && exec java -jar "$JAR" --http --host 127.0.0.1 --port "$PORT" ) \
      > "$STAGE/worker-http.log" 2>&1 &
    WORKER_PID=$!

    ready=""
    for _ in $(seq 1 60); do
      if ! kill -0 "$WORKER_PID" 2>/dev/null; then
        echo "::error::http worker exited during startup; log follows:" >&2
        cat "$STAGE/worker-http.log" >&2 || true
        exit 1
      fi
      if curl -fsS "http://127.0.0.1:$PORT/health" -o /dev/null 2>/dev/null; then
        ready=1
        break
      fi
      sleep 0.5
    done
    if [ -z "$ready" ]; then
      echo "::error::http worker /health never returned 200; log follows:" >&2
      cat "$STAGE/worker-http.log" >&2 || true
      exit 1
    fi
    echo "http worker healthy on port $PORT"
    export VGI_TIKA_WORKER="http://127.0.0.1:$PORT"
    ;;

  unix)
    JAR="$(resolve_jar)"
    SOCK="${UNIX_SOCK:-$STAGE/vgi-tika.sock}"
    echo "Starting fat JAR in --unix mode on $SOCK (cwd=$STAGE) ..."
    ( cd "$STAGE" && exec java -jar "$JAR" --unix "$SOCK" ) \
      > "$STAGE/worker-unix.log" 2>&1 &
    WORKER_PID=$!

    ready=""
    for _ in $(seq 1 60); do
      if ! kill -0 "$WORKER_PID" 2>/dev/null; then
        echo "::error::unix worker exited during startup; log follows:" >&2
        cat "$STAGE/worker-unix.log" >&2 || true
        exit 1
      fi
      if [ -S "$SOCK" ]; then
        ready=1
        break
      fi
      sleep 0.5
    done
    if [ -z "$ready" ]; then
      echo "::error::unix worker socket never appeared; log follows:" >&2
      cat "$STAGE/worker-unix.log" >&2 || true
      exit 1
    fi
    echo "unix worker listening on $SOCK"
    export VGI_TIKA_WORKER="unix://$SOCK"
    ;;

  *)
    echo "::error::unknown TRANSPORT '$TRANSPORT' (want subprocess|http|unix)" >&2
    exit 1
    ;;
esac

cd "$STAGE"

# Warm the extension cache once: vgi from the signed community channel, plus
# httpfs from core (the vgi HTTP transport requires httpfs for DuckDB's HTTP
# client). A miss here is only a warning — but it is what provisions the signed
# extensions so each test file's explicit `LOAD vgi;` / injected `LOAD httpfs;`
# succeed on a clean runner. httpfs is harmless for the subprocess/unix legs.
echo "Warming the extension cache (vgi from community, httpfs from core) ..."
mkdir -p "$STAGE/test"
cat > "$STAGE/test/_warm.test" <<'EOF'
# name: test/_warm.test
# group: [warm]
statement ok
INSTALL vgi FROM community;

statement ok
INSTALL httpfs FROM core;
EOF
"$HAYBARN_UNITTEST" "test/_warm.test" >/dev/null 2>&1 || echo "::warning::extension warm step did not fully succeed"
rm -f "$STAGE/test/_warm.test"

# Run the whole suite in one invocation, streaming the runner's native
# sqllogictest report. Any failed assertion exits non-zero and fails the job.
#
# SILENT-SKIP GUARD: DuckDB's sqllogictest runner auto-SKIPS (exit 0!) any test
# whose error message contains "HTTP" or "Unable to connect" — so a broken http
# setup would report "All tests were skipped" and go green while testing nothing.
# Capture the output and fail the leg unless the runner reports tests PASSED.
echo "Running suite (transport: $TRANSPORT, worker: $VGI_TIKA_WORKER) ..."
OUT="$STAGE/suite.out"
set +e
"$HAYBARN_UNITTEST" "test/sql/*" 2>&1 | tee "$OUT"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then
  echo "::error::suite failed (exit $status) on transport $TRANSPORT" >&2
  exit "$status"
fi
if ! grep -q 'All tests passed' "$OUT"; then
  echo "::error::runner did not report 'All tests passed' on transport $TRANSPORT" >&2
  echo "::error::(no positive pass confirmation — treating as a failure)." >&2
  exit 1
fi
# Any skip is a fake-pass on the leg under test: the DuckDB/Haybarn runner skips
# (exit 0) a test whose error matches its built-in 'HTTP'/'Unable to connect'
# allowlist — including the httpfs-missing "VGI HTTP transport requires the
# httpfs extension" error. On a clean Linux CI runner the signed extensions
# install and NOTHING should be skipped, so treat any skip as a failure.
# (NB: a local macOS dev box may skip here because the signed community/httpfs
# build for your platform can't download — Linux CI is the source of truth. Set
# ALLOW_SKIPS=1 to tolerate that locally.)
if grep -Eq 'skipped tests?|tests were skipped' "$OUT"; then
  if [ "${ALLOW_SKIPS:-0}" = "1" ]; then
    echo "::warning::runner SKIPPED some tests on transport '$TRANSPORT' (tolerated via ALLOW_SKIPS=1)" >&2
    grep -A3 'Skipped tests for the following reasons' "$OUT" >&2 || true
  else
    echo "::error::runner SKIPPED some tests on transport '$TRANSPORT' — the built-in" >&2
    echo "::error::network-error skip (matches 'HTTP'/'Unable to connect') swallowed a" >&2
    echo "::error::real error. This is NOT a clean pass." >&2
    grep -A3 'Skipped tests for the following reasons' "$OUT" >&2 || true
    if [ -f "$STAGE/worker-http.log" ]; then
      echo "----- worker-http.log (server-side error the runner masked) -----" >&2
      cat "$STAGE/worker-http.log" >&2 || true
      echo "----- end worker-http.log -----" >&2
    fi
    exit 1
  fi
fi
echo "Suite passed on transport $TRANSPORT."
