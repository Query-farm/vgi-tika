package farm.query.vgi.tika;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.protocol.BindResponse;
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
                .withCategories("document", "metadata", "tika");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        // Polymorphic doc arg (VARCHAR path or BLOB bytes) — see ExtractFunction.
        return List.of(
                ArgSpec.any("doc", 0, List.of()),
                ArgSpec.named("strict", Schemas.BOOL, "false"));
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
        return new State(input, a.namedBool("strict", false), engine);
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
