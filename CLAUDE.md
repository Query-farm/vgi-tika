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

## SDK dependency & CI (self-contained via Maven Central)

The worker depends on the VGI Java SDK `farm.query:vgi:0.4.0` (the worker/catalog
API; pulls in `farm.query:vgirpc:0.10.2` transitively — declared explicitly since
the code imports `farm.query.vgirpc.*`). **It's on Maven Central**, so the build
is fully self-contained: no sibling checkout, no `mavenLocal`, no composite build.
`.github/workflows/test.yml` is a single `build-and-test` job that resolves from
Central, runs JUnit + shadowJar + an HTTP boot smoke test + `make test-sql`.

- **Artifact rename:** the old local SNAPSHOT was `farm.query:vgi-core`; the
  Central release is `farm.query:vgi`. Same Java packages (`farm.query.vgi.*`),
  so imports are unchanged.
- **Keep the SDK version aligned with the published `vgi` DuckDB extension.** The
  0.x records gained components over time — `TableInitParams` picked up `atUnit`,
  `atValue`, `storage`; `TableInOutInitParams` picked up `storage` (the
  "late_materialization" / 31-vs-30 skew that an older SDK couldn't satisfy).
  Pinning the current release (0.4.0) is what fixes it — there is no local patch.
  The in-process test driver (`TestSupport`, `ExtractAllTest`) constructs these
  records directly, so an SDK bump can require appending trailing `null`s there;
  get the exact constructor with `javap -cp <vgi jar> farm.query.vgi.table.TableInitParams`.

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
