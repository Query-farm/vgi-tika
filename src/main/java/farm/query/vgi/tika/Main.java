package farm.query.vgi.tika;

import farm.query.vgi.Worker;
import farm.query.vgi.protocol.FunctionExample;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VGI worker entry point for Apache Tika document extraction.
 *
 * <p>Attach from DuckDB with:
 * <pre>{@code
 * ATTACH 'tika' (TYPE vgi, LOCATION 'java -jar vgi-tika-all.jar');
 * SELECT content, meta['Content-Type'] AS mime FROM tika.extract_text('/docs/report.pdf');
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
     * An example query defined once and referenced from every list it appears in
     * (the schema-level {@code vgi.example_queries}, and each participating
     * function's {@code withExamples} list and {@code vgi.example_queries} tag).
     */
    record Example(String sql, String description) {
        FunctionExample asFunctionExample() {
            return new FunctionExample(sql, description, null);
        }
    }

    /**
     * Composed: {@code extract_all} over a corpus, then {@code detect_lang} +
     * {@code detect_lang_conf} on each body, aggregated per language. Bodies too
     * short to classify (confidence below 0.5, or none at all) land in 'und'.
     */
    static final Example LANGUAGE_BREAKDOWN = new Example(
            "SELECT CASE WHEN tika.main.detect_lang_conf(content) >= 0.5 "
                    + "THEN tika.main.detect_lang(content) ELSE 'und' END AS language, "
                    + "count(*) AS docs, list(id ORDER BY id) AS ids "
                    + "FROM tika.main.extract_all((SELECT id, body FROM (VALUES "
                    + "(1, 'The quarterly report shows strong growth across every region this year.'::BLOB), "
                    + "(2, 'Le rapport trimestriel montre une forte croissance dans toutes les regions.'::BLOB), "
                    + "(3, 'Der Quartalsbericht zeigt ein starkes Wachstum in allen Regionen.'::BLOB), "
                    + "(4, 'Our customers renewed their contracts early and expanded their usage.'::BLOB), "
                    + "(5, 'OK'::BLOB), (6, 'PO-88213'::BLOB)) AS t(id, body)), "
                    + "id := 'id', doc_column := 'body') "
                    + "GROUP BY language ORDER BY docs DESC, language",
            "Language breakdown of a document corpus: extract every body with extract_all, "
                    + "tag it with detect_lang, and send bodies whose detect_lang_conf is below 0.5 "
                    + "(or undetectable) to 'und'. Returns en [1, 4] and und [5, 6] (2 docs each), then de [3] and fr [2].");

    /**
     * Composed: {@code detect_mime} sniffs each raw blob so only the HTML pages
     * are parsed by {@code extract_all}; {@code detect_lang} tags each page body.
     */
    static final Example HTML_INBOX_TRIAGE = new Example(
            "WITH inbox(id, body) AS (VALUES "
                    + "(1, '<html><head><title>Q3 Update</title></head><body><p>Revenue grew in every region "
                    + "this quarter and margins improved.</p></body></html>'::BLOB), "
                    + "(2, '\\x89PNG\\x0D\\x0A\\x1A\\x0A\\x00\\x00\\x00\\x0DIHDR'::BLOB), "
                    + "(3, '<html><head><title>Point trimestriel</title></head><body><p>Le chiffre d''affaires "
                    + "a progresse dans toutes les regions ce trimestre.</p></body></html>'::BLOB)) "
                    + "SELECT x.id, x.meta['dc:title'] AS title, tika.main.detect_lang(x.content) AS language "
                    + "FROM tika.main.extract_all((SELECT id, body FROM inbox "
                    + "WHERE tika.main.detect_mime(body) = 'text/html'), id := 'id', doc_column := 'body') AS x "
                    + "ORDER BY x.id",
            "Triage a mixed inbox of raw bytes: detect_mime sniffs each blob so only the HTML pages "
                    + "go through extract_all (the PNG, id 2, is skipped), then each page's <title> "
                    + "is read from the metadata map and its body language tagged with detect_lang "
                    + "(1 'Q3 Update' en, 3 'Point trimestriel' fr).");

    /**
     * Fixed agent-suitability suite (VGI152/VGI920). A JSON array of analyst tasks
     * graded by {@code vgi-lint simulate}. Only the public {@code name} +
     * {@code prompt} are published (VGI416); the private grader fields
     * ({@code reference_sql}, {@code ignore_column_names}) live in the
     * {@code vgi-agent-tests.yaml} sidecar at the repo root, keyed by task name.
     */
    static final String AGENT_TEST_TASKS = """
            [
              {"name": "detect_document_mime",
               "prompt": "I have a document whose raw bytes are the text 'Hello, this is a plain-text document body.'. What media (MIME) type does this worker detect for it? Return a single row with one column named mime."},
              {"name": "detect_text_language",
               "prompt": "Detect the natural language of this sentence: 'The quick brown fox jumps over the lazy dog while the sun sets slowly behind the distant hills.'. Return a single row with one column named lang holding the detected language code."},
              {"name": "language_confidence_score",
               "prompt": "For the sentence 'The quick brown fox jumps over the lazy dog while the sun sets slowly behind the distant hills.', how confident is the language detector in its top guess? Return the raw confidence score, unrounded, as a single row with one column named confidence."},
              {"name": "extract_document_media_type",
               "prompt": "Using the single-document extraction, return the detected media type of a document whose raw bytes are 'A short quarterly report body.'. Return a single row with one column named mime."},
              {"name": "metadata_media_type",
               "prompt": "Using the metadata-only function (no body text), return the detected media type of a document whose raw bytes are 'A brief internal memo.'. Return a single row with one column named mime."},
              {"name": "batch_extract_media_types",
               "prompt": "I have a small table of two documents: id 1 with raw bytes 'alpha document body' and id 2 with raw bytes 'beta document body'. Process them all at once and return each document's id together with its detected media type, ordered by id ascending. Return two columns named id and mime."},
              {"name": "ocr_when_no_text_layer",
               "prompt": "Run optical character recognition over a tiny input whose raw bytes are 'x' (there is no real image, so OCR yields no recognizable characters). Return a single row with one column named text holding whatever OCR produces."},
              {"name": "least_confident_language_document",
               "prompt": "I have three documents as raw bytes: id 1 is 'The quarterly report shows strong growth across every region this year.', id 2 is 'Invoice 4471-B', and id 3 is 'Le rapport trimestriel montre une forte croissance dans toutes les regions.'. Extract the body text of each document and find the one whose language detection is least confident. Return a single row with one INTEGER column named id."},
              {"name": "english_html_page_titles",
               "prompt": "I have three HTML pages as raw bytes: id 1 is '<html><head><title>Pricing</title></head><body><p>Our plans are billed monthly and you can cancel at any time.</p></body></html>', id 2 is '<html><head><title>Preise</title></head><body><p>Unsere Tarife werden monatlich abgerechnet und sind jederzeit kuendbar.</p></body></html>', and id 3 is '<html><head><title>About us</title></head><body><p>We are a small team building tools for analysts around the world.</p></body></html>'. Return the HTML title, as reported in the extracted document metadata, of every page whose extracted body text is in English. Return one VARCHAR column named title, ordered by title ascending."}
            ]""";

    /** Catalog-level tags surfaced to DuckDB and the vgi-lint metadata linter. */
    static Map<String, String> catalogTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("vgi.title", "Apache Tika Document Extraction");
        tags.put(
                "vgi.subtitle",
                "Pull text and metadata out of PDFs, Office files and other documents, "
                        + "detect their type and language, and OCR scanned images.");
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
        // prompt is solvable with only the exposed functions; the hidden graders
        // live in vgi-agent-tests.yaml (VGI416).
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
        tags.put("vgi.title", "Document extraction");
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
                        "SELECT content, mime FROM tika.main.extract_text('A plain-text body.'::BLOB)",
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
                        "Batch-extract a whole column of documents, passing an id column through onto every output row.",
                        LANGUAGE_BREAKDOWN.sql(), LANGUAGE_BREAKDOWN.description(),
                        HTML_INBOX_TRIAGE.sql(), HTML_INBOX_TRIAGE.description()));
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
