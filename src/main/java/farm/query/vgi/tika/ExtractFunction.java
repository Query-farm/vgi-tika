package farm.query.vgi.tika;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.table.TableBindParams;
import farm.query.vgi.table.TableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.nio.file.Path;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;

/**
 * {@code tika.extract(path | bytes, by_page := false, lang := 'eng', ocr := false,
 * strict := false)} — extract text + metadata from a single document.
 *
 * <p>Emits exactly one row (the whole document) unless {@code by_page := true},
 * in which case it emits one row per page with a leading {@code page} column.
 * Real per-page text splitting is performed for PDFs (via PDFBox); other formats
 * fall back to a single page row carrying the whole body. Parse failures are
 * captured per-row (NULL content + {@code error}) unless {@code strict := true},
 * which re-raises and fails the query.
 */
public final class ExtractFunction implements TableFunction {

    private final TikaEngine engine;

    public ExtractFunction() { this(TikaEngine.shared()); }
    public ExtractFunction(TikaEngine engine) { this.engine = engine; }

    @Override public String name() { return "extract"; }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(
                        "Extract text, metadata, language, and page count from a document (PDF, DOCX, "
                                + "PPTX, XLSX, HTML, EML, RTF, ODF, images) via Apache Tika.")
                .withCategories("document", "extraction", "tika")
                .withTag("vgi.category", "Text & Metadata Extraction")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT content, mime, n_pages FROM tika.main.extract('A short plain-text body.'::BLOB);",
                                "Extract the body text, MIME type, and page count of a single document.",
                                null),
                        new FunctionExample(
                                "SELECT page, content FROM tika.main.extract('Single page body.'::BLOB, by_page := true);",
                                "Split a document into one row per page (leading `page` column).",
                                null),
                        new FunctionExample(
                                "SELECT meta['Content-Type'] AS ct FROM tika.main.extract('Some bytes.'::BLOB);",
                                "Extract from document bytes (a BLOB) and read a metadata key from the `meta` MAP.",
                                null)))
                .withTags(Meta.objectTags(
                        "Extract Document Text and Metadata",
                        "## extract\n\n"
                                + "Parse a single document with Apache Tika and return its plain-text body "
                                + "together with detected metadata. Handles PDF, DOCX, PPTX, XLSX, HTML, "
                                + "EML/MSG, RTF, ODF, and image formats.\n\n"
                                + "**Input** — an `any`-typed `doc` argument: a `VARCHAR` filesystem path "
                                + "the worker opens, or a `BLOB` of the document bytes. Named options: "
                                + "`by_page := true` emits one row per page (real per-page splitting for "
                                + "PDFs, a single fallback row for other formats); `lang` sets the OCR "
                                + "language; `ocr := true` forces OCR; `strict := true` re-raises parse "
                                + "errors instead of capturing them.\n\n"
                                + "**Output** — one row (or one per page) with `content`, `mime`, "
                                + "`n_pages`, `lang`, a `meta` `MAP(VARCHAR, VARCHAR)`, and an `error` "
                                + "column.\n\n"
                                + "**Error handling** — a corrupt or unsupported document yields a row "
                                + "with `NULL` content and a populated `error` message rather than failing "
                                + "the whole query, unless `strict := true`.",
                        "# extract\n\n"
                                + "Extracts text, metadata, language, and page count from a single "
                                + "document.\n\n"
                                + "## Usage\n\n"
                                + "Pass a file path or document bytes. Use `by_page := true` to fan a PDF "
                                + "out into per-page rows, `ocr := true` for image-only content, and "
                                + "`strict := true` to fail loudly on parse errors.\n\n"
                                + "## Notes\n\n"
                                + "- Per-row error capture: failures surface in the `error` column "
                                + "(NULL `content`) unless `strict`.\n"
                                + "- The `meta` column is a `MAP(VARCHAR, VARCHAR)` of Tika's full "
                                + "metadata bag.\n"
                                + "- See the returned-columns table below for the exact output shape.",
                        "extract", "text extraction", "document text", "pdf", "docx", "pptx",
                        "xlsx", "html", "tika", "parse document", "content", "metadata",
                        "by_page"))
                .withTag("vgi.result_dynamic_columns_md", COLUMNS_MD)
                .withTag("vgi.executable_examples", EXECUTABLE_EXAMPLES)
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT content, mime, n_pages FROM tika.main.extract('A short plain-text body.'::BLOB);",
                        "Extract the body text, MIME type, and page count of a single document.",
                        "SELECT page, content FROM tika.main.extract('Single page body.'::BLOB, by_page := true);",
                        "Split a document into one row per page (leading `page` column).",
                        "SELECT meta['Content-Type'] AS ct FROM tika.main.extract('Some bytes.'::BLOB);",
                        "Extract from document bytes (a BLOB) and read a metadata key from the `meta` MAP."));
    }

    /**
     * Guaranteed-runnable, catalog-qualified examples (VGI509). Each {@code sql}
     * is self-contained: it synthesizes a plain-text document via a {@code BLOB}
     * literal so no external file is needed, and runs as written against an
     * attached {@code tika} worker. {@code expected_result} is omitted on purpose.
     */
    static final String EXECUTABLE_EXAMPLES =
            "[\n"
                    + "  {\n"
                    + "    \"description\": \"Extract the body text and MIME type of inline document bytes.\",\n"
                    + "    \"sql\": \"SELECT content, mime FROM tika.main.extract('Hello from a plain-text document body.'::BLOB)\"\n"
                    + "  },\n"
                    + "  {\n"
                    + "    \"description\": \"Read a single key from the Tika metadata MAP of a document.\",\n"
                    + "    \"sql\": \"SELECT meta['Content-Type'] AS content_type FROM tika.main.extract('Some document bytes.'::BLOB)\"\n"
                    + "  }\n"
                    + "]";

    /**
     * Result columns of {@code extract} as {@code vgi.result_dynamic_columns_md}
     * (VGI307): the shape varies by argument — {@code by_page := true} prepends a
     * leading {@code page} column — so each variant is a separate
     * {@code Name | Type | Description} table under its own heading.
     */
    static final String COLUMNS_MD =
            "### Default (`by_page := false`)\n\n"
                    + "| name | type | description |\n"
                    + "|---|---|---|\n"
                    + "| `content` | VARCHAR | Extracted plain-text body of the document, or NULL on error. |\n"
                    + "| `mime` | VARCHAR | Detected media type, e.g. `application/pdf`. |\n"
                    + "| `n_pages` | INTEGER | Page/slide/sheet count when the format reports it, else NULL. |\n"
                    + "| `lang` | VARCHAR | Document language code if Tika reported one in the metadata. |\n"
                    + "| `meta` | MAP(VARCHAR, VARCHAR) | Full Tika metadata bag. |\n"
                    + "| `error` | VARCHAR | Per-row parse error message, or NULL on success. |\n\n"
                    + "### Per page (`by_page := true`)\n\n"
                    + "| name | type | description |\n"
                    + "|---|---|---|\n"
                    + "| `page` | INTEGER | 1-based page/slide/sheet ordinal. |\n"
                    + "| `content` | VARCHAR | Extracted plain-text body of this page, or NULL on error. |\n"
                    + "| `mime` | VARCHAR | Detected media type, e.g. `application/pdf`. |\n"
                    + "| `n_pages` | INTEGER | Total page count of the source document. |\n"
                    + "| `lang` | VARCHAR | Document language code if Tika reported one in the metadata. |\n"
                    + "| `meta` | MAP(VARCHAR, VARCHAR) | Full Tika metadata bag. |\n"
                    + "| `error` | VARCHAR | Per-row parse error message, or NULL on success. |";

    @Override public List<ArgSpec> argumentSpecs() {
        // Polymorphic doc arg: an any-typed positional so DuckDB binds both a
        // VARCHAR path (the worker opens the file) and a BLOB/BINARY of bytes.
        // The worker disambiguates on the runtime value/type via DocInput.
        return List.of(
                Meta.anyArg("doc", 0,
                        "The document to extract. Pass either a filesystem path (the worker "
                                + "opens and reads the file) or the raw document bytes inline; "
                                + "the worker dispatches on the runtime value."),
                Meta.namedArg("by_page", Schemas.BOOL, "false",
                        "When true, emit one row per page with a leading 1-based `page` column "
                                + "(real per-page splitting for PDFs; other formats fall back to a "
                                + "single page row). When false (default), emit one row for the "
                                + "whole document."),
                Meta.namedArg("lang", Schemas.UTF8, "eng",
                        "Tesseract OCR language(s) to use when OCR runs, as a `+`-joined "
                                + "trained-data list (e.g. `eng` or `eng+deu`). Only consulted when "
                                + "OCR is triggered; defaults to English."),
                Meta.namedArg("ocr", Schemas.BOOL, "false",
                        "When true, force OCR of the document (images/scanned PDFs) instead of "
                                + "relying on born-digital text extraction. Defaults to false."),
                Meta.namedArg("strict", Schemas.BOOL, "false",
                        "When true, re-raise any parse error and fail the query instead of "
                                + "capturing it per-row (NULL content + populated `error` column). "
                                + "Defaults to false (errors are captured)."));
    }

    private static boolean byPage(Arguments a) { return a.namedBool("by_page", false); }

    @Override public BindResponse onBind(TableBindParams p) {
        Schema schema = byPage(p.arguments())
                ? TikaSchemas.EXTRACT_BY_PAGE_SCHEMA
                : TikaSchemas.EXTRACT_SCHEMA;
        return BindResponse.forSchema(SchemaUtil.serializeSchema(schema));
    }

    @Override public long cardinality(TableBindParams p) {
        return byPage(p.arguments()) ? 16L : 1L;
    }

    @Override public TableProducerState createProducer(TableInitParams params) {
        Arguments a = params.arguments();
        Object docValue = a.positionalAt(0);
        ArrowType docType = a.positionalTypeAt(0);
        DocInput input = DocInput.fromArgument(docValue, docType);
        return new State(input, byPage(a), a.namedBool("ocr", false),
                a.namedString("lang", "eng"), a.namedBool("strict", false), engine);
    }

    public static final class State extends TableProducerState {
        public boolean byPage;
        public boolean ocr;
        public String lang;
        public boolean strict;
        public byte[] bytes;
        public String path;
        public boolean done;
        public transient TikaEngine engine;

        public State() {}

        State(DocInput input, boolean byPage, boolean ocr, String lang, boolean strict, TikaEngine engine) {
            this.bytes = input.bytes();
            this.path = input.path() == null ? null : input.path().toString();
            this.byPage = byPage;
            this.ocr = ocr;
            this.lang = lang;
            this.strict = strict;
            this.engine = engine;
        }

        private TikaEngine engine() { return engine != null ? engine : TikaEngine.shared(); }

        @Override public void produceTick(OutputCollector out, CallContext ctx) {
            if (done) { out.finish(); return; }
            done = true;

            Path p = path == null ? null : Path.of(path);

            if (byPage) {
                List<TikaEngine.PageResult> pages = engine().extractByPage(bytes, p, ocr, lang);
                if (strict) {
                    for (TikaEngine.PageResult page : pages) {
                        if (page.result().error() != null) {
                            throw new RuntimeException("tika.extract failed: " + page.result().error());
                        }
                    }
                }
                out.emit(buildByPage(Allocators.root(), pages));
                out.finish();
                return;
            }

            TikaEngine.ExtractResult r = engine().extract(bytes, p, ocr, lang);
            if (strict && r.error() != null) {
                throw new RuntimeException("tika.extract failed: " + r.error());
            }
            out.emit(TikaSchemas.singleRow(Allocators.root(), r));
            out.finish();
        }

        /** Build a by-page Arrow batch: one row per {@link TikaEngine.PageResult}. */
        private static VectorSchemaRoot buildByPage(BufferAllocator alloc, List<TikaEngine.PageResult> pages) {
            VectorSchemaRoot root = VectorSchemaRoot.create(
                    TikaSchemas.EXTRACT_BY_PAGE_SCHEMA, alloc);
            root.allocateNew();
            MapVector metaVec = (MapVector) root.getVector("meta");
            for (int i = 0; i < pages.size(); i++) {
                TikaEngine.PageResult page = pages.get(i);
                TikaEngine.ExtractResult r = page.result();
                TikaSchemas.setInt(root, "page", i, page.page());
                TikaSchemas.setUtf8(root, "content", i, r.content());
                TikaSchemas.setUtf8(root, "mime", i, r.mime());
                TikaSchemas.setInt(root, "n_pages", i, r.nPages());
                TikaSchemas.setUtf8(root, "lang", i, r.lang());
                TikaSchemas.writeMap(metaVec, i, r.meta());
                TikaSchemas.setUtf8(root, "error", i, r.error());
            }
            root.setRowCount(pages.size());
            return root;
        }
    }
}
