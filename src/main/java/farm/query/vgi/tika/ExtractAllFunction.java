package farm.query.vgi.tika;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
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
                .withCategories("document", "extraction", "tika");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        return List.of(
                ArgSpec.table("input", 0),
                ArgSpec.named("doc_column", Schemas.UTF8, ""),
                ArgSpec.named("id", Schemas.UTF8, ""),
                ArgSpec.named("ocr", Schemas.BOOL, "false"),
                ArgSpec.named("lang", Schemas.UTF8, "eng"));
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
