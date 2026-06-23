<p align="center">
  <img src="https://raw.githubusercontent.com/Query-farm/vgi/main/docs/vgi-logo.png" alt="Vector Gateway Interface (VGI)" width="320">
</p>

<p align="center"><em>A <a href="https://query.farm">Query.Farm</a> VGI worker for DuckDB.</em></p>

# vgi-tika

[![test](https://github.com/Query-farm/vgi-tika/actions/workflows/test.yml/badge.svg)](https://github.com/Query-farm/vgi-tika/actions/workflows/test.yml)

A [VGI](https://query.farm) worker that brings [Apache Tika](https://tika.apache.org/)
into DuckDB/SQL: extract **text, metadata, language, and OCR** from PDF, DOCX, PPTX,
XLSX, HTML, EML/MSG, RTF, ODF, and images — all as SQL table and scalar functions.

Written in **Java** because Tika + PDFBox + POI + Tesseract is a JVM stack with no
equal in any other language; the document-ingest primitive for every RAG / search
pipeline on DuckDB.

```sql
INSTALL vgi FROM community; LOAD vgi;
ATTACH 'tika' (TYPE vgi, LOCATION 'java -jar /path/to/vgi-tika-all.jar');

-- one document -> one row of text + metadata
SELECT content, meta['Content-Type'] AS mime, n_pages
FROM tika.extract('/docs/report.pdf');

-- a whole directory of files, joined back by path
SELECT f.path, t.content
FROM glob('/docs/**/*.{pdf,docx,pptx}') f
CROSS JOIN tika.extract(f.path) t;
```

## How Tika maps onto SQL

Tika is built around a streaming `Parser` that turns a byte stream into text plus a
metadata bag. Each capability maps to the VGI primitive that fits its data flow:

| Area | SQL surface | VGI primitive |
| --- | --- | --- |
| **Extract (one doc)** | `SELECT * FROM tika.extract(path \| bytes)` | table function (1 arg → 1 row, or N rows with `by_page := true`) |
| **Extract (a column of docs)** | `SELECT * FROM tika.extract_all((SELECT id, bytes FROM files), id := 'id')` | table-in-out (streams a column of blobs → rows) |
| **Metadata only (no text)** | `SELECT * FROM tika.metadata(path \| bytes)` | table function |
| **Detect mime / language** | `tika.detect_mime(bytes \| path)`, `tika.detect_lang(text)` | scalar |
| **OCR an image / scanned PDF** | `tika.ocr(bytes \| path, lang := 'eng+deu')` | scalar (text) |

### Conventions

- **`path` vs `bytes`** — every extractor is overloaded: pass a `VARCHAR` path (the
  worker opens the file) **or** a `BLOB`/`BINARY` value (bytes travel over Arrow).
  Prefer `bytes` when the data already lives in a DuckDB table or came from
  `read_blob()`; prefer `path` for local files to avoid moving megabytes per row.
- **`id` passthrough** on `extract_all` — the named column is excluded from
  processing and copied onto each output row so you can join results back to source.
- **`by_page := true`** — emits one row per page with a `page` column so you can
  chunk for embeddings without a second pass. For **PDFs** this is real per-page
  text splitting (PDFBox `PDFTextStripper`); other formats fall back to a single
  page row (see Limitations).
- **Per-row errors** — a corrupt file yields a row with `NULL content` and a populated
  `error` column rather than failing the whole query. Set `strict := true` to re-raise.

## Function catalog

### Extraction — `tika.extract`, `tika.extract_all`, `tika.metadata`

`extract` / `extract_all` output schema:

```
(content VARCHAR, mime VARCHAR, n_pages INT, lang VARCHAR,
 meta MAP(VARCHAR, VARCHAR), error VARCHAR)
```

`metadata()` returns the same minus `content`. With `by_page := true`, `extract`
prepends a `page INT` column. `extract_all` prepends the passthrough `id` column.

```sql
-- chunk a PDF into per-page rows ready for an embedding worker
SELECT page, content
FROM tika.extract('/docs/contract.pdf', by_page := true);

-- pull author + creation date out of a column of stored bytes, keep the id
SELECT id, meta['dc:creator'] AS author, meta['dcterms:created'] AS created
FROM tika.extract_all((SELECT id, body FROM uploaded_files), id := 'id');
```

Signatures:

```
extract(doc BLOB|VARCHAR, by_page := false, lang := 'eng', ocr := false, strict := false)
metadata(doc BLOB|VARCHAR, strict := false)
extract_all(input TABLE, id := '', doc_column := '', ocr := false, lang := 'eng')
```

`extract_all`'s `doc_column` defaults to the first non-`id` column of the input.

### Detection — scalars

```
detect_mime(doc BLOB|VARCHAR)  -> VARCHAR    -- Tika media-type detection
detect_lang(text VARCHAR)       -> VARCHAR    -- ISO-639 code, or NULL if unknown
detect_lang_conf(text VARCHAR)  -> DOUBLE     -- confidence (0.0-1.0) of detect_lang, or NULL
```

```sql
SELECT detect_mime(body) AS mime, count(*) FROM files GROUP BY 1;
SELECT detect_lang(content), detect_lang_conf(content) FROM tika.extract('/docs/report.pdf');
```

`detect_lang_conf` is the companion confidence score for `detect_lang` — use it to
threshold low-confidence detections (e.g. `WHERE detect_lang_conf(content) > 0.5`).

### OCR — `tika.ocr`

```
ocr(doc BLOB|VARCHAR, lang := 'eng') -> VARCHAR
```

Tesseract via Tika's `TesseractOCRParser`; `lang` is a `+`-joined trained-data list
(e.g. `'eng+spa'`). **Guarded by availability:** if the `tesseract` binary is not on
`PATH`, every row returns `NULL` rather than failing the query. Scanned PDFs are
auto-OCR'd inside `extract(..., ocr := true)` when no text layer is found.

```sql
SELECT tika.ocr(page_image, lang := 'eng+spa') FROM scanned_pages;
```

## Build

```sh
./gradlew test          # JUnit tests (real PDF/DOCX fixtures via PDFBox/POI)
./gradlew shadowJar      # -> build/libs/vgi-tika-<version>-all.jar  (a runnable fat JAR)
```

Run the worker directly (VGI `LOCATION` is just a launcher command):

```sh
java -jar build/libs/vgi-tika-*-all.jar              # stdio transport (default)
java -jar build/libs/vgi-tika-*-all.jar --http --port 8000   # HTTP transport
```

### The VGI Java SDK

The build depends on the VGI Java SDK — `farm.query:vgi` (the worker/catalog API;
pulls in `farm.query:vgirpc` transitively) — which is **published to Maven
Central**. There is nothing to set up: a clean checkout builds and tests with no
sibling repos, no `mavenLocal`, and no composite build. The SDK version is pinned
in `build.gradle.kts`; keep it aligned with the `vgi` DuckDB extension version
you run against (a worker built on an older SDK can miss protocol fields a newer
extension expects).

## Dependencies & licensing

- `org.apache.tika:tika-core` + `tika-parsers-standard-package` +
  `tika-langdetect-optimaize` — **Apache-2.0**. Pulls PDFBox, POI, jackcess, etc.
  (all Apache-2.0). The shaded JAR is ~85 MB.
- **OCR:** Tesseract must be installed and on `PATH` (system binary); trained
  language data is installed separately. OCR functions degrade to `NULL` when absent.
- Worker code is under the [MIT License](LICENSE); all bundled deps are permissive.

### Fat-JAR packaging notes

Two things make the shaded artifact work as a standalone worker; both are wired in
`build.gradle.kts`:

- **SPI merge.** Tika 3.x splits its parsers across ~27 `tika-parser-*-module` jars
  that each declare the same `org.apache.tika.parser.Parser` service. Shadow's
  `mergeServiceFiles()` alone collapsed them to a single entry (only
  `CompositeExternalParser` survived), which silently made body extraction return
  empty text while MIME detection still worked. The `generateMergedSpi` task
  pre-concatenates every dependency's `META-INF/services/*` into the shaded jar so
  all ~85 parser implementations register.
- **No stdout pollution.** The stdio transport speaks Arrow IPC over stdout, so a
  single stray byte there corrupts the protocol and hangs the worker. Some Tika
  modules use the Log4j 2 API; with no provider, Log4j's `StatusLogger` prints to
  **stdout**. We add `log4j-to-slf4j` to route Log4j → SLF4J → `slf4j-simple`
  (stderr). The manifest also sets `Add-Opens: java.base/java.nio` so a bare
  `java -jar` works without the caller passing `--add-opens` (Arrow needs it).

## Testing

```sh
make test        # JUnit (./gradlew test) + SQL E2E
make test-unit   # JUnit only
make test-sql    # fat JAR + regenerate fixtures + haybarn-unittest over test/sql/*
```

The SQL E2E suite (`test/sql/*.test`) runs the real functions inside DuckDB via
[`haybarn-unittest`](https://pypi.org/project/haybarn-unittest/) with the fat JAR
as the VGI `LOCATION`. Install the runner once with `uv tool install haybarn-unittest`
and put `~/.local/bin` on `PATH`. Fixtures under `test/sql/data/` (a known-text PDF,
a 3-page PDF, a DOCX, an English text sample, and a corrupt PDF) are reproducible
from the JUnit PDFBox/POI builders via `make fixtures` (Gradle `generateSqlFixtures`).

> Note: under `haybarn-unittest`, `require vgi` *skips* the file — the `.test`
> files use an explicit `LOAD vgi;` instead.

## Limitations

- **`by_page`** does real per-page text splitting for **PDFs** (PDFBox
  `PDFTextStripper`). For non-PDF formats (DOCX/PPTX/XLSX/HTML/…) Tika exposes no
  page-boundary signal, so `by_page` falls back to a single row carrying the whole
  body with `page = 1`. The schema is identical either way, so callers don't branch.
  PDFs parsed with `ocr := true` also fall back (OCR text has no page boundaries).

## How it's built

- `TikaEngine` — a thread-safe wrapper around Tika's `AutoDetectParser`/`Detector`,
  shared across all functions; the persistent VGI worker amortizes Tika's first-parse
  classload across queries.
- `ExtractFunction` / `MetadataFunction` — `TableFunction`s emitting one Arrow batch.
- `ExtractAllFunction` — a `TableInOutFunction` that streams a blob column to rows
  with `id` passthrough.
- `DetectMimeFunction` / `DetectLangFunction` / `OcrFunction` — `ScalarFn`s; the
  path-vs-bytes overload is a single `any`-typed input dispatched on the runtime
  Arrow vector type.
- The `meta` column is a DuckDB `MAP(VARCHAR, VARCHAR)`, written via Arrow's
  `MapVector`/`UnionMapWriter`.

---

## Authorship & License

Written by [Query.Farm](https://query.farm) — every VGI worker is designed and built by Query.Farm.

Copyright 2026 Query Farm LLC - https://query.farm

