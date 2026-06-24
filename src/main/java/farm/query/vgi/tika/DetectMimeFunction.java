package farm.query.vgi.tika;

import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Vector;
import farm.query.vgi.types.Schemas;

import java.util.List;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.nio.file.Path;

/**
 * {@code tika.detect_mime(bytes | path) -> VARCHAR} — detect the media type of a
 * document from its bytes (a {@code BLOB} column) or a {@code VARCHAR} path.
 *
 * <p>One polymorphic ({@code any}-typed) input handled at runtime: a binary
 * vector is sniffed as bytes, a text vector as a file path.
 */
public final class DetectMimeFunction extends ScalarFn {

    private final TikaEngine engine;

    public DetectMimeFunction() { this(TikaEngine.shared()); }
    public DetectMimeFunction(TikaEngine engine) { this.engine = engine; }

    @Override public String name() { return "detect_mime"; }

    @Override public String description() {
        return "Detect a document's MIME type from its bytes or a file path (Apache Tika).";
    }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(description())
                .withCategories("document", "detection", "tika")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT tika.main.detect_mime('/docs/report.pdf');",
                                "Detect the MIME type of a document from its file path "
                                        + "(returns e.g. 'application/pdf').",
                                null),
                        new FunctionExample(
                                "SELECT tika.main.detect_mime(read_blob('/docs/report.pdf'));",
                                "Detect the MIME type from the document's bytes (a BLOB) "
                                        + "instead of a path.",
                                null)))
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT tika.main.detect_mime('/docs/report.pdf');",
                        "Detect the MIME type of a document from its file path (returns e.g. 'application/pdf').",
                        "SELECT tika.main.detect_mime(read_blob('/docs/report.pdf'));",
                        "Detect the MIME type from the document's bytes (a BLOB) instead of a path."));
    }

    /** Always VARCHAR, regardless of the (any-typed) input. */
    @Override protected ArrowType outputType(Schema inputSchema, Arguments args) {
        return Schemas.UTF8;
    }

    public void compute(@Vector(value = "doc", any = true) FieldVector in, VarCharVector out) {
        int n = in.getValueCount();
        for (int i = 0; i < n; i++) {
            if (in.isNull(i)) { out.setNull(i); continue; }
            String mime;
            if (in instanceof VarBinaryVector b) {
                mime = engine.detectMime(b.get(i), null);
            } else if (in instanceof VarCharVector s) {
                mime = engine.detectMime(null, Path.of(s.getObject(i).toString()));
            } else {
                Object obj = in.getObject(i);
                DocInput input = DocInput.fromArgument(obj, in.getField().getType());
                mime = engine.detectMime(input.bytes(), input.path());
            }
            if (mime == null) out.setNull(i);
            else out.setSafe(i, new Text(mime));
        }
    }
}
