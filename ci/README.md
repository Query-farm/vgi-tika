# CI: the vgi-tika worker integration suite

[`.github/workflows/test.yml`](../.github/workflows/test.yml) runs the Java unit
tests, builds the fat JAR, and runs this repo's sqllogictest suite
(`test/sql/*.test`) against the vgi-tika VGI worker through the **real DuckDB
`vgi` extension** on every push / PR — once per VGI transport.

## How it works (no C++ build)

Rather than building the vgi DuckDB extension from source, CI drives a
**prebuilt** standalone `haybarn-unittest` (the DuckDB/Haybarn sqllogictest
runner, published in Haybarn's releases) and installs the **signed** `vgi`
extension from the Haybarn community channel:

1. **Build the worker** — `./gradlew shadowJar` produces a self-contained fat
   JAR at `build/libs/vgi-tika-<ver>-all.jar`. Its manifest sets `Add-Opens`, so
   a bare `java -jar <jar>` is a self-contained stdio worker the extension can
   spawn as a subprocess (the VGI LOCATION), and the same JAR serves the HTTP and
   AF_UNIX transports via `--http` / `--unix`.
2. **Download the runner** — the `haybarn_unittest-linux-amd64.zip` asset from
   the latest Haybarn release (resolved once in the `resolve-haybarn` job so the
   version is never hardcoded).
3. **Preprocess** — the standalone runner links none of the extensions the
   tests gate on, so [`preprocess-require.awk`](preprocess-require.awk) rewrites
   each `require <ext>` into an explicit signed `INSTALL <ext> FROM
   {community,core}; LOAD <ext>;`. On the **http** leg it also injects
   `INSTALL httpfs FROM core; LOAD httpfs;` after each `LOAD vgi;` (see below).
   `require-env` and everything else pass through untouched.
4. **Run** — [`run-integration.sh`](run-integration.sh) stages the preprocessed
   tree plus the committed `test/sql/data` fixtures, brings up the worker on the
   chosen transport, points `VGI_TIKA_WORKER` at it, **warms the extension cache
   once** (`INSTALL vgi FROM community;` + `INSTALL httpfs FROM core;`) so each
   test file's explicit `LOAD vgi;` / injected `LOAD httpfs;` succeeds, then runs
   the suite in a single `haybarn-unittest` invocation. Any failed assertion
   exits non-zero and fails the job.

## Transport matrix

The `vgi` extension chooses the transport from the worker `LOCATION` string, so
the **same suite** exercises a different transport just by changing what
`VGI_TIKA_WORKER` resolves to. `run-integration.sh` is parameterized by a
`TRANSPORT` env var (default `subprocess`), and the `integration` CI job runs it
once per transport (`strategy.matrix.transport: [subprocess, http, unix]`):

| `TRANSPORT`  | `VGI_TIKA_WORKER`       | how the worker is started | readiness gate |
|--------------|-------------------------|---------------------------|----------------|
| `subprocess` | `java -jar <jar>`       | DuckDB spawns it per attach (Arrow-IPC over stdio) | n/a |
| `http`       | `http://127.0.0.1:<p>`  | this script boots `java -jar <jar> --http --host 127.0.0.1 --port <p>` (default 18110) | poll `GET /health` until HTTP 200 |
| `unix`       | `unix://<sock>`         | this script boots `java -jar <jar> --unix <sock>` | wait for the AF_UNIX socket file to appear |

The vgi Java SDK's `Worker.runFromArgs` (farm.query:vgi) supports these flags
directly: `--http`/`--host`/`--port` (it serves `/health`) and `--unix <path>`
(the AF_UNIX launcher), plus an optional `--idle-timeout`.

For `http`/`unix` the worker is started **by this script** rather than by
DuckDB, so it is launched with **cwd = the stage dir** — the `.test` files
reference fixtures by the relative path `test/sql/data/*`, which the worker
resolves against its own cwd. (For `subprocess`, DuckDB spawns the worker with
that same cwd.) In all three cases the committed fixtures are staged into the
stage dir, so fixture resolution is identical across transports.

The suite is transport-agnostic and **runs in full on all three transports** —
no per-transport gate. The table functions (`extract`, `metadata`) emit their
whole result in one `produceTick` then `finish()`, and the scalars are plain
request/response.

### HTTP-serializable table-in-out state (the one real http fix)

The `extract_all` **table-in-out** initially failed only over **http** with a
masked `HTTP 500` at `/init`. Over http the worker is stateless across exchanges,
so the framework CBOR-serializes each function's per-exchange state into an opaque
continuation token between exchanges (`StateSerializer` walks the declared fields,
skipping `static`/`transient`/`synthetic`). The original
`ExtractAllFunction.State` held a `final TikaEngine` and a `final` Arrow `Schema`
— neither is CBOR-serializable — so serializing the state threw and the worker
returned 500 `state serialize failed: …ExtractAllFunction$State`. The DuckDB
extension surfaced that as `HTTP 500 for HTTP POST to '…/init'`, whose "HTTP" the
sqllogictest runner then *silently skipped* (see the guard below), which is why
it masqueraded as a partial-green http leg.

The fix is the standard "externalize the state" pattern: `State` now persists only
serializable fields — the output schema as a `byte[]` (via `SchemaUtil`), plus the
scalar `docColumn`/`idColumn`/`ocr`/`lang` — and keeps the non-serializable
`TikaEngine` and the rebuilt Arrow `Schema` as `transient` fields rebuilt lazily
on the worker, with a no-arg constructor for the deserializer. (The plain
`extract`/`metadata` producer state was already http-safe — its `TikaEngine` was
already `transient`.) With that, the **full suite — including the `meta`
`MAP(VARCHAR,VARCHAR)` column and the `extract_all` table-in-out — runs green over
subprocess, http, and unix.** Arrow Map columns and table-in-out streaming both
traverse the vgi HTTP transport fine; the only requirement is serializable
exchange state.

### httpfs is required for the HTTP transport

The `vgi` extension's HTTP transport uses DuckDB's HTTP client, which lives in
the **`httpfs`** extension — without it, ATTACH over an `http://` LOCATION raises
`VGI HTTP transport requires the httpfs extension`. On the standalone
`haybarn-unittest` runner, extension auto-load/auto-install is off, so `httpfs`
must be installed and loaded explicitly. On the **http** leg only,
`preprocess-require.awk` injects `INSTALL httpfs FROM core; LOAD httpfs;` after
each `LOAD vgi;`, and the warm step installs it once. `httpfs` is unused by the
subprocess/unix transports, so it is not injected there.

### Silent-skip guard

Some `haybarn-unittest` builds carry a built-in `skip on error_message matching
'HTTP'` (and `'Unable to connect'`) rule that **silently skips** (exit 0) a
`.test` file whenever any error string contains those substrings — including the
`httpfs`-missing `VGI HTTP transport requires the httpfs extension` error. A
broken http setup would therefore report "All tests were skipped" and the job
would go GREEN having run nothing. `run-integration.sh` captures the runner
output and **fails the leg** unless it sees `All tests passed` and does not see
`All tests were skipped`.

## Run it locally

```bash
./gradlew shadowJar                         # build the fat JAR
JAR="$PWD/build/libs/$(ls build/libs | grep -- -all.jar | head -1)"

# subprocess (default):
HAYBARN_UNITTEST=/path/to/haybarn-unittest \
VGI_TIKA_WORKER="java -jar $JAR" \
  ci/run-integration.sh

# http / unix: the script boots the JAR itself, so give it the JAR path:
HAYBARN_UNITTEST=/path/to/haybarn-unittest TRANSPORT=http \
VGI_TIKA_WORKER_JAR="$JAR" ci/run-integration.sh
HAYBARN_UNITTEST=/path/to/haybarn-unittest TRANSPORT=unix \
VGI_TIKA_WORKER_JAR="$JAR" ci/run-integration.sh
```

Or use `make test-sql`, which builds the JAR and runs the (subprocess) suite via
a `haybarn-unittest` already on PATH.
