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
                .withCategories("document", "extraction", "tika");
    }

    @Override public List<ArgSpec> argumentSpecs() {
        // Polymorphic doc arg: an any-typed positional so DuckDB binds both a
        // VARCHAR path (the worker opens the file) and a BLOB/BINARY of bytes.
        // The worker disambiguates on the runtime value/type via DocInput.
        return List.of(
                ArgSpec.any("doc", 0, List.of()),
                ArgSpec.named("by_page", Schemas.BOOL, "false"),
                ArgSpec.named("lang", Schemas.UTF8, "eng"),
                ArgSpec.named("ocr", Schemas.BOOL, "false"),
                ArgSpec.named("strict", Schemas.BOOL, "false"));
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
