package farm.query.vgi.tika;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.tableinout.TableInOutBindParams;
import farm.query.vgi.tableinout.TableInOutExchangeState;
import farm.query.vgi.tableinout.TableInOutFunction;
import farm.query.vgi.tableinout.TableInOutInitParams;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.AnnotatedBatch;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code tika.extract_all((SELECT id, body FROM files), id := 'id')} — a
 * table-in-out that streams a column of documents (paths in a {@code VARCHAR}
 * column or bytes in a {@code BLOB} column) into one extract row each.
 *
 * <p>The {@code id} column named by the {@code id} argument is excluded from
 * processing and copied verbatim onto every output row so results join back to
 * the source. Per-row parse errors are captured (NULL content + {@code error}).
 */
public final class ExtractAllFunction implements TableInOutFunction {

    private final TikaEngine engine;

    public ExtractAllFunction() { this(TikaEngine.shared()); }
    public ExtractAllFunction(TikaEngine engine) { this.engine = engine; }

    @Override public String name() { return "extract_all"; }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(
                        "Extract text + metadata for a whole column of documents (paths or bytes), "
                                + "with an id column passed through onto each output row.")
                .withCategories("document", "extraction", "tika")
                .withTag("vgi.category", "Text & Metadata Extraction")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT id, content, mime FROM tika.main.extract_all("
                                        + "(SELECT * FROM (VALUES (1, 'First body.'::BLOB), (2, 'Second body.'::BLOB)) AS t(id, body)), "
                                        + "id := 'id', doc_column := 'body');",
                                "Extract every document in an inline column of bytes, keeping the "
                                        + "source `id` on each output row to join results back.",
                                null),
                        new FunctionExample(
                                "SELECT id, content FROM tika.main.extract_all("
                                        + "(SELECT * FROM (VALUES (1, 'Inline document bytes.'::BLOB)) AS t(id, body)), "
                                        + "id := 'id', doc_column := 'body');",
                                "Extract from a BLOB column (`body`) of inline document bytes, with "
                                        + "the `id` column passed through.",
                                null)))
                .withTags(Meta.objectTags(
                        "Extract All Documents in a Column",
                        "## extract_all\n\n"
                                + "A table-in-out function that runs `extract` over a whole column of "
                                + "documents at once. Stream a relation whose column holds either "
                                + "filesystem paths (`VARCHAR`) or document bytes (`BLOB`); the function "
                                + "emits one extract row per input row.\n\n"
                                + "**Input** — a table argument plus named options: `doc_column` selects "
                                + "the document column (defaults to the first non-id column); `id` names a "
                                + "passthrough column that is excluded from parsing and copied verbatim "
                                + "onto every output row so results join back to the source; `ocr` and "
                                + "`lang` control OCR as in `extract`.\n\n"
                                + "**Output** — the `extract` column set (`content`, `mime`, `n_pages`, "
                                + "`lang`, `meta`, `error`), optionally prefixed by the `id` passthrough "
                                + "column.\n\n"
                                + "**Error handling** — per-row: a failed document yields `NULL` content "
                                + "and an `error` message; the rest of the batch still processes. Use this "
                                + "to bulk-extract a corpus referenced by a SQL relation.",
                        "# extract_all\n\n"
                                + "Bulk-extracts text and metadata for a column of documents, with an "
                                + "id passthrough.\n\n"
                                + "## Usage\n\n"
                                + "Pass a subquery as the table argument; set `doc_column` to the "
                                + "path/bytes column and `id` to a key column copied onto each result row "
                                + "for joining back to the source.\n\n"
                                + "## Notes\n\n"
                                + "- Accepts a `VARCHAR` path column or a `BLOB` bytes column.\n"
                                + "- Per-row error capture (NULL `content` + `error`).\n"
                                + "- See the returned-columns table below for the exact output shape.",
                        "extract all", "bulk extract", "batch", "column of documents",
                        "table function", "passthrough id", "corpus", "tika", "paths",
                        "blobs"))
                .withTag("vgi.result_dynamic_columns_md", COLUMNS_MD)
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT id, content, mime FROM tika.main.extract_all("
                                + "(SELECT * FROM (VALUES (1, 'First body.'::BLOB), (2, 'Second body.'::BLOB)) AS t(id, body)), "
                                + "id := 'id', doc_column := 'body');",
                        "Extract every document in an inline column of bytes, keeping the source `id` "
                                + "on each output row to join results back.",
                        "SELECT id, content FROM tika.main.extract_all("
                                + "(SELECT * FROM (VALUES (1, 'Inline document bytes.'::BLOB)) AS t(id, body)), "
                                + "id := 'id', doc_column := 'body');",
                        "Extract from a BLOB column (`body`) of inline document bytes, with the `id` column passed through."));
    }

    /**
     * Markdown table of the columns returned by {@code extract_all}. The output
     * is the {@code extract} column set, optionally prefixed by the passthrough
     * column named by the {@code id} argument (copied verbatim from each input
     * row); the optional passthrough is noted inline.
     */
    static final String COLUMNS_MD =
            "### Without id passthrough (`id := ''`)\n\n"
                    + "| name | type | description |\n"
                    + "|---|---|---|\n"
                    + "| `content` | VARCHAR | Extracted plain-text body of the document, or NULL on error. |\n"
                    + "| `mime` | VARCHAR | Detected media type, e.g. `application/pdf`. |\n"
                    + "| `n_pages` | INTEGER | Page/slide/sheet count when the format reports it, else NULL. |\n"
                    + "| `lang` | VARCHAR | Document language code if Tika reported one. |\n"
                    + "| `meta` | MAP(VARCHAR, VARCHAR) | Full Tika metadata bag. |\n"
                    + "| `error` | VARCHAR | Per-row parse error message, or NULL on success. |\n\n"
                    + "### With id passthrough (`id := '<column>'`)\n\n"
                    + "| name | type | description |\n"
                    + "|---|---|---|\n"
                    + "| `id` | INTEGER | Passthrough column named by the `id` argument (shown here as "
                    + "`id`), copied verbatim from each input row so results can be joined back. Its "
                    + "name and type mirror the chosen source column — INTEGER in these examples. |\n"
                    + "| `content` | VARCHAR | Extracted plain-text body of the document, or NULL on error. |\n"
                    + "| `mime` | VARCHAR | Detected media type, e.g. `application/pdf`. |\n"
                    + "| `n_pages` | INTEGER | Page/slide/sheet count when the format reports it, else NULL. |\n"
                    + "| `lang` | VARCHAR | Document language code if Tika reported one. |\n"
                    + "| `meta` | MAP(VARCHAR, VARCHAR) | Full Tika metadata bag. |\n"
                    + "| `error` | VARCHAR | Per-row parse error message, or NULL on success. |";

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                Meta.tableArg("input", 0,
                        "The input relation (a subquery) supplying the documents to extract. "
                                + "A column of this relation holds the documents as filesystem "
                                + "paths or inline bytes (see `doc_column`); one extract row is "
                                + "produced per input row."),
                Meta.namedArg("doc_column", Schemas.UTF8, "",
                        "Name of the input column holding the documents (filesystem paths or "
                                + "inline bytes). When empty (default), the first column that is "
                                + "not the `id` column is used."),
                Meta.namedArg("id", Schemas.UTF8, "",
                        "Name of an input column to pass through: it is excluded from parsing "
                                + "and copied verbatim onto every output row so results can be "
                                + "joined back to the source. When empty (default), no passthrough "
                                + "column is emitted."),
                Meta.namedArg("ocr", Schemas.BOOL, "false",
                        "When true, force OCR of each document (images/scanned PDFs) instead of "
                                + "born-digital text extraction. Defaults to false."),
                Meta.namedArg("lang", Schemas.UTF8, "eng",
                        "Tesseract OCR language(s) to use when OCR runs, as a `+`-joined "
                                + "trained-data list (e.g. `eng` or `eng+deu`). Defaults to "
                                + "English."));
    }

    /** Output schema = optional id passthrough column + the extract columns. */
    static Schema outputSchema(Schema inputSchema, String idColumn) {
        List<Field> fields = new ArrayList<>();
        if (idColumn != null && !idColumn.isEmpty() && inputSchema != null) {
            Field id = findField(inputSchema, idColumn);
            if (id != null) {
                fields.add(new Field(id.getName(),
                        new FieldType(true, id.getType(), null,
                                Map.of("comment", "Passthrough id from the source row.")),
                        id.getChildren()));
            }
        }
        fields.addAll(TikaSchemas.EXTRACT_SCHEMA.getFields());
        return new Schema(fields);
    }

    private static Field findField(Schema schema, String name) {
        for (Field f : schema.getFields()) {
            if (f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    /** The document column: explicit {@code doc_column}, else the first non-id column. */
    static String resolveDocColumn(Schema inputSchema, String docColumn, String idColumn) {
        if (docColumn != null && !docColumn.isEmpty()) return docColumn;
        for (Field f : inputSchema.getFields()) {
            if (idColumn == null || idColumn.isEmpty() || !f.getName().equalsIgnoreCase(idColumn)) {
                return f.getName();
            }
        }
        return inputSchema.getFields().isEmpty() ? null : inputSchema.getFields().get(0).getName();
    }

    @Override public BindResponse onBind(TableInOutBindParams p) {
        String idColumn = p.arguments().namedString("id", "");
        Schema out = outputSchema(p.inputSchema(), idColumn);
        return BindResponse.forSchema(SchemaUtil.serializeSchema(out));
    }

    @Override public TableInOutExchangeState createExchange(TableInOutInitParams params) {
        Arguments a = params.arguments();
        String idColumn = a.namedString("id", "");
        String docColumn = resolveDocColumn(params.inputSchema(), a.namedString("doc_column", ""), idColumn);
        Schema outSchema = outputSchema(params.inputSchema(), idColumn);
        return new State(outSchema, docColumn, idColumn, a.namedBool("ocr", false),
                a.namedString("lang", "eng"), engine);
    }

    /**
     * Per-exchange state for {@code extract_all}.
     *
     * <p><b>HTTP-serializable.</b> Over the HTTP transport the worker is stateless
     * across exchanges, so the framework gob/CBOR-serializes this state into an
     * opaque continuation token between exchanges (see
     * {@code StateSerializer} — it walks the declared fields, skipping {@code
     * static}/{@code transient}/{@code synthetic}). Every persisted field must
     * therefore be plain and serializable: we store the output schema as its
     * serialized {@code byte[]} (an Arrow {@code Schema} is not CBOR-serializable)
     * plus scalar strings/booleans, and keep the non-serializable {@link
     * TikaEngine} and the rebuilt Arrow {@link Schema} as {@code transient}
     * fields rebuilt lazily on the worker side. A no-arg constructor is required
     * so the deserializer can instantiate it. (The earlier {@code final
     * TikaEngine}/{@code final Schema} fields made the state unserializable and
     * the http {@code /init} returned 500 "state serialize failed".)
     */
    public static final class State extends TableInOutExchangeState {
        // Persisted (serializable) fields — public + non-final so the CBOR
        // (de)serializer reads/writes them.
        public byte[] outSchemaBytes;
        public String docColumn;
        public String idColumn;
        public boolean ocr;
        public String lang;

        // Reconstructed on the worker; excluded from serialization.
        private transient Schema outSchema;
        private transient TikaEngine engine;

        public State() {}

        State(Schema outSchema, String docColumn, String idColumn, boolean ocr, String lang, TikaEngine engine) {
            this.outSchema = outSchema;
            this.outSchemaBytes = SchemaUtil.serializeSchema(outSchema);
            this.docColumn = docColumn;
            this.idColumn = idColumn;
            this.ocr = ocr;
            this.lang = lang;
            this.engine = engine;
        }

        private Schema outSchema() {
            if (outSchema == null) {
                outSchema = SchemaUtil.deserializeSchema(outSchemaBytes);
            }
            return outSchema;
        }

        private TikaEngine engine() { return engine != null ? engine : TikaEngine.shared(); }

        @Override public void onInputBatch(AnnotatedBatch batch, OutputCollector out, CallContext ctx) {
            VectorSchemaRoot in = batch.root();
            int n = in.getRowCount();
            if (n == 0) return;

            FieldVector docVec = docColumn == null ? null : in.getVector(docColumn);
            boolean hasId = idColumn != null && !idColumn.isEmpty();
            FieldVector idVec = hasId ? in.getVector(idColumn) : null;

            VectorSchemaRoot root = VectorSchemaRoot.create(outSchema(), Allocators.root());
            root.allocateNew();
            MapVector metaVec = (MapVector) root.getVector("meta");

            for (int i = 0; i < n; i++) {
                if (idVec != null) {
                    copyId(idVec, root.getVector(idColumn), i);
                }
                DocInput input = docInputAt(docVec, i);
                TikaEngine.ExtractResult r = input == null
                        ? TikaEngine.ExtractResult.failure("missing document column value")
                        : engine().extract(input.bytes(), input.path(), ocr, lang);

                TikaSchemas.setUtf8(root, "content", i, r.content());
                TikaSchemas.setUtf8(root, "mime", i, r.mime());
                TikaSchemas.setInt(root, "n_pages", i, r.nPages());
                TikaSchemas.setUtf8(root, "lang", i, r.lang());
                TikaSchemas.writeMap(metaVec, i, r.meta());
                TikaSchemas.setUtf8(root, "error", i, r.error());
            }
            root.setRowCount(n);
            out.emit(root);
        }

        private DocInput docInputAt(FieldVector docVec, int row) {
            if (docVec == null || docVec.isNull(row)) return null;
            if (docVec instanceof VarBinaryVector b) {
                return new DocInput(b.get(row), null);
            }
            if (docVec instanceof VarCharVector s) {
                return new DocInput(null, Path.of(s.getObject(row).toString()));
            }
            ArrowType t = docVec.getField().getType();
            Object obj = docVec.getObject(row);
            return DocInput.fromArgument(obj, t);
        }

        private static void copyId(FieldVector src, FieldVector dst, int row) {
            if (src.isNull(row)) { dst.setNull(row); return; }
            if (dst instanceof VarCharVector d && src instanceof VarCharVector s) {
                d.setSafe(row, new Text(s.getObject(row).toString()));
            } else {
                dst.copyFromSafe(row, row, src);
            }
        }
    }
}
