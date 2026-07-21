#!/bin/sh
# Copyright 2026 Query Farm LLC - https://query.farm
#
# Dispatch the single vgi-tika image into one of its transports:
#   http   (default) the HTTP server on $PORT (8000), bound 0.0.0.0 so a
#                    published host port reaches it. Serves /health.
#   stdio            a worker DuckDB spawns over stdio (on-host execution).
#   unix <sock>      the AF_UNIX launcher on <sock> (defaults to
#                    $VGI_UNIX_SOCK or /tmp/vgi-tika.sock).
# Any other first argument is exec'd verbatim (escape hatch for debugging).
#
# The VGI Java SDK's Worker.runFromArgs selects the transport from these flags:
#   (no flags)                    -> stdio (Arrow-IPC over stdin/stdout)
#   --http --host H --port P       -> HTTP server (serves GET /health)
#   --unix <sock>                  -> AF_UNIX launcher
#
# The worker is stateless (per-call Tika extraction), so there is no /data to
# create and no state env to wire — each mode just exec's the fat JAR.
set -e

JAR=/app/worker.jar

case "${1:-http}" in
  http)
    shift 2>/dev/null || true
    # Bind 0.0.0.0 on a FIXED port so `-p $PORT:$PORT` and the HEALTHCHECK reach it.
    exec java -jar "$JAR" --http --host 0.0.0.0 --port "${PORT:-8000}" "$@"
    ;;
  stdio)
    shift 2>/dev/null || true
    exec java -jar "$JAR" "$@"
    ;;
  unix)
    shift 2>/dev/null || true
    SOCK="${1:-${VGI_UNIX_SOCK:-/tmp/vgi-tika.sock}}"
    [ $# -gt 0 ] && shift 2>/dev/null || true
    exec java -jar "$JAR" --unix "$SOCK" "$@"
    ;;
  *)
    exec "$@"
    ;;
esac
