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
                .withTag("vgi.category", "Content Detection")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT tika.main.detect_mime('Hello, plain text body.'::BLOB);",
                                "Detect the MIME type of inline document bytes (returns 'text/plain').",
                                null),
                        new FunctionExample(
                                "SELECT tika.main.detect_mime('/docs/report.pdf');",
                                "Detect the MIME type of a document from its file path "
                                        + "(returns e.g. 'application/pdf').",
                                null)))
                .withTags(Meta.objectTags(
                        "Detect Document MIME Type",
                        "## detect_mime\n\n"
                                + "Sniff the media (MIME) type of a document using Apache Tika's "
                                + "container-aware detector, working from the document's magic bytes "
                                + "rather than its file extension.\n\n"
                                + "**Input** — a single `any`-typed argument resolved at runtime: a "
                                + "`BLOB` of the raw document bytes, or a `VARCHAR` filesystem path the "
                                + "worker opens.\n\n"
                                + "**Output** — a `VARCHAR` media type such as `application/pdf`, "
                                + "`application/vnd.openxmlformats-officedocument.wordprocessingml.document`, "
                                + "or `text/plain`; `NULL` for a `NULL` input.\n\n"
                                + "Use it to route, filter, or validate documents by type before "
                                + "calling the heavier `extract` / `metadata` table functions.",
                        "# detect_mime\n\n"
                                + "Returns the detected media type of a document as a `VARCHAR`.\n\n"
                                + "## Usage\n\n"
                                + "Pass either a `BLOB` of document bytes or a `VARCHAR` file path; "
                                + "detection is content-based (Tika reads the leading bytes), so it does "
                                + "not rely on the file name.\n\n"
                                + "## Notes\n\n"
                                + "- Returns `NULL` for a `NULL` input.\n"
                                + "- Office formats are correctly disambiguated by inspecting the OOXML/ODF "
                                + "container, not just the ZIP signature.",
                        "mime", "media type", "content type", "detect mime", "file type",
                        "magic bytes", "sniff", "content-type", "tika detect"))
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT tika.main.detect_mime('Hello, plain text body.'::BLOB);",
                        "Detect the MIME type of inline document bytes (returns 'text/plain').",
                        "SELECT tika.main.detect_mime('/docs/report.pdf');",
                        "Detect the MIME type of a document from its file path (returns e.g. 'application/pdf')."));
    }

    /** Always VARCHAR, regardless of the (any-typed) input. */
    @Override protected ArrowType outputType(Schema inputSchema, Arguments args) {
        return Schemas.UTF8;
    }

    public void compute(
            @Vector(value = "doc", any = true,
                    doc = "The document to sniff. Pass either the raw document bytes inline "
                            + "(detected from its magic bytes) or a filesystem path the worker "
                            + "opens; the worker dispatches on the runtime value.")
            FieldVector in, VarCharVector out) {
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
