#!/usr/bin/env bash
# Copyright 2026 Query Farm LLC - https://query.farm
#
# Run this repo's sqllogictest suite (test/sql/*.test) against the vgi-tika
# VGI worker, using a prebuilt standalone `haybarn-unittest` and the signed
# community `vgi` extension — no C++ build from source. See ci/README.md.
#
# Required environment:
#   HAYBARN_UNITTEST   path to the haybarn-unittest binary
#   VGI_TIKA_WORKER    worker LOCATION the .test files attach (a stdio command
#                      such as `java -jar /abs/path/vgi-tika-<ver>-all.jar`, or
#                      an http:// URL)
# Optional:
#   STAGE              scratch dir for the preprocessed test tree (default: mktemp)
set -euo pipefail

: "${HAYBARN_UNITTEST:?path to the haybarn-unittest binary}"
: "${VGI_TIKA_WORKER:?worker LOCATION (stdio command or http:// URL)}"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
STAGE="${STAGE:-$(mktemp -d)}"

echo "Staging preprocessed tests into $STAGE ..."
mkdir -p "$STAGE/test/sql"
for f in "$REPO"/test/sql/*.test; do
  awk -f "$HERE/preprocess-require.awk" "$f" > "$STAGE/test/sql/$(basename "$f")"
done

# The .test files reference committed fixtures under test/sql/data via relative
# paths (e.g. 'test/sql/data/hello.pdf'); stage them alongside the preprocessed
# tests so the runner (which cd's into $STAGE) resolves them.
if [ -d "$REPO/test/sql/data" ]; then
  cp -R "$REPO/test/sql/data" "$STAGE/test/sql/data"
fi

cd "$STAGE"

# Warm the extension cache once: vgi from the signed community channel. A miss
# here is only a warning — but it is what provisions the signed `vgi`
# extension so each test file's explicit `LOAD vgi;` succeeds on a clean runner.
echo "Warming the extension cache (vgi from community) ..."
mkdir -p "$STAGE/test"
cat > "$STAGE/test/_warm.test" <<'EOF'
# name: test/_warm.test
# group: [warm]
statement ok
INSTALL vgi FROM community;
EOF
"$HAYBARN_UNITTEST" "test/_warm.test" >/dev/null 2>&1 || echo "::warning::extension warm step did not fully succeed"
rm -f "$STAGE/test/_warm.test"

# Run the whole suite in one invocation, streaming the runner's native
# sqllogictest report. Any failed assertion exits non-zero and fails the job.
echo "Running suite (worker: $VGI_TIKA_WORKER) ..."
"$HAYBARN_UNITTEST" "test/sql/*"
