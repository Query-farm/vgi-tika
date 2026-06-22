package farm.query.vgi.tika;

import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Setting;
import farm.query.vgi.scalar.Vector;
import farm.query.vgi.types.Schemas;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;

import java.nio.file.Path;

/**
 * {@code tika.ocr(bytes | path, lang := 'eng') -> VARCHAR} — OCR an image or
 * scanned PDF via Tika's Tesseract parser. {@code lang} is a {@code +}-joined
 * trained-data list (e.g. {@code 'eng+deu'}).
 *
 * <p>Guarded by Tesseract availability: if the {@code tesseract} binary is not
 * on {@code PATH}, every row returns NULL rather than failing the query.
 */
public final class OcrFunction extends ScalarFn {

    private final TikaEngine engine;

    public OcrFunction() { this(TikaEngine.shared()); }
    public OcrFunction(TikaEngine engine) { this.engine = engine; }

    @Override public String name() { return "ocr"; }

    @Override public String description() {
        return "OCR an image or scanned PDF (bytes or path) via Tika's Tesseract parser; "
                + "returns NULL when Tesseract is unavailable.";
    }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(description()).withCategories("document", "ocr", "tika");
    }

    @Override protected ArrowType outputType(Schema inputSchema, Arguments args) {
        return Schemas.UTF8;
    }

    public void compute(@Vector(value = "doc", any = true) FieldVector in,
                        @Setting(default_ = "eng") String lang,
                        VarCharVector out) {
        String ocrLang = (lang == null || lang.isBlank()) ? "eng" : lang;
        boolean available = engine.tesseractAvailable();
        int n = in.getValueCount();
        for (int i = 0; i < n; i++) {
            if (in.isNull(i) || !available) { out.setNull(i); continue; }
            String text;
            if (in instanceof VarBinaryVector b) {
                text = engine.ocr(b.get(i), null, ocrLang);
            } else if (in instanceof VarCharVector s) {
                text = engine.ocr(null, Path.of(s.getObject(i).toString()), ocrLang);
            } else {
                DocInput input = DocInput.fromArgument(in.getObject(i), in.getField().getType());
                text = engine.ocr(input.bytes(), input.path(), ocrLang);
            }
            if (text == null) out.setNull(i);
            else out.setSafe(i, new Text(text));
        }
    }
}
