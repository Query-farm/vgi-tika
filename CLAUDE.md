# CLAUDE.md — vgi-tika

Contributor/agent notes. User-facing docs live in `README.md`; this is the
"how it's built and where the sharp edges are" companion.

## What this is

A [VGI](https://query.farm) worker (Java) wrapping **Apache Tika** to extract
text / metadata / OCR from PDF, DOCX, PPTX, XLSX, HTML, email, and images, as
DuckDB SQL functions. Modeled on `vgi-trains-java`; built with Gradle (Kotlin
DSL, JDK 21) into a shaded fat JAR. Catalog name `tika` (single `main` schema).

## Layout

```
build.gradle.kts / settings.gradle.kts / gradle.properties   Gradle, shadow plugin (com.gradleup.shadow 9.4.2)
src/main/java/farm/query/vgi/tika/
  Main.java                  Worker.builder().catalogName("tika")...registerTable/registerScalar
  ExtractFunction.java       table fn: extract(path|bytes), by_page per-page splitting
  MetadataFunction.java      table fn: metadata (extract minus content)
  ExtractAllFunction.java    table-in-out over a path/blob column, id passthrough
  DetectMimeFunction.java    scalar
  DetectLangFunction.java / DetectLangConfFunction.java   scalars
  OcrFunction.java           scalar, Tesseract-guarded
  TikaEngine.java            the Tika integration (parsers, per-page, error capture)
  TikaSchemas.java / DocInput.java   Arrow schema + path-vs-bytes input dispatch
src/test/java/...            JUnit: Extraction, ErrorEdgeCase, ByPage, ExtractAll, DetectLang
                             + Fixtures.java (PDFBox/POI builders) + SqlFixtureGenerator
test/sql/*.test + data/      haybarn-unittest E2E + committed generated fixtures
Makefile                     build / fixtures / test-unit / test-sql / test / clean
```

## Sharp edges (learned the hard way — these were real shipping bugs)

1. **Fat-JAR SPI merge.** Shadow's `mergeServiceFiles()` collapsed Tika's ~27
   parser-module `META-INF/services/*` files into one entry → **body extraction
   silently returned empty text** (MIME detection still worked, so it looked
   fine). Fixed with a `generateMergedSpi` task that concatenates all service
   files (85 Parser impls register). If extraction returns empty over RPC,
   suspect the SPI merge first.
2. **Log4j corrupted the Arrow stdio stream.** Log4j's StatusLogger wrote
   "could not find a logging provider" to **stdout**, which is the Arrow-IPC
   channel for a stdio worker → protocol corruption. Fixed by adding
   `log4j-to-slf4j` (routes logging to stderr). Any dependency that writes to
   stdout will break a stdio VGI worker.
3. **`Add-Opens: java.base/java.nio`** is baked into the manifest (Arrow needs
   it); the `extract`/`metadata` doc arg is `any`-typed so a VARCHAR path binds
   from SQL.
4. **`haybarn-unittest` skips `require vgi`** — `.test` files use explicit
   `LOAD vgi;`.
5. **Per-row error capture.** A corrupt/unsupported doc yields a row with NULL
   content + an `error` column rather than failing the query; `strict := true`
   re-raises. OCR returns NULL (not a crash) when Tesseract is absent.
6. **`by_page`** does real per-page splitting for PDFs (PDFBox `PDFTextStripper`);
   non-PDF formats and OCR-mode PDFs fall back to a single page row (documented
   in the README Limitations).

## SDK dependency & CI (the fragile part)

The worker depends on the `vgi-java` SDK (`farm.query:vgi-core` + `vgirpc` +
`vgirpc-oauth`), which is a **separate repo** not on a public Maven repo.

- **Local:** resolves from `mavenLocal` (built via the sibling `vgi-java` /
  `vgi-rpc-java` checkouts' `publishToMavenLocal`). The composite-build path is
  gated behind `VGI_JAVA_COMPOSITE=1`; mavenLocal is the default.
- **Local version skew:** the published `vgi` DuckDB extension (haybarn v1.5.4)
  expects a `late_materialization` field a stale mavenLocal SDK may not emit
  (31-vs-30 function-schema mismatch → all scalar calls blocked). If E2E fails
  with a schema-count mismatch, rebuild a current `vgi-core` into mavenLocal.
  That patch is environment state — **not committed here** (CI builds the SDK
  fresh from upstream `main`, which tracks the published extension).
- **CI** (`.github/workflows/test.yml`): an always-passing `build-scripts` job
  (Gradle configuration only, no SDK) + a gated `unit-and-e2e` job that checks
  out `query-farm/vgi-java` + `vgi-rpc-java`, publishes to mavenLocal, and
  **fails loudly with `::error::`** if those checkouts are unavailable. Don't
  make CI silently skip when the SDK is missing.

## Testing

```sh
./gradlew test                # JUnit (real in-memory PDF/DOCX via PDFBox/POI)
make test-sql                 # shadowJar + fixtures + haybarn-unittest over test/sql/*
make test                     # both
```

`make test-sql` builds `build/libs/vgi-tika-*-all.jar`, sets
`VGI_TIKA_WORKER="java -jar <abs jar>"`, and runs `haybarn-unittest --test-dir .
"test/sql/*"` (install once: `uv tool install haybarn-unittest`). **The SQL
suite is authoritative** — both real bugs above (empty extraction, stdio
corruption) passed JUnit and were caught only by E2E. Fixtures are reproducible
via `make fixtures` (Gradle `generateSqlFixtures`).

## Packaging

~88 MB shaded JAR (Tika 3.3.1 + PDFBox + POI, all Apache-2.0). The README notes
GraalVM native-image as the cold-start follow-up. Tesseract (system binary) is
optional, only for `ocr` and scanned-PDF auto-OCR.
