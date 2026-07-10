package farm.query.vgi.tika;

import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.util.Text;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;

import java.util.List;

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
        return FunctionMetadata.describe(description())
                .withCategories("text", "detection", "tika")
                .withTag("vgi.category", "Content Detection")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT tika.main.detect_lang('The quick brown fox jumps over the lazy dog.');",
                                "Detect the ISO-639 language code of a piece of text "
                                        + "(returns 'en' here).",
                                null),
                        new FunctionExample(
                                "SELECT content, tika.main.detect_lang(content) AS lang "
                                        + "FROM tika.main.extract('Bonjour le monde, ceci est un texte.'::BLOB);",
                                "Detect the language of an extracted document's body text.",
                                null)))
                .withTags(Meta.objectTags(
                        "Detect Text Language Code",
                        "## detect_lang\n\n"
                                + "Identify the dominant natural language of a piece of text and return "
                                + "its ISO-639 code (e.g. `en`, `fr`, `de`), using Apache Tika's "
                                + "Optimaize n-gram language detector.\n\n"
                                + "**Input** — a `VARCHAR` of text (often the `content` column produced "
                                + "by `extract`).\n\n"
                                + "**Output** — a short ISO-639 language code, or `NULL` when the input "
                                + "is `NULL`/blank or no language can be detected confidently.\n\n"
                                + "Pair it with `detect_lang_conf` to threshold on detection confidence. "
                                + "Detection is most reliable on at least a sentence or two of text; very "
                                + "short strings may return `NULL`.",
                        "# detect_lang\n\n"
                                + "Returns the ISO-639 language code of a text string.\n\n"
                                + "## Usage\n\n"
                                + "Feed it any `VARCHAR` text — typically extracted document bodies — to "
                                + "tag rows by language for filtering or routing.\n\n"
                                + "## Notes\n\n"
                                + "- Returns `NULL` for `NULL`, blank, or low-confidence input.\n"
                                + "- Backed by the Optimaize models bundled with Tika; accuracy improves "
                                + "with longer text.\n"
                                + "- Use `detect_lang_conf` for the matching confidence score.",
                        "language", "detect language", "language detection", "iso-639",
                        "locale", "language code", "optimaize", "nlp"))
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT tika.main.detect_lang('The quick brown fox jumps over the lazy dog.');",
                        "Detect the ISO-639 language code of a piece of text (returns 'en' here).",
                        "SELECT content, tika.main.detect_lang(content) AS lang "
                                + "FROM tika.main.extract('Bonjour le monde, ceci est un texte en francais.'::BLOB);",
                        "Detect the language of an extracted document's body text."));
    }

    public void compute(
            @Vector(value = "text",
                    doc = "The text whose dominant natural language to detect — typically an "
                            + "extracted document body from `extract`. Text that is empty or too "
                            + "short to classify yields a NULL result.")
            VarCharVector in, VarCharVector out) {
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
