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
                "Extract plain text, document metadata, language, page counts, and OCR from "
                        + "binary documents (PDF, DOCX, PPTX, XLSX, HTML, EML/MSG, RTF, ODF, and "
                        + "images) directly in SQL via Apache Tika. Pass a file path (the worker "
                        + "opens it) or a BLOB of the document bytes. Use the `extract` / "
                        + "`metadata` table functions for a single document, `extract_all` to "
                        + "process a whole column of documents with an id passthrough, and the "
                        + "`detect_mime`, `detect_lang`, `detect_lang_conf`, and `ocr` scalars "
                        + "for media-type sniffing, language detection, and Tesseract OCR.");
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

                        + "## SQL use cases & functions\n\n"
                        + "Inputs are always either a `VARCHAR` file path (the worker opens it) or a "
                        + "`BLOB` of the raw document bytes. The function surface is:\n\n"
                        + "- **`extract`** — table function returning the full text `content`, a "
                        + "metadata map, and page counts for one document; supports per-page "
                        + "splitting via `by_page`.\n"
                        + "- **`metadata`** — table function returning the metadata map without the "
                        + "body text, for fast media-type and property inspection.\n"
                        + "- **`extract_all`** — table-in/table-out function that extracts an entire "
                        + "column of documents at once with an id passthrough, ideal for batch "
                        + "processing a table of paths or blobs.\n"
                        + "- **`detect_mime`** — scalar returning a document's MIME / media type.\n"
                        + "- **`detect_lang`** / **`detect_lang_conf`** — scalars returning the "
                        + "detected language of a text and the detection confidence.\n"
                        + "- **`ocr`** — scalar running Tesseract OCR over an image or scanned PDF.\n\n"
                        + "Typical queries include `SELECT content FROM tika.extract('/docs/report.pdf')`, "
                        + "joining `extract_all` output against a `documents` table for full-text "
                        + "indexing, or filtering on `detect_mime` and `detect_lang` to route "
                        + "documents through a downstream pipeline.");
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
                "Apache Tika document-extraction functions: extract text/metadata/language/page "
                        + "counts from a document (`extract`, `metadata`), process a column of "
                        + "documents (`extract_all`), and detect a document's MIME type, a text's "
                        + "language and confidence, or OCR images and scanned PDFs (`detect_mime`, "
                        + "`detect_lang`, `detect_lang_conf`, `ocr`).");
        tags.put(
                "vgi.doc_md",
                "# tika.main\n\n"
                        + "Apache Tika document text, metadata, language, and OCR extraction "
                        + "functions over Apache Arrow. Table functions: `extract`, `metadata`, "
                        + "`extract_all`; scalars: `detect_mime`, `detect_lang`, "
                        + "`detect_lang_conf`, `ocr`.");
        // VGI506 representative example queries for the schema (self-contained).
        tags.put(
                "vgi.example_queries",
                "SELECT content, mime FROM tika.main.extract('A plain-text body.'::BLOB);\n"
                        + "SELECT mime, n_pages FROM tika.main.metadata('Some document bytes.'::BLOB);\n"
                        + "SELECT tika.main.detect_mime('Plain text.'::BLOB);\n"
                        + "SELECT tika.main.detect_lang('The quick brown fox jumps over the lazy dog.');\n"
                        + "SELECT id, content FROM tika.main.extract_all("
                        + "(SELECT * FROM (VALUES (1, 'Body bytes.'::BLOB)) AS t(id, body)), id := 'id', doc_column := 'body');");
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
