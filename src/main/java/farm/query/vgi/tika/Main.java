package farm.query.vgi.tika;

import farm.query.vgi.Worker;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VGI worker entry point for Apache Tika document extraction.
 *
 * <p>Attach from DuckDB with:
 * <pre>{@code
 * ATTACH 'tika' (TYPE vgi, LOCATION 'java -jar vgi-tika-all.jar');
 * SELECT content, meta['Content-Type'] AS mime FROM tika.extract('/docs/report.pdf');
 * }</pre>
 */
public final class Main {

    private Main() {}

    public static final String GIT_COMMIT =
            System.getenv("VGI_TIKA_GIT_COMMIT") != null
                    ? System.getenv("VGI_TIKA_GIT_COMMIT") : "unknown";

    /**
     * Encode example queries as the reserved {@code vgi.example_queries} tag — a
     * JSON array of {@code {"sql", "description"}} objects. This is the carrier
     * the vgi-lint metadata linter reads for every function type (table
     * functions in particular only surface examples via this tag). Pass
     * sql/description pairs as alternating arguments.
     */
    static String exampleQueriesTag(String... sqlThenDescription) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 1 < sqlThenDescription.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append("{\"sql\":").append(jsonString(sqlThenDescription[i]))
                    .append(",\"description\":").append(jsonString(sqlThenDescription[i + 1]))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /** Minimal JSON string escaper for the example-query tag. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Fixed agent-suitability suite (VGI152/VGI920). A JSON array of analyst tasks
     * graded by {@code vgi-lint simulate}: only {@code prompt} is shown to the
     * simulated analyst; {@code reference_sql} is the hidden canonical solution.
     * Every reference is self-contained (inline {@code BLOB} literals) and
     * deterministic; {@code ignore_column_names} makes grading value-based.
     */
    static final String AGENT_TEST_TASKS = """
            [
              {"name": "detect_document_mime",
               "prompt": "I have a document whose raw bytes are the text 'Hello, this is a plain-text document body.'. What media (MIME) type does this worker detect for it? Return a single row with one column named mime.",
               "reference_sql": "SELECT tika.main.detect_mime('Hello, this is a plain-text document body.'::BLOB) AS mime",
               "ignore_column_names": true},
              {"name": "detect_text_language",
               "prompt": "Detect the natural language of this sentence: 'The quick brown fox jumps over the lazy dog while the sun sets slowly behind the distant hills.'. Return a single row with one column named lang holding the detected language code.",
               "reference_sql": "SELECT tika.main.detect_lang('The quick brown fox jumps over the lazy dog while the sun sets slowly behind the distant hills.') AS lang",
               "ignore_column_names": true},
              {"name": "language_confidence_score",
               "prompt": "For the sentence 'The quick brown fox jumps over the lazy dog while the sun sets slowly behind the distant hills.', how confident is the language detector in its top guess? Return the raw confidence score, unrounded, as a single row with one column named confidence.",
               "reference_sql": "SELECT tika.main.detect_lang_conf('The quick brown fox jumps over the lazy dog while the sun sets slowly behind the distant hills.') AS confidence",
               "ignore_column_names": true},
              {"name": "extract_document_media_type",
               "prompt": "Using the single-document extraction, return the detected media type of a document whose raw bytes are 'A short quarterly report body.'. Return a single row with one column named mime.",
               "reference_sql": "SELECT mime FROM tika.main.extract('A short quarterly report body.'::BLOB)",
               "ignore_column_names": true},
              {"name": "metadata_media_type",
               "prompt": "Using the metadata-only function (no body text), return the detected media type of a document whose raw bytes are 'A brief internal memo.'. Return a single row with one column named mime.",
               "reference_sql": "SELECT mime FROM tika.main.metadata('A brief internal memo.'::BLOB)",
               "ignore_column_names": true},
              {"name": "batch_extract_media_types",
               "prompt": "I have a small table of two documents: id 1 with raw bytes 'alpha document body' and id 2 with raw bytes 'beta document body'. Process them all at once and return each document's id together with its detected media type, ordered by id ascending. Return two columns named id and mime.",
               "reference_sql": "SELECT id, mime FROM tika.main.extract_all((SELECT * FROM (VALUES (1, 'alpha document body'::BLOB), (2, 'beta document body'::BLOB)) AS t(id, body)), id := 'id', doc_column := 'body') ORDER BY id",
               "ignore_column_names": true},
              {"name": "ocr_when_no_text_layer",
               "prompt": "Run optical character recognition over a tiny input whose raw bytes are 'x' (there is no real image, so OCR yields no recognizable characters). Return a single row with one column named text holding whatever OCR produces.",
               "reference_sql": "SELECT tika.main.ocr('x'::BLOB) AS text",
               "ignore_column_names": true}
            ]""";

