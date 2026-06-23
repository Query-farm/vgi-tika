# CI: the vgi-tika worker integration suite

[`.github/workflows/test.yml`](../.github/workflows/test.yml) runs the Java unit
tests, builds the fat JAR, and runs this repo's sqllogictest suite
(`test/sql/*.test`) against the vgi-tika VGI worker through the **real DuckDB
`vgi` extension** on every push / PR.

## How it works (no C++ build)

Rather than building the vgi DuckDB extension from source, CI drives a
**prebuilt** standalone `haybarn-unittest` (the DuckDB/Haybarn sqllogictest
runner, published in Haybarn's releases) and installs the **signed** `vgi`
extension from the Haybarn community channel:

1. **Build the worker** — `./gradlew shadowJar` produces a self-contained fat
   JAR at `build/libs/vgi-tika-<ver>-all.jar`. Its manifest sets `Add-Opens`, so
   a bare `java -jar <jar>` is a self-contained stdio worker the extension can
   spawn as a subprocess (the VGI LOCATION).
2. **Download the runner** — the `haybarn_unittest-linux-amd64.zip` asset from
   the latest Haybarn release (resolved once in the `resolve-haybarn` job so the
   version is never hardcoded).
3. **Preprocess** — the standalone runner links none of the extensions the
   tests gate on, so [`preprocess-require.awk`](preprocess-require.awk) rewrites
   each `require <ext>` into an explicit signed `INSTALL <ext> FROM
   {community,core}; LOAD <ext>;`. `require-env` and everything else pass
   through untouched.
4. **Run** — [`run-integration.sh`](run-integration.sh) stages the preprocessed
   tree plus the committed `test/sql/data` fixtures, points `VGI_TIKA_WORKER` at the fat
   JAR command, **warms the extension cache once** (`INSTALL vgi FROM
   community;`) so each test file's explicit `LOAD vgi;` succeeds, then runs the
   suite in a single `haybarn-unittest` invocation. Any failed assertion exits
   non-zero and fails the job.

## Run it locally

```bash
./gradlew shadowJar                         # build the fat JAR
# point HAYBARN_UNITTEST at a haybarn-unittest binary (or `uv tool install
# haybarn-unittest`), and the worker at the fat JAR command:
JAR="$PWD/build/libs/$(ls build/libs | grep -- -all.jar | head -1)"
HAYBARN_UNITTEST=/path/to/haybarn-unittest \
VGI_TIKA_WORKER="java -jar $JAR" \
  ci/run-integration.sh
```

Or use `make test-sql`, which builds the JAR and runs the suite via a
`haybarn-unittest` already on PATH.
