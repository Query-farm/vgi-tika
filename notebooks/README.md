# VGI Tika notebooks

[01 · Preserve document coverage](01_preserve_document_coverage.ipynb) audits a document manifest with the real Tika worker, distinguishes missing and failed input from blank text, and selects extracted rows with a visible minimum-character rule. It keeps physical PDF pages separate from whole-document fallback rows.

## Run

Install [uv](https://docs.astral.sh/uv/) and Java 21+ on `PATH`. On macOS with Homebrew Java, add `/opt/homebrew/opt/openjdk@21/bin` if needed. The release helper uses POSIX locks on macOS/Linux. Initial setup needs network access for the Python environment, community VGI extension and approximately 78 MB release JAR. All document processing is local; the notebook explicitly disables OCR and does not need Tesseract.

```sh
uv sync --project notebooks --frozen
uv run --project notebooks --frozen python notebooks/execute.py
```

The complete directory works without the parent worker checkout:

```sh
cd notebooks
uv sync --frozen
uv run --frozen python execute.py
```

In VS Code choose `notebooks/.venv/bin/python` and use `notebooks/` as the kernel working directory. Optionally register a kernel for other Jupyter editors:

```sh
uv run --project notebooks --frozen python -m ipykernel install --user --name vgi-tika-notebooks --display-name 'Python (vgi-tika notebooks)'
```

Restart and run all after editing the parameter. VGI connection and analytical SQL remain visible; imports, plotting, assertions and cleanup source are folded. Saved outputs embed both charts.

## Experiment

```sh
uv run --project notebooks --frozen python notebooks/execute.py \
  --parameters '{"MIN_CHARS":150}' \
  --output-dir notebooks/validation/min-150
```

The default is 40 normalized characters. Runs of whitespace become one space before trimming/counting. Accepted values are integers 1–5000. The experiment changes selection and its coverage denominators, while preserving the original extraction outcomes. This is not a relevance, quality or token-budget metric.

The runner requires a separate output directory for overrides, rejects unknown parameters, and saves only successful complete executions. It copies branding and runtime companions beside experiment notebooks. Review PNGs live under ignored `validation/charts/`; the main notebook keeps embedded outputs.

## Portable worker and fixtures

The literal `ATTACH` uses the [public v0.4.0 JAR](https://github.com/Query-farm/vgi-tika/releases/tag/v0.4.0), SHA-256 `4f65055a0c97e7e94a90377bc6b469ee2d955440135e23c049112e12d3affa61`. `release_jar.py` verifies the digest both after download and before cached launches, uses a bounded lock, publishes atomically, and executes Java. Its cache is `${XDG_CACHE_HOME:-~/.cache}/vgi-notebook-jars/`. No Gradle, sibling repository or source build is required. Upgrade release and digest together, then rerun default and experiment. The notebook's separate lock pins Haybarn 2.0.0rc11.

[build_fixture.py](build_fixture.py) regenerates all authored assets using only Python's standard library:

- [field-guide.pdf](assets/field-guide.pdf): four pages with a long text layer, a short `OK` page, a blank page and a shorter checklist.
- [support-note.docx](assets/support-note.docx): two paragraphs with no reported physical page count.
- [brief.html](assets/brief.html): a short synthetic body.
- [whitespace.txt](assets/whitespace.txt): a successful text parse with zero normalized characters.
- [corrupt.pdf](assets/corrupt.pdf): deliberately incomplete PDF bytes.

The manifest also includes a deliberately missing path and SQL NULL. Run `uv run --project notebooks --frozen python notebooks/build_fixture.py` to reproduce the fixture bytes. There are no real accounts, private documents or external links.

## Interpretation

The bulk extractor processes a relation of paths and returns one result per ID, including per-row errors. The notebook preserves raw errors and NULL lengths. It does not turn errors or unavailable data into zero text. The page-level calls are deliberate additional reads of the three text-bearing fixtures; subsequent charts and SQL reuse materialized results.

With OCR disabled, PDF page rows use actual page boundaries for this fixture. DOCX and HTML return one whole-document fallback each, with unknown `n_pages`. A fallback row is not a verified physical page; the API can also fall back when PDF splitting fails or OCR is enabled. The notebook explicitly validates PDF ordinals against the document's returned page count. `n_pages` repeats on PDF page rows and must not be summed. Metadata is carried only on the first PDF page; later empty maps/NULL language fields are not document-level absence indicators.

The threshold keeps or rejects extracted rows; it does not prove the completeness or usefulness of their text. A document can remain represented while some PDF pages are omitted. The table therefore separates original manifest documents, text-bearing documents, represented documents, physical PDF pages and whole-document rows. OCR, image-only PDFs, semantic reading order, layout recovery, language confidence, and arbitrary-format completeness are outside this example.

Validation includes executed default and threshold-150 copies, standalone startup with an empty release cache, chart review, twelve SQL invariants plus chart/coverage reconciliation, runner failure guards and deterministic asset regeneration. Source checks passed 34 JUnit tests, 60 stdio / 68 HTTP / 60 Unix SQL assertions across four cases each, and executed metadata 100/100 L2 with zero findings. Default execution took 16.9 seconds; the detached threshold-150 run with an empty release cache took 8.1 seconds. Selected chunks change from four to two, represented documents from three to two, and selected PDF pages from two to one. The extraction-outcome chart is byte-identical across settings. Notebook runs use only the public release JAR. All original worker code, build files and committed SQL fixture bytes are preserved.

Official Query.Farm Strata Sun and VGI artwork is copied unchanged from the approved notebook assets and linked at the top and bottom.
