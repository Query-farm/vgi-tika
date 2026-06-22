package farm.query.vgi.tika;

import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.util.Text;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;

/**
 * {@code tika.detect_lang(text) -> VARCHAR} — detect the ISO-639 language code
 * of a piece of text using Tika's {@link LanguageDetector} (Optimaize models).
 * Returns NULL when no confident language is found.
 */
public final class DetectLangFunction extends ScalarFn {

    // LanguageDetector instances are not thread-safe for concurrent detect()
    // calls; build per-thread copies lazily.
    private static final ThreadLocal<LanguageDetector> DETECTOR =
            ThreadLocal.withInitial(() -> {
                try {
                    return LanguageDetector.getDefaultLanguageDetector().loadModels();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

    @Override public String name() { return "detect_lang"; }

    @Override public String description() {
        return "Detect the ISO-639 language code of a piece of text (Apache Tika language detection).";
    }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(description()).withCategories("text", "detection", "tika");
    }

    public void compute(@Vector("text") VarCharVector in, VarCharVector out) {
        LanguageDetector detector;
        try {
            detector = DETECTOR.get();
        } catch (Exception e) {
            // Models unavailable on the classpath — emit all-null rather than fail.
            for (int i = 0; i < in.getValueCount(); i++) out.setNull(i);
            return;
        }
        int n = in.getValueCount();
        for (int i = 0; i < n; i++) {
            if (in.isNull(i)) { out.setNull(i); continue; }
            String text = in.getObject(i).toString();
            if (text.isBlank()) { out.setNull(i); continue; }
            LanguageResult r = detector.detect(text);
            if (r == null || r.isUnknown()) {
                out.setNull(i);
            } else {
                out.setSafe(i, new Text(r.getLanguage()));
            }
        }
    }
}