    /** Catalog-level tags surfaced to DuckDB and the vgi-lint metadata linter. */
    static Map<String, String> catalogTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("vgi.title", "Apache Tika Document Extraction");
        tags.put(
                "vgi.keywords",
                Meta.keywordsJson(
                        "tika", "document extraction", "text extraction", "metadata", "ocr",
                        "mime", "language detection", "pdf", "docx", "pptx", "xlsx", "html",
                        "email", "rtf", "odf", "tesseract"));
        tags.put(
                "vgi.doc_llm",
                "Extract searchable plain text, rich document metadata, detected language, page "
                        + "counts, and OCR output from binary documents — PDF, Word, PowerPoint, "
                        + "Excel, HTML, email, RTF, OpenDocument, and image formats — directly "
                        + "inside DuckDB SQL, powered by Apache Tika. Each call takes either a "
                        + "filesystem path (the worker opens and reads the file) or a `BLOB` of the "
                        + "raw document bytes; the media type is auto-detected, so one call handles "
                        + "dozens of formats. Reach for this worker when you need to mine "
                        + "unstructured documents from SQL — full-text and vector indexing for "
                        + "search or RAG, media-type routing, language filtering, or joining "
                        + "extracted content and metadata against the rest of your warehouse — "
                        + "without standing up an external extraction service. Scanned pages and "
                        + "images are run through Tesseract OCR when the binary is available, and "
                        + "parse failures surface per row rather than aborting the query. The "
                        + "worker's functions are grouped into navigable categories for text and "
                        + "metadata extraction, content detection, and optical character "
                        + "recognition.");
        tags.put(
                "vgi.doc_md",
                "# Apache Tika Document & OCR Text Extraction in SQL\n\n"
                        + "![Apache Tika logo](https://upload.wikimedia.org/wikipedia/commons/7/74/Apache_Tika_Logo.svg)\n\n"
                        + "Extract plain text, document metadata, language, page counts, and OCR "
                        + "directly in DuckDB SQL from PDF, Word, Excel, PowerPoint, HTML, email, "
                        + "and image files — powered by [Apache Tika](https://tika.apache.org/) "
                        + "([source](https://github.com/apache/tika)).\n\n"

                        + "## What it does\n\n"
                        + "The **tika** extension turns DuckDB into a document-parsing engine: point "
                        + "it at a file path or a `BLOB` of document bytes and get back searchable "
                        + "plain text plus a rich metadata map, all without leaving SQL. It is built "
                        + "for data engineers, analysts, and AI/RAG pipelines that need to mine "
                        + "unstructured documents — contracts, invoices, reports, slide decks, "
                        + "spreadsheets, web pages, and scanned images — and join the extracted "
                        + "content against the rest of their warehouse. Because it runs as a VGI "
                        + "worker over Apache Arrow, extraction happens close to your data with no "
                        + "external service calls and no manual file shuffling.\n\n"

                        + "## How it works\n\n"
                        + "Every function is backed by [Apache Tika](https://tika.apache.org/), the "
                        + "battle-tested Apache content-detection and analysis toolkit that ships "
                        + "more than a thousand parsers (PDFBox for PDF, Apache POI for Microsoft "
                        + "Office, and many more). Tika auto-detects each document's media type and "
                        + "dispatches it to the right parser, so a single call handles dozens of "
                        + "formats. Scanned PDFs and images are run through "
                        + "[Tesseract OCR](https://github.com/tesseract-ocr/tesseract) when the "
                        + "Tesseract binary is available; when it is not, OCR results return `NULL` "
                        + "rather than failing the query. See the official "
                        + "[Apache Tika documentation](https://cwiki.apache.org/confluence/display/TIKA) "
                        + "for the full parser and metadata catalog.\n\n"

                        + "## When to use it\n\n"
                        + "Reach for this worker whenever unstructured documents need to become "
                        + "queryable rows: building a full-text or vector index over a document "
                        + "store, routing or filtering files by their true media type, detecting "
                        + "the language of a body of text, or pulling structured metadata (author, "
                        + "title, creation date, page count) out of office and PDF files. Inputs "
                        + "are always either a `VARCHAR` filesystem path the worker opens or a "
                        + "`BLOB` of the raw document bytes, so documents already living in a table "
                        + "column can be processed in place without shuffling files around.\n\n"
                        + "## Navigating the functions\n\n"
                        + "The worker's functions are organized into navigable categories — text "
                        + "and metadata extraction (single-document and whole-column batch), "
                        + "content detection (media type and language), and optical character "
                        + "recognition — each carrying its own runnable examples and documented "
                        + "return columns.");
        // VGI152/VGI920 agent-suitability suite for `vgi-lint simulate`. Every
        // prompt is solvable with only the exposed functions and is graded against
        // the hidden reference_sql; each reference is self-contained (inline BLOB
        // literals, no external files) and deterministic. `ignore_column_names`
        // compares by values so an equivalent solution grades as correct.
        tags.put("vgi.agent_test_tasks", AGENT_TEST_TASKS);
        tags.put("vgi.author", "Query.Farm");
        tags.put("vgi.copyright", "Copyright 2026 Query Farm LLC - https://query.farm");
        tags.put("vgi.license", "MIT");
        tags.put("vgi.support_contact", "https://github.com/Query-farm/vgi-tika/issues");
        tags.put(
                "vgi.support_policy_url",
                "https://github.com/Query-farm/vgi-tika/blob/main/README.md");
        return tags;
    }

    /** Tags for the single `main` schema. */
    static Map<String, String> schemaTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("vgi.title", "Tika — main schema");
        tags.put(
                "vgi.keywords",
                Meta.keywordsJson(
                        "tika", "extract", "metadata", "extract_all", "detect_mime",
                        "detect_lang", "detect_lang_conf", "ocr", "document", "text",
                        "language", "mime"));
        // VGI123 classifying tags — BARE keys (not vgi.-namespaced) for faceting.
        tags.put("domain", "documents");
        tags.put("category", "extraction");
        tags.put("topic", "text-metadata-ocr");
        // Per-object vgi.source_url is omitted here (VGI139): source_url lives
        // only on the catalog object (set via Worker.builder().sourceUrl(...)).
        tags.put(
                "vgi.doc_llm",
                "Apache Tika document-analysis functions exposed as DuckDB SQL over Apache Arrow. "
                        + "This schema covers the worker's full surface: turning a document (a "
                        + "filesystem path or a `BLOB` of bytes) into plain text with a metadata map "
                        + "and page counts, batch-processing a whole column of documents with an id "
                        + "passthrough, sniffing a document's media type, identifying the natural "
                        + "language and confidence of a body of text, and running Tesseract OCR "
                        + "over images and scanned PDFs. The functions are grouped into navigable "
                        + "categories for text and metadata extraction, content detection, and "
                        + "optical character recognition.");
        tags.put(
                "vgi.doc_md",
                "# tika.main\n\n"
                        + "Apache Tika document text, metadata, language, and OCR extraction, "
                        + "exposed as DuckDB SQL functions over Apache Arrow.\n\n"
                        + "## Concepts\n\n"
                        + "Each function takes a document as either a `VARCHAR` filesystem path "
                        + "(the worker opens it) or a `BLOB` of the raw bytes, and Tika "
                        + "auto-detects the media type before dispatching to the right parser. Text "
                        + "extraction returns the plain-text body alongside a `MAP(VARCHAR, "
                        + "VARCHAR)` of Tika's full metadata bag; per-row error capture keeps a "
                        + "single corrupt document from failing an entire query.\n\n"
                        + "## Categories\n\n"
                        + "The functions are organized into navigable categories — text and "
                        + "metadata extraction (single-document and batch), content detection "
                        + "(media type and language), and optical character recognition — so agents "
                        + "and users can discover the right tool for a task. See each function's "
                        + "own documentation and examples for exact inputs, options, and returned "
                        + "columns.");
        // VGI413 navigation/SEO registry: the ordered category sections this
        // schema's functions are grouped into. Each object carries a matching
        // vgi.category naming one of these names.
        tags.put(
                "vgi.categories",
                "[\n"
                        + "  {\"name\": \"Text & Metadata Extraction\", \"description\": \"Turn a "
                        + "single document, or a whole column of documents, into plain text plus a "
                        + "structured metadata map, language, and page counts.\"},\n"
                        + "  {\"name\": \"Content Detection\", \"description\": \"Identify a "
                        + "document's media (MIME) type from its bytes and detect the natural "
                        + "language, with confidence, of a body of text.\"},\n"
                        + "  {\"name\": \"Optical Character Recognition\", \"description\": \"Recover "
                        + "text from images and scanned PDFs by running them through Tesseract "
                        + "OCR.\"}\n"
                        + "]");
        // VGI506/VGI515 representative example queries for the schema. Each carries
        // a non-empty description (JSON {"sql","description"} objects) and is
        // self-contained (inline BLOB literals, no external files).
        tags.put(
                "vgi.example_queries",
                exampleQueriesTag(
                        "SELECT content, mime FROM tika.main.extract('A plain-text body.'::BLOB)",
                        "Extract the plain-text body and detected media type from a single document.",
                        "SELECT mime, n_pages FROM tika.main.metadata('Some document bytes.'::BLOB)",
                        "Read a document's media type and page count without extracting its body text.",
                        "SELECT tika.main.detect_mime('Plain text.'::BLOB)",
                        "Detect the media (MIME) type of a document from its raw bytes.",
                        "SELECT tika.main.detect_lang('The quick brown fox jumps over the lazy dog.')",
                        "Detect the natural-language code of a body of text.",
                        "SELECT id, content FROM tika.main.extract_all("
                                + "(SELECT * FROM (VALUES (1, 'Body bytes.'::BLOB)) AS t(id, body)), "
                                + "id := 'id', doc_column := 'body')",
                        "Batch-extract a whole column of documents, passing an id column through onto every output row."));
        return tags;
    }

    public static Worker buildWorker() {
        return Worker.builder()
                .catalogName("tika")
                .implementationVersion(GIT_COMMIT)
                .catalogComment("Document text, metadata, and OCR extraction (Apache Tika)")
                .catalogTags(catalogTags())
                .sourceUrl("https://github.com/Query-farm/vgi-tika")
                .defaultSchema("main")
                .schemaComment("main", "Apache Tika document text, metadata, and OCR extraction functions.")
                .schemaTags("main", schemaTags())
                .registerTable(new ExtractFunction())
                .registerTable(new MetadataFunction())
                .registerTableInOut(new ExtractAllFunction())
                .registerScalar(new DetectMimeFunction())
                .registerScalar(new DetectLangFunction())
                .registerScalar(new DetectLangConfFunction())
                .registerScalar(new OcrFunction());
    }

    public static void main(String[] args) {
        String stderrPath = System.getenv("VGI_WORKER_STDERR");
        if (stderrPath != null && !stderrPath.isEmpty()) {
            try {
                java.io.PrintStream ps = new java.io.PrintStream(
                        new java.io.FileOutputStream(stderrPath, true), true);
                System.setErr(ps);
            } catch (Exception ignore) {
                // best-effort stderr redirect
            }
        }
        buildWorker().runFromArgs(args);
    }
}
