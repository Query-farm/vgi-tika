package farm.query.vgi.tika;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.ParameterExtractor;
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
import org.apache.arrow.vector.types.pojo.ArrowType;

import java.nio.file.Path;
import java.util.List;

/**
 * {@code tika.metadata(path | bytes, strict := false)} — like {@code extract}
 * but returns metadata only (no body text). One row per call.
 */
public final class MetadataFunction implements TableFunction {

    private final TikaEngine engine;

    public MetadataFunction() { this(TikaEngine.shared()); }
    public MetadataFunction(TikaEngine engine) { this.engine = engine; }

    @Override public String name() { return "metadata"; }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(
                        "Extract document metadata (MIME, page count, language, full metadata MAP) "
                                + "without the body text, via Apache Tika.")
                .withCategories("document", "metadata", "tika")
                .withTag("vgi.category", "Text & Metadata Extraction")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT mime, n_pages, lang FROM tika.main.metadata('A short document body.'::BLOB);",
                                "Read a document's MIME type, page count, and language without "
                                        + "extracting the body text.",
                                null),
                        new FunctionExample(
                                "SELECT meta['Content-Type'] AS ct FROM tika.main.metadata('Some bytes.'::BLOB);",
                                "Read a single metadata field from the `meta` MAP.",
                                null)))
                .withTags(Meta.objectTags(
                        "Extract Document Metadata Only",
                        "## metadata\n\n"
                                + "Like `extract`, but returns only a document's metadata — its MIME "
                                + "type, page count, language, and the full Tika metadata bag — without "
                                + "the (potentially large) body text. One row per call.\n\n"
                                + "**Input** — an `any`-typed `doc` argument: a `VARCHAR` path the worker "
                                + "opens or a `BLOB` of document bytes. `strict := true` re-raises parse "
                                + "errors instead of capturing them in the `error` column.\n\n"
                                + "**Output** — a single row with `mime`, `n_pages`, `lang`, a `meta` "
                                + "`MAP(VARCHAR, VARCHAR)`, and an `error` column.\n\n"
                                + "Use it when you only need provenance/structural metadata (author, "
                                + "created date, content type, page count) and want to avoid the cost of "
                                + "extracting and transferring full text.",
                        "# metadata\n\n"
                                + "Returns a document's metadata without its body text.\n\n"
                                + "## Usage\n\n"
                                + "Pass a path or bytes; read individual fields from the `meta` `MAP` "
                                + "(e.g. `meta['Author']`, `meta['Content-Type']`).\n\n"
                                + "## Notes\n\n"
                                + "- Cheaper than `extract` when the body text is not needed.\n"
                                + "- Per-row error capture in the `error` column unless `strict := true`.\n"
                                + "- See the returned-columns table below for the exact output shape.",
                        "metadata", "document metadata", "mime", "page count", "language",
                        "author", "properties", "tika", "meta map", "content-type"))
                .withTag("vgi.result_columns_schema", RESULT_COLUMNS_SCHEMA)
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT mime, n_pages, lang FROM tika.main.metadata('A short document body.'::BLOB);",
                        "Read a document's MIME type, page count, and language without extracting the body text.",
                        "SELECT meta['Content-Type'] AS ct FROM tika.main.metadata('Some bytes.'::BLOB);",
                        "Read a single metadata field from the `meta` MAP."));
    }

    /**
     * Static result schema of {@code metadata} (VGI307), as the structured
     * {@code vgi.result_columns_schema} JSON array of {@code {name,type,description}}.
     * The shape is fixed (unlike {@code extract}, which has a {@code by_page}
     * variant), so a static schema is the correct carrier.
     */
    static final String RESULT_COLUMNS_SCHEMA =
            "[\n"
                    + "  {\"name\": \"mime\", \"type\": \"VARCHAR\", \"description\": "
                    + "\"Detected media type, e.g. application/pdf.\"},\n"
                    + "  {\"name\": \"n_pages\", \"type\": \"INTEGER\", \"description\": "
                    + "\"Page/slide/sheet count when the format reports it, else NULL.\"},\n"
                    + "  {\"name\": \"lang\", \"type\": \"VARCHAR\", \"description\": "
                    + "\"Document language code if Tika reported one, else NULL.\"},\n"
                    + "  {\"name\": \"meta\", \"type\": \"MAP(VARCHAR, VARCHAR)\", \"description\": "
                    + "\"Full Tika metadata bag as a MAP(VARCHAR, VARCHAR).\"},\n"
                    + "  {\"name\": \"error\", \"type\": \"VARCHAR\", \"description\": "
                    + "\"Per-row parse error message, or NULL on success.\"}\n"
                    + "]";

    @Override public List<ArgSpec> argumentSpecs() {
        // Polymorphic doc arg (VARCHAR path or BLOB bytes) — see ExtractFunction.
        return List.of(
                Meta.anyArg("doc", 0,
                        "The document whose metadata to read. Pass either a filesystem path "
                                + "(the worker opens the file) or the raw document bytes inline; "
                                + "the worker dispatches on the runtime value."),
                Meta.namedArg("strict", Schemas.BOOL, "false",
                        "When true, re-raise any parse error and fail the query instead of "
                                + "capturing it in the `error` column (NULL metadata). Defaults to "
                                + "false (errors are captured per-row)."));
    }

    @Override public BindResponse onBind(TableBindParams p) {
        return BindResponse.forSchema(SchemaUtil.serializeSchema(TikaSchemas.METADATA_SCHEMA));
    }

    @Override public long cardinality(TableBindParams p) { return 1L; }

    @Override public TableProducerState createProducer(TableInitParams params) {
        Arguments a = params.arguments();
        Object docValue = a.positionalAt(0);
        ArrowType docType = a.positionalTypeAt(0);
        DocInput input = DocInput.fromArgument(docValue, docType);
        return new State(input,
                ParameterExtractor.of(a).named("strict").asBool().orElse(false), engine);
    }

    public static final class State extends TableProducerState {
        public byte[] bytes;
        public String path;
        public boolean strict;
        public boolean done;
        public transient TikaEngine engine;

        public State() {}

        State(DocInput input, boolean strict, TikaEngine engine) {
            this.bytes = input.bytes();
            this.path = input.path() == null ? null : input.path().toString();
            this.strict = strict;
            this.engine = engine;
        }

        private TikaEngine engine() { return engine != null ? engine : TikaEngine.shared(); }

        @Override public void produceTick(OutputCollector out, CallContext ctx) {
            if (done) { out.finish(); return; }
            done = true;
            Path p = path == null ? null : Path.of(path);
            TikaEngine.ExtractResult r = engine().metadataOnly(bytes, p);
            if (strict && r.error() != null) {
                throw new RuntimeException("tika.metadata failed: " + r.error());
            }
            VectorSchemaRoot root = TikaSchemas.metadataRow(Allocators.root(), r);
            out.emit(root);
            out.finish();
        }
    }
}
