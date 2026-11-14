# Apache Tika Document Extraction examples

- [01 · Preserve document coverage before selecting text](01_preserve_document_coverage.ipynb) keeps the original manifest/failed/blank extraction story, checks MIME hints against actual outcomes and reconciles metadata page counts. Its minimum-character rule keeps physical PDF pages distinct from whole-document fallbacks.
- [02 · Recover text before trusting a language label](02_recover_text_before_trusting_language.ipynb) OCRs an authored raster card, compares its tokens with the reference and routes a synthetic language queue. A lower confidence gate admits an ambiguous two-letter label without improving the evidence.

Read the saved notebooks on GitHub, or use **Open in Colab** and Run All. A single downloaded
notebook installs the base shared package and retrieves SHA-256-verified companions. Actual hosted
Colab is untested; validation includes a fresh local Python 3.12 notebook-only environment with
compatible data/plotting/kernel libraries already loaded.

## Run from this directory

Requires Python 3.11+, [uv](https://docs.astral.sh/uv/), macOS/Linux, and network access for initial
Python/shared-package, VGI extension, Java runtime, release JAR and companion downloads.

```sh
uv sync --locked --extra runner
uv run --locked --extra runner vgi-notebooks
```

The [shared package v0.3.23](https://github.com/Query-farm/vgi-example-notebooks/tree/v0.3.23) supplies canonical branding, SVG charts, isolated SQL setup and atomic execution.
Colab installs only its base package; local publication uses the optional `[runner]` extra and
`uv.lock`. No worker checkout, Gradle or system Java is required. The complete notebooks directory
runs independently of the parent repository. Optional local kernel registration:

```sh
uv run --locked --extra runner python -m ipykernel install --user \
  --name vgi-tika-notebooks --display-name 'Python (vgi-tika notebooks)'
```

Visible SQL uses the published v0.4.0 release:

```sql
INSTALL vgi FROM community;
LOAD vgi;
ATTACH 'tika' (TYPE vgi, LOCATION 'uv run --frozen --script tika_release.py v0.4.0');
```

`tika_release.py` verifies the 78 MB public JAR's SHA-256 on download and every cached launch,
uses a bounded POSIX lock and atomic download, and forwards stdio/signals to Java. Its independent
PEP 723 lock pins `jdk4py==21.0.8.2`; both package and actual Java binary version 21.0.8 are checked.
The host's Java is not used. JAR SHA-256 is
`4f65055a0c97e7e94a90377bc6b469ee2d955440135e23c049112e12d3affa61`, and the worker tag resolves to
`c052ab54a8248e4f2191103f032e26f0180422a4`. Upgrade the reviewed release mapping and digest together,
then rerun validation. No transitive dependency overrides or engine rebuild are involved.

Notebook 01 explicitly disables OCR and needs no Tesseract. Notebook 02 requires **Tesseract and
its English model** on PATH. On macOS use `brew install tesseract`; on Debian/Ubuntu use
`sudo apt-get install tesseract-ocr tesseract-ocr-eng`. Its visible setup installs these packages
only when Tesseract is absent in a root Linux environment, then verifies English model availability.
That Linux installation branch is not exercised on macOS. Tesseract/model versions are external
native prerequisites, not pinned by uv; saved outputs report the tested native version.

`connect(globals())` creates empty temporary SQL-secret storage, disables persistence and prevents
JupySQL's saved default-connection autoload. These local workers require no credentials. Cleanup
closes the database and removes temporary secret storage; no existing secrets are read or modified.

## Focused experiments

```sh
uv run --locked --extra runner vgi-notebooks 01_preserve_document_coverage.ipynb \
  --parameters '{"MIN_CHARS":150}' --output-dir validation/min-150
uv run --locked --extra runner vgi-notebooks 02_recover_text_before_trusting_language.ipynb \
  --parameters '{"MIN_LANGUAGE_CONFIDENCE":0.05}' --output-dir validation/confidence-0.05
```

The first experiment changes selection only: extraction results and the first inventory chart stay
fixed. The second lowers the policy from 0.8 to 0.05: recovered text, returned language codes and
confidence scores stay fixed while the weak `OK` label can enter the retained population. A lower
gate is not stronger evidence. Restore defaults before publication.

Parameter overrides require a separate destination. Unknown names, source-overwriting destinations
and invalid values fail without replacing the last successful default or experiment. Companion
files travel with experiment copies. Logs, environments, review exports and experiments are ignored;
saved defaults retain native SVG plus committed image links for GitHub. Experiments use native SVG
without unpublished links. Restart and Run All after editing.

## Synthetic inputs and scope

The five original files and [build_fixture.py](build_fixture.py) are preserved unchanged: a
four-page PDF (long text, `OK`, blank page, checklist), a DOCX note, HTML brief, whitespace-only
text and intentionally corrupt PDF. The first manifest also includes missing-path and SQL NULL
controls. No real account, private document or external document link is used.

[build_scan.py](build_scan.py) creates a clean 1800×500 white raster with four authored lines;
[scan-manifest.json](assets/scan-manifest.json) records exact reference tokens and image hash.
The generator uses Pillow with Matplotlib's DejaVu Sans; the committed image is hash-pinned, so
readers need not regenerate it or rely on identical font/rendering versions. It is not a captured
scan, licensed stock image, degraded page, or worker-output fixture. All OCR and language outputs
are generated by the real released worker.

The first story keeps per-row parse failures, missing input and known zero-length text separate.
`detect_mime` can identify corrupt PDF bytes without proving they parse. `metadata` still parses
the file; its MIME/page fields are not recovered body text. PDF page rows repeat document totals:
use one total plus verified row ordinals, not a sum. DOCX/HTML fall back to whole-document rows;
those row labels do not establish physical page counts. The unchanged minimum-character rule is
not a relevance, quality, token-budget or completeness metric.

The second story checks exact OCR token order after whitespace/case normalization on one clean
card. It does not estimate OCR accuracy on handwriting, rotations, degraded images, complex layout
or other scripts. Tika returns NULL if Tesseract is unavailable, so the notebook checks that
prerequisite explicitly and requires nonempty matching recognition rather than counting a NULL
probe as an OCR demonstration. This release's SQL catalog exposes `ocr(doc)` without a named
`lang` setting; it uses default English. Current documentation mentioning other settings does not
make those SQL arguments available in the released signature.

Language confidence is the detector's raw top-result score. It is not calibrated routing accuracy,
OCR fidelity or a percentage of text recovered. The ambiguous `OK` has no asserted correct language;
identifier/blank/NULL rows retain unknown scores. No document is actually routed or sent elsewhere.

## Coverage and evidence

[coverage.json](coverage.json) maps all **seven public SQL surfaces** to successfully executed
visible SQL, results and reviewed interpretation across the two stories. Complete released
functions/tables/views exports include zero tables and zero views. Current public source keeps the
same seven signatures; its changes add composed examples, not runtime semantics. Exact released
runtime coverage and reviewed current-interface compatibility are reported separately; no current
source build is claimed.

Validation includes both defaults, separate parameter experiments, one fresh preloaded bootstrap
covering the shared launcher/companion path, SQL/reference checks, runner preservation guards,
figure review and actual GitHub SVG rendering. Existing green [source CI 43574719304](https://github.com/Query-farm/vgi-tika/actions/runs/43574719304)
is reused for unchanged worker code. No source build, repeated unit/transport suite or source/container
CI dispatch is performed for this notebook-only migration. Native Linux installation and hosted
Colab remain untested. Source, build files and original fixture bytes are preserved.
