package farm.query.vgi.tika;

import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;

import java.util.List;

/**
 * {@code tika.detect_lang_conf(text) -> DOUBLE} — the confidence (0.0–1.0) of the
 * top language detected for a piece of text, the companion to {@code detect_lang}.
 * Returns NULL when no language is found, mirroring {@code detect_lang}'s NULL.
 *
 * <p>Tika reports confidence as a {@code Confidence} enum (NONE/LOW/MEDIUM/HIGH);
 * we surface the underlying raw probability via {@link LanguageResult#getRawScore()}
 * so callers get a continuous score they can threshold.
 */
public final class DetectLangConfFunction extends ScalarFn {

    // LanguageDetector instances are not thread-safe for concurrent detect()
    // calls; build per-thread copies lazily (mirrors DetectLangFunction).
    private static final ThreadLocal<LanguageDetector> DETECTOR =
            ThreadLocal.withInitial(() -> {
                try {
                    return LanguageDetector.getDefaultLanguageDetector().loadModels();
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

    @Override public String name() { return "detect_lang_conf"; }

    @Override public String description() {
        return "Confidence (0.0-1.0) of the top detected language for a piece of text "
                + "(Apache Tika language detection); the companion score to detect_lang.";
    }

    @Override public FunctionMetadata metadata() {
        return FunctionMetadata.describe(description())
                .withCategories("text", "detection", "tika")
                .withExamples(List.of(
                        new FunctionExample(
                                "SELECT tika.main.detect_lang_conf('The quick brown fox jumps over the lazy dog.');",
                                "Confidence (0.0-1.0) of the top detected language for a "
                                        + "piece of text.",
                                null),
                        new FunctionExample(
                                "SELECT tika.main.detect_lang(content) AS lang, "
                                        + "tika.main.detect_lang_conf(content) AS conf "
                                        + "FROM tika.main.extract('The annual report covers quarterly results.'::BLOB);",
                                "Report the detected language and its confidence for an "
                                        + "extracted document body.",
                                null)))
                .withTags(Meta.objectTags(
                        "Detect Text Language Confidence",
                        "## detect_lang_conf\n\n"
                                + "Return the detector's confidence in the top language it found for a "
                                + "piece of text, as a continuous score in `0.0`–`1.0`. This is the "
                                + "companion to `detect_lang`: that function gives the ISO-639 code, this "
                                + "one gives how sure the model is.\n\n"
                                + "**Input** — a `VARCHAR` of text.\n\n"
                                + "**Output** — a `DOUBLE` raw probability (`0.0`–`1.0`), or `NULL` when "
                                + "the input is `NULL`/blank or no language is detected (mirroring "
                                + "`detect_lang`'s `NULL`).\n\n"
                                + "Tika exposes language confidence as a coarse `NONE`/`LOW`/`MEDIUM`/`HIGH` "
                                + "enum; this function surfaces the underlying raw score so callers can "
                                + "apply their own threshold (e.g. keep rows where confidence `> 0.5`).",
                        "# detect_lang_conf\n\n"
                                + "Returns the confidence (`0.0`–`1.0`) of the top detected language as a "
                                + "`DOUBLE`.\n\n"
                                + "## Usage\n\n"
                                + "Run it alongside `detect_lang` to keep only high-confidence language "
                                + "tags, or to rank ambiguous rows.\n\n"
                                + "## Notes\n\n"
                                + "- Returns `NULL` for `NULL`, blank, or undetected input.\n"
                                + "- The score is the model's raw probability, not Tika's coarse enum.\n"
                                + "- Longer text yields more reliable confidence.",
                        "language confidence, language score, detect language, confidence, "
                                + "probability, iso-639, language detection, threshold",
                        "DetectLangConfFunction.java"))
                .withTag("vgi.example_queries", Main.exampleQueriesTag(
                        "SELECT tika.main.detect_lang_conf('The quick brown fox jumps over the lazy dog.');",
                        "Confidence (0.0-1.0) of the top detected language for a piece of text.",
                        "SELECT tika.main.detect_lang(content) AS lang, tika.main.detect_lang_conf(content) AS conf "
                                + "FROM tika.main.extract('The annual report covers quarterly results.'::BLOB);",
                        "Report the detected language and its confidence for an extracted document body."));
    }

    public void compute(@Vector("text") VarCharVector in, Float8Vector out) {
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
                out.setSafe(i, r.getRawScore());
            }
        }
    }
}
